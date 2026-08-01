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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlin.random.Random

/**
 * A player's side of one match. No hardcoded `Dispatchers.*`: every implementation runs its
 * coroutines on the [CoroutineScope] its caller supplies, which is what makes slice 6's
 * virtual-time tests possible later without a signature change now.
 */
public interface MatchSession {
    /** `null` only for the guest before its first view has arrived. */
    public val view: StateFlow<MatchView?>
    public fun submit(intent: PlayerIntent)
    public fun close()
}

/**
 * Runs [RulesEngine] against the authoritative [MatchState] and pushes a [MatchView] to the
 * guest after every transition. The host's own UI is served by [RulesEngine.project] directly —
 * it never sees [MatchState] either, per the structural-redaction ADR.
 */
public class HostMatchSession(
    deck: Deck,
    config: MatchConfig,
    random: Random,
    private val transport: Transport,
    private val scope: CoroutineScope,
) : MatchSession {

    private var state: MatchState = RulesEngine.deal(deck, config, random)

    private val _view = MutableStateFlow(RulesEngine.project(state, Seat.HOST).toMatchView())
    override val view: StateFlow<MatchView?> = _view.asStateFlow()

    init {
        scope.launch {
            // Discrete and first, ahead of any View — the guest's unambiguous "you have been
            // dealt in" trigger (TDD §5), sent before the round's actual first View.
            val guestHand = state.hands.getValue(Seat.GUEST).map { it.toRemote() }
            transport.send(ProtocolCodec.encodeHostToGuest(HostToGuest.MatchStart(guestHand, state.totalRounds)))
            pushGuestView()
        }
        scope.launch {
            transport.incoming.collect { bytes ->
                // The socket is real and unauthenticated once this is a two-device match — a
                // corrupt frame or a build mismatch that slipped past the handshake must not crash
                // the host's coroutine, so a bad frame is dropped rather than decoded unguarded.
                val message = runCatching { ProtocolCodec.decodeGuestToHost(bytes) }.getOrNull() ?: return@collect
                when (message) {
                    is GuestToHost.ChooseMetric -> applyIntent(Seat.GUEST, PlayerIntent.ChooseMetric(MetricKey(message.metricKey)))
                    is GuestToHost.AdvanceRound -> applyIntent(Seat.GUEST, PlayerIntent.AdvanceRound)
                    // Handled during the pre-match handshake (MatchHandshake.kt) and never
                    // legitimately recur once a HostMatchSession exists.
                    is GuestToHost.Hello, is GuestToHost.DeckMismatch -> Unit
                    // Slice 6 surfaces this to the host's own UI once the abandon/countdown story lands.
                    is GuestToHost.Leave -> Unit
                }
            }
        }
    }

    override fun submit(intent: PlayerIntent) {
        // Routed through `scope`, same as the guest-intent path below, rather than mutating
        // `state` synchronously on the caller's thread — the TDD requires all state mutation
        // confined to a single dispatcher. It's the caller's job to supply a confined `scope`
        // (:core:session cannot pin one itself without a hardcoded `Dispatchers.*` literal).
        scope.launch { applyIntent(Seat.HOST, intent) }
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
        transport.send(ProtocolCodec.encodeHostToGuest(HostToGuest.View(guestView)))
    }

    override fun close() {
        transport.close()
    }
}

/** The guest never runs [RulesEngine] — every [MatchView] it shows came from the host over the wire. */
public class GuestMatchSession(
    private val transport: Transport,
    private val scope: CoroutineScope,
) : MatchSession {

    private val _view = MutableStateFlow<MatchView?>(null)
    override val view: StateFlow<MatchView?> = _view.asStateFlow()

    init {
        scope.launch {
            transport.incoming.collect { bytes ->
                val message = runCatching { ProtocolCodec.decodeHostToGuest(bytes) }.getOrNull() ?: return@collect
                when (message) {
                    is HostToGuest.View -> _view.value = message.view
                    // Already consumed during the pre-match handshake for a two-device guest
                    // (MatchHandshake.kt); solo's guest sees it here since it has no separate
                    // handshake phase, and it's a pure no-op — the deal animation it exists to
                    // trigger is slice 7's job.
                    is HostToGuest.MatchStart -> Unit
                    is HostToGuest.HelloAck, is HostToGuest.VersionMismatch, is HostToGuest.DeckChosen -> Unit
                    // Slice 6 surfaces this to the guest's own UI once the abandon/countdown story lands.
                    is HostToGuest.Abandoned -> Unit
                }
            }
        }
    }

    override fun submit(intent: PlayerIntent) {
        val message: GuestToHost = when (intent) {
            is PlayerIntent.ChooseMetric -> GuestToHost.ChooseMetric(intent.metric.id)
            is PlayerIntent.AdvanceRound -> GuestToHost.AdvanceRound
        }
        scope.launch { transport.send(ProtocolCodec.encodeGuestToHost(message)) }
    }

    override fun close() {
        transport.close()
    }
}

private fun Card.toRemote(): RemoteCardFace = RemoteCardFace(id, name, stats.toRemoteStats(), image.file)
