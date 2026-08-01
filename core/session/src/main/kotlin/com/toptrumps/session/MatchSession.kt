package com.toptrumps.session

import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.MatchConfig
import com.toptrumps.rules.MatchState
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.rules.RulesEngine
import com.toptrumps.rules.Seat
import com.toptrumps.rules.StepResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private val HEARTBEAT_INTERVAL = 2.seconds
private const val HEARTBEAT_MISSES_TO_UNREACHABLE = 3
private const val ABANDON_WINDOW_SECONDS = 60

/**
 * A player's side of one match. No hardcoded `Dispatchers.*`: every implementation runs its
 * coroutines on the [CoroutineScope] its caller supplies, which is what makes slice 6's
 * virtual-time tests possible without a signature change.
 */
public interface MatchSession {
    /** `null` only for the guest before its first view has arrived. */
    public val view: StateFlow<MatchView?>
    public val connectionState: StateFlow<ConnectionState>

    /**
     * Always `false` for the host, which applies its own intents synchronously. `true` for a
     * guest between `submit()` and the resolving view — lets the UI disable its buttons rather
     * than allow a second tap the session would just drop (the reconnect-resync ADR's
     * at-most-one-intent-in-flight invariant).
     */
    public val hasPendingIntent: StateFlow<Boolean>
    public fun submit(intent: PlayerIntent)

    /** A deliberate quit — sends a courtesy notice before tearing down, so the peer shows "X left the game" rather than a countdown. */
    public fun leave()

    /** Silent local teardown — no notice sent. Used when reacting to the peer already being gone. */
    public fun close()
}

/**
 * Shared heartbeat/unreachable/countdown bookkeeping for [HostMatchSession] and
 * [GuestMatchSession] — see the foreground-service ADR (2s ping, 3 misses ≈ 6s to declare
 * unreachable) and the reconnect-resync ADR (60s window). Owns only liveness tracking; transport
 * swapping and the resume handshake itself stay with the session, since only it holds the state
 * (or pending intent) a resume needs.
 */
private class ConnectionWatchdog(private val scope: CoroutineScope, private val sendHeartbeat: suspend () -> Unit) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Volatile: flipped from the incoming-frame collector, read from the heartbeat loop — two
    // different coroutines, no shared dispatcher guaranteed once a transport is swapped. Both
    // start `false` rather than optimistically `true` — the first tick must be a real
    // observation, or 3 misses would actually take 4 ticks (~8s) instead of the ADR's ~6s.
    @Volatile private var trafficSeenSinceLastTick = false
    @Volatile private var sentSinceLastTick = false
    private var missedTicks = 0
    private var countdownJob: Job? = null

    fun start() {
        scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL)
                if (!sentSinceLastTick) sendHeartbeat()
                sentSinceLastTick = false
                if (trafficSeenSinceLastTick) {
                    missedTicks = 0
                } else {
                    missedTicks++
                    if (missedTicks == HEARTBEAT_MISSES_TO_UNREACHABLE) declareUnreachable()
                }
                trafficSeenSinceLastTick = false
            }
        }
    }

    fun onOutboundTraffic() {
        sentSinceLastTick = true
    }

    /** Call on every inbound frame, heartbeat or otherwise — the "piggybacked on any traffic" rule. */
    fun onInboundTraffic() {
        trafficSeenSinceLastTick = true
        missedTicks = 0
        if (_state.value is ConnectionState.PeerUnreachable) restoreConnected()
    }

    fun onResumeSettled() = restoreConnected()

    fun onPeerLeft() {
        countdownJob?.cancel()
        _state.value = ConnectionState.PeerLeft
    }

    fun onPeerAbandoned(reason: AbandonReason) {
        countdownJob?.cancel()
        _state.value = ConnectionState.Abandoned(reason)
    }

    private fun restoreConnected() {
        countdownJob?.cancel()
        _state.value = ConnectionState.Connected
    }

    private fun declareUnreachable() {
        _state.value = ConnectionState.PeerUnreachable(ABANDON_WINDOW_SECONDS)
        countdownJob = scope.launch {
            for (remaining in (ABANDON_WINDOW_SECONDS - 1) downTo 0) {
                delay(1.seconds)
                _state.value = ConnectionState.PeerUnreachable(remaining)
            }
            _state.value = ConnectionState.Abandoned(AbandonReason.GRACE_EXPIRED)
        }
    }
}

/**
 * Runs [RulesEngine] against the authoritative [MatchState] and pushes a [MatchView] to the
 * guest after every transition. The host's own UI is served by [RulesEngine.project] directly —
 * it never sees [MatchState] either, per the structural-redaction ADR.
 *
 * [sessionToken] is the value handed out in the pre-match handshake's `HelloAck` — the caller
 * (`:app`'s `MatchController`) is the one who ran that handshake, so it supplies the token here
 * rather than this class minting its own.
 */
public class HostMatchSession(
    deck: Deck,
    config: MatchConfig,
    random: Random,
    initialTransport: Transport,
    private val scope: CoroutineScope,
    private val sessionToken: String,
) : MatchSession {

    private var transport: Transport = initialTransport
    private var state: MatchState = RulesEngine.deal(deck, config, random)
    private var lastAcceptedGuestSeq: Long = 0
    private var listenJob: Job? = null

    private val _view = MutableStateFlow(RulesEngine.project(state, Seat.HOST).toMatchView())
    override val view: StateFlow<MatchView?> = _view.asStateFlow()

    private val watchdog = ConnectionWatchdog(scope) { send(ProtocolCodec.encodeHostToGuest(HostToGuest.Heartbeat)) }
    override val connectionState: StateFlow<ConnectionState> get() = watchdog.state
    override val hasPendingIntent: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    init {
        scope.launch {
            // Discrete and first, ahead of any View — the guest's unambiguous "you have been
            // dealt in" trigger (TDD §5), sent before the round's actual first View.
            val guestHand = state.hands.getValue(Seat.GUEST).map { it.toRemote() }
            send(ProtocolCodec.encodeHostToGuest(HostToGuest.MatchStart(guestHand, state.totalRounds)))
            pushGuestView()
        }
        listen(transport)
        watchdog.start()
    }

    override fun submit(intent: PlayerIntent) {
        // Routed through `scope`, same as the guest-intent path below, rather than mutating
        // `state` synchronously on the caller's thread — the TDD requires all state mutation
        // confined to a single dispatcher. It's the caller's job to supply a confined `scope`
        // (:core:session cannot pin one itself without a hardcoded `Dispatchers.*` literal).
        scope.launch { applyIntent(Seat.HOST, intent) }
    }

    /**
     * Accepts a resume attempt arriving on a freshly dialled [newTransport] — routed here by the
     * app layer, which sniffs a fresh inbound connection's first frame rather than assuming it's
     * always a lobby invite (see the reconnect-resync ADR). An unrecognised token means either a
     * genuinely different match or a stale resume from a finished one — session tokens are
     * regenerated per rematch specifically so that can't collide with a live one.
     */
    public fun acceptResume(newTransport: Transport, resume: GuestToHost.Resume) {
        scope.launch {
            if (resume.sessionToken != sessionToken) {
                newTransport.send(ProtocolCodec.encodeHostToGuest(HostToGuest.ResumeRejected("UNKNOWN_SESSION")))
                newTransport.close() // rejected — nothing else is ever going to use this connection.
                return@launch
            }
            // The 60s window already ran out and the match is over — a resume arriving late (or
            // one that lost the race with the countdown's last tick) must not resurrect it.
            if (watchdog.state.value is ConnectionState.Abandoned) {
                newTransport.send(ProtocolCodec.encodeHostToGuest(HostToGuest.ResumeRejected("WINDOW_EXPIRED")))
                newTransport.close()
                return@launch
            }
            // The old transport is presumed dead (that's why a resume was needed at all) — closed
            // for hygiene, not gracefully: nothing meaningful is left to flush to it, and its
            // reader/writer coroutines would otherwise leak forever blocked on a half-open socket.
            val oldTransport = transport
            transport = newTransport
            listen(newTransport)
            oldTransport.close()
            when (val pending = resume.pendingIntent) {
                is GuestToHost.ChooseMetric -> applyGuestIntent(pending.seq, PlayerIntent.ChooseMetric(MetricKey(pending.metricKey)))
                is GuestToHost.AdvanceRound -> applyGuestIntent(pending.seq, PlayerIntent.AdvanceRound)
                else -> Unit // null — no intent was in flight when the drop happened.
            }
            send(ProtocolCodec.encodeHostToGuest(HostToGuest.ResumeAck(RulesEngine.project(state, Seat.GUEST).toMatchView(), resync = true)))
            watchdog.onResumeSettled()
        }
    }

    /**
     * A deliberate host-side quit — there's no `HostToGuest.Leave` counterpart to
     * [GuestToHost.Leave]; `Abandoned(PEER_QUIT)` already exists and this is what slice 5 left it for.
     */
    override fun leave() {
        scope.launch {
            send(ProtocolCodec.encodeHostToGuest(HostToGuest.Abandoned(AbandonReason.PEER_QUIT)))
            transport.closeGracefully()
        }
    }

    override fun close() {
        transport.close()
    }

    private fun listen(t: Transport) {
        listenJob?.cancel()
        listenJob = scope.launch {
            t.incoming.collect { bytes ->
                // The socket is real and unauthenticated once this is a two-device match — a
                // corrupt frame or a build mismatch that slipped past the handshake must not crash
                // the host's coroutine, so a bad frame is dropped rather than decoded unguarded.
                watchdog.onInboundTraffic()
                val message = runCatching { ProtocolCodec.decodeGuestToHost(bytes) }.getOrNull() ?: return@collect
                when (message) {
                    is GuestToHost.ChooseMetric -> applyGuestIntent(message.seq, PlayerIntent.ChooseMetric(MetricKey(message.metricKey)))
                    is GuestToHost.AdvanceRound -> applyGuestIntent(message.seq, PlayerIntent.AdvanceRound)
                    // Handled during the pre-match handshake (MatchHandshake.kt) and never
                    // legitimately recur once a HostMatchSession exists.
                    is GuestToHost.Hello, is GuestToHost.DeckMismatch -> Unit
                    // Liveness tracking above already covers it — nothing further to do.
                    is GuestToHost.Heartbeat -> Unit
                    is GuestToHost.Leave -> if (message.deliberate) watchdog.onPeerLeft()
                    // A resume arrives on a *freshly dialled* transport, routed to [acceptResume]
                    // by the app layer's inbound-connection sniffing — never on one already being
                    // listened to here.
                    is GuestToHost.Resume -> Unit
                }
            }
        }
    }

    /**
     * Sequence-number dedup — the reconnect-resync ADR's whole reason to exist: `seq <= last` was
     * already applied before a drop and must not be re-applied; `seq == last + 1` is new. A gap
     * is unreachable given the guest's at-most-one-intent-in-flight invariant.
     */
    private fun applyGuestIntent(seq: Long, intent: PlayerIntent) {
        if (seq == lastAcceptedGuestSeq + 1) {
            lastAcceptedGuestSeq = seq
            applyIntent(Seat.GUEST, intent)
        }
    }

    private fun applyIntent(seat: Seat, intent: PlayerIntent) {
        when (val result = RulesEngine.apply(state, seat, intent)) {
            is StepResult.Applied -> {
                state = result.state
                _view.value = RulesEngine.project(state, Seat.HOST).toMatchView()
                scope.launch { pushGuestView() }
            }
            is StepResult.Rejected -> Unit // slice 1 has no error channel back to the sender yet.
        }
    }

    private suspend fun pushGuestView() {
        val guestView = RulesEngine.project(state, Seat.GUEST).toMatchView()
        send(ProtocolCodec.encodeHostToGuest(HostToGuest.View(guestView)))
    }

    private suspend fun send(bytes: ByteArray) {
        watchdog.onOutboundTraffic()
        transport.send(bytes)
    }
}

/**
 * The guest never runs [RulesEngine] — every [MatchView] it shows came from the host over the
 * wire. [sessionToken] is the value learned from the handshake's `HelloAck`, presented back in a
 * [GuestToHost.Resume] after a drop.
 */
public class GuestMatchSession(
    initialTransport: Transport,
    private val scope: CoroutineScope,
    private val sessionToken: String,
) : MatchSession {

    private var transport: Transport = initialTransport
    private var listenJob: Job? = null
    private var nextSeq: Long = 1

    /**
     * At most one in flight at a time (the reconnect-resync ADR's client invariant) — cleared
     * when the resolving `View`/`ResumeAck` arrives, and resent verbatim in a `Resume` if a
     * reconnect happens first.
     */
    private var pendingIntent: GuestToHost? = null
        set(value) {
            field = value
            _hasPendingIntent.value = value != null
        }

    private val _hasPendingIntent = MutableStateFlow(false)
    override val hasPendingIntent: StateFlow<Boolean> = _hasPendingIntent.asStateFlow()

    private val _view = MutableStateFlow<MatchView?>(null)
    override val view: StateFlow<MatchView?> = _view.asStateFlow()

    private val watchdog = ConnectionWatchdog(scope) { send(ProtocolCodec.encodeGuestToHost(GuestToHost.Heartbeat)) }
    override val connectionState: StateFlow<ConnectionState> get() = watchdog.state

    init {
        listen(transport)
        watchdog.start()
    }

    /**
     * Silently ignored while an intent is already in flight — the at-most-one invariant, enforced
     * here rather than trusted to the UI alone so it also covers `:core:ai`'s driver, which
     * happens to respect it today but shouldn't rely on that being an accident of its own logic.
     * The read-check-and-set of [pendingIntent] is routed through `scope.launch` rather than done
     * synchronously on the caller's thread (Compose's main thread calls this directly) — `scope`
     * is the *only* dispatcher [pendingIntent] and [nextSeq] are otherwise touched from (see
     * [listen]), and reading/writing them off that thread would be an unguarded data race.
     */
    override fun submit(intent: PlayerIntent) {
        scope.launch {
            if (pendingIntent != null) return@launch
            val seq = nextSeq++
            val message: GuestToHost = when (intent) {
                is PlayerIntent.ChooseMetric -> GuestToHost.ChooseMetric(intent.metric.id, seq)
                is PlayerIntent.AdvanceRound -> GuestToHost.AdvanceRound(seq)
            }
            pendingIntent = message
            send(ProtocolCodec.encodeGuestToHost(message))
        }
    }

    /**
     * Swaps in a freshly dialled [newTransport] after a drop and re-announces the session —
     * called by the app layer's reconnect coordinator (direct redial first, NSD fallback only if
     * that fails, per the foreground-service ADR).
     */
    public fun reconnect(newTransport: Transport) {
        val oldTransport = transport
        transport = newTransport
        listen(newTransport)
        // Presumed dead already (that's why a reconnect was needed) — a hygiene close, not a
        // graceful one, so its reader/writer coroutines don't leak forever on a half-open socket.
        oldTransport.close()
        scope.launch { send(ProtocolCodec.encodeGuestToHost(GuestToHost.Resume(sessionToken, pendingIntent))) }
    }

    override fun leave() {
        scope.launch {
            send(ProtocolCodec.encodeGuestToHost(GuestToHost.Leave(deliberate = true)))
            transport.closeGracefully()
        }
    }

    override fun close() {
        transport.close()
    }

    private fun listen(t: Transport) {
        listenJob?.cancel()
        listenJob = scope.launch {
            t.incoming.collect { bytes ->
                watchdog.onInboundTraffic()
                val message = runCatching { ProtocolCodec.decodeHostToGuest(bytes) }.getOrNull() ?: return@collect
                when (message) {
                    is HostToGuest.View -> {
                        _view.value = message.view
                        pendingIntent = null
                    }
                    // Already consumed during the pre-match handshake for a two-device guest
                    // (MatchHandshake.kt); solo's guest sees it here since it has no separate
                    // handshake phase, and it's a pure no-op — the deal animation it exists to
                    // trigger is slice 7's job.
                    is HostToGuest.MatchStart -> Unit
                    is HostToGuest.HelloAck, is HostToGuest.VersionMismatch, is HostToGuest.DeckChosen -> Unit
                    // Liveness tracking above already covers it — nothing further to do.
                    is HostToGuest.Heartbeat -> Unit
                    is HostToGuest.ResumeAck -> {
                        _view.value = message.view
                        pendingIntent = null
                        watchdog.onResumeSettled()
                    }
                    // The host didn't recognise our token — nothing left to resume into.
                    is HostToGuest.ResumeRejected -> watchdog.onPeerAbandoned(AbandonReason.GRACE_EXPIRED)
                    is HostToGuest.Abandoned -> when (message.reason) {
                        AbandonReason.PEER_QUIT -> watchdog.onPeerLeft()
                        AbandonReason.GRACE_EXPIRED -> watchdog.onPeerAbandoned(AbandonReason.GRACE_EXPIRED)
                    }
                }
            }
        }
    }

    private suspend fun send(bytes: ByteArray) {
        watchdog.onOutboundTraffic()
        transport.send(bytes)
    }
}

private fun Card.toRemote(): RemoteCardFace = RemoteCardFace(id, name, stats.toRemoteStats(), image.file)
