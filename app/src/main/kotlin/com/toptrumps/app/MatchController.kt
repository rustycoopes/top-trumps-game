package com.toptrumps.app

import android.util.Log
import com.toptrumps.decks.DeckLoader
import com.toptrumps.decks.DeckSource
import com.toptrumps.rules.MatchConfig
import com.toptrumps.session.ConnectionState
import com.toptrumps.session.GuestHandshake
import com.toptrumps.session.GuestMatchSession
import com.toptrumps.session.HostHandshake
import com.toptrumps.session.HostMatchSession
import com.toptrumps.session.MatchSession
import com.toptrumps.session.Role
import com.toptrumps.session.Transport
import com.toptrumps.session.toWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/** Where a two-device match's setup currently stands — drives [TwoDeviceMatchScreen]. */
public sealed interface MatchPhase {
    public data object Handshaking : MatchPhase
    public data class VersionMismatch(val peerVersion: Int) : MatchPhase
    public data class PickingDeck(val decks: List<DeckSummary>) : MatchPhase
    public data object WaitingForHost : MatchPhase
    public data class DeckMismatch(val deckId: String) : MatchPhase
    public data class InMatch(val session: MatchSession, val deckId: String, val localSeat: String) : MatchPhase

    /** The peer vanished mid-handshake (backgrounded, crashed, walked out of Wi-Fi range) — the same recoverable-elsewhere gap slice 6's heartbeat/reconnect story closes for good; for now this is a clean dead end rather than a crash. */
    public data object ConnectionLost : MatchPhase

    /** Nothing recognizable arrived within [HostHandshake.DEFAULT_HELLO_TIMEOUT] at one of the handshake's automatic exchanges — a distinct dead end from [ConnectionLost] (the transport is still open; the peer just never sent anything decodable) so the two don't get confused when triaging a stuck pairing. */
    public data object HandshakeTimedOut : MatchPhase
}

/**
 * Drives the Hello/DeckChosen handshake (TDD §5's two gates) over an already-connected
 * [Transport] — the one [LobbyController] hands off once an invitation resolves to
 * [com.toptrumps.session.InvitationState.Connected] — then hands off to an ordinary
 * [HostMatchSession]/[GuestMatchSession], exactly the classes solo mode already uses, so the
 * in-match wire behaviour is identical either way. One instance per visit to the connected
 * screen; [close] tears it down, matching [AppGraph]'s other per-visit controllers.
 */
public class MatchController(
    private val transport: Transport,
    private val role: Role,
    private val displayName: String,
    private val instanceId: String,
    private val deckSource: DeckSource,
    private val listDecks: () -> List<DeckSummary>,
    private val scope: CoroutineScope,
    /** The peer's display name, already known from NSD discovery before this controller ever ran — decorates the [MatchSession] this creates so its [MatchSession.completedMatch] carries who was actually played (TDD §11). */
    private val peerDisplayName: String,
    /** Guest-only: dials a fresh [Transport] to the host, direct-redial first and NSD fallback second — see [AppGraph]. `null` for the host side, which has nothing to dial out to. */
    private val reconnect: (suspend () -> Transport)? = null,
    /**
     * Fires once, the moment [MatchPhase.InMatch] is reached — [AppGraph] hangs its history
     * collector off this rather than this class knowing anything about `:feature:history`. Passed
     * this controller's own [scope] (its match's lifetime) so that collector can bound itself to
     * it rather than outliving a quit or abandoned match forever.
     */
    private val onMatchStarted: (MatchSession, deckId: String, matchScope: CoroutineScope) -> Unit = { _, _, _ -> },
) {
    private val _phase = MutableStateFlow<MatchPhase>(MatchPhase.Handshaking)
    public val phase: StateFlow<MatchPhase> = _phase.asStateFlow()

    private var started = false
    private var pickingDeck = false
    private var sessionToken: String? = null

    /**
     * Idempotent — only the first call does anything. This matters beyond tidiness: a second,
     * concurrent [runHost]/[runGuest] would race the first for messages off the same
     * [transport.incoming] channel, and whichever `.first { }` loses would hang forever waiting
     * for a message the other coroutine already consumed.
     */
    public fun start() {
        if (started) return
        started = true
        scope.launch {
            runOrConnectionLost {
                when (role) {
                    Role.HOST -> runHost()
                    Role.GUEST -> runGuest()
                }
            }
        }
    }

    private suspend fun runHost() {
        Log.d("TTHandshake", "runHost: awaiting Hello on transport=${transport.hashCode()}")
        val started = System.currentTimeMillis()
        when (val result = HostHandshake.awaitHello(transport)) {
            is HostHandshake.HelloResult.Refused -> {
                Log.d("TTHandshake", "runHost: Refused after ${System.currentTimeMillis() - started}ms (guestVersion=${result.guestVersion})")
                _phase.value = MatchPhase.VersionMismatch(result.guestVersion)
            }
            HostHandshake.HelloResult.TimedOut -> {
                Log.d("TTHandshake", "runHost: TimedOut after ${System.currentTimeMillis() - started}ms")
                _phase.value = MatchPhase.HandshakeTimedOut
            }
            is HostHandshake.HelloResult.Ready -> {
                Log.d("TTHandshake", "runHost: Ready after ${System.currentTimeMillis() - started}ms")
                sessionToken = result.sessionToken
                _phase.value = MatchPhase.PickingDeck(listDecks())
            }
        }
    }

    /**
     * Player One's pick (story 18) — validated and hashed locally, then offered to the guest
     * before any dealing. Guarded by [pickingDeck] rather than re-derived from [_phase], since a
     * double-tap during the handshake's deck-confirm window would otherwise start a second
     * concurrent [HostHandshake.chooseDeck] racing the first for the same [transport.incoming]
     * channel — the same single-consumer hazard [start] guards against.
     */
    public fun pickDeck(deckId: String) {
        if (pickingDeck) return
        pickingDeck = true
        scope.launch {
            runOrConnectionLost {
                val deck = DeckLoader.loadOrThrow(deckSource, deckId)
                val hash = DeckLoader.manifestHash(deckSource, deckId) ?: error("deck '$deckId' manifest unreadable")
                val config = MatchConfig(deckId)

                when (HostHandshake.chooseDeck(transport, deckId, hash, config.toWire())) {
                    HostHandshake.DeckResult.Refused -> _phase.value = MatchPhase.DeckMismatch(deckId)
                    HostHandshake.DeckResult.Accepted -> {
                        val token = checkNotNull(sessionToken) { "chooseDeck accepted before awaitHello issued a token" }
                        val session = HostMatchSession(deck, config, Random(System.nanoTime()), transport, scope, token, peerDisplayName)
                        _phase.value = MatchPhase.InMatch(session, deckId, role.name)
                        onMatchStarted(session, deckId, scope)
                    }
                }
            }
        }
    }

    private suspend fun runGuest() {
        _phase.value = MatchPhase.WaitingForHost
        Log.d("TTHandshake", "runGuest: sending Hello on transport=${transport.hashCode()}")
        val started = System.currentTimeMillis()
        val result = GuestHandshake.run(
            transport,
            displayName,
            instanceId,
            localDeckHash = { deckId -> DeckLoader.manifestHash(deckSource, deckId) },
        )
        Log.d("TTHandshake", "runGuest: result=${result::class.simpleName} after ${System.currentTimeMillis() - started}ms")
        when (result) {
            is GuestHandshake.Result.VersionRefused -> _phase.value = MatchPhase.VersionMismatch(result.hostVersion)
            is GuestHandshake.Result.DeckRefused -> _phase.value = MatchPhase.DeckMismatch(result.deckId)
            GuestHandshake.Result.TimedOut -> _phase.value = MatchPhase.HandshakeTimedOut
            is GuestHandshake.Result.Ready -> {
                val session = GuestMatchSession(transport, scope, result.sessionToken, peerDisplayName)
                watchConnection(session)
                _phase.value = MatchPhase.InMatch(session, result.deckId, role.name)
                onMatchStarted(session, result.deckId, scope)
            }
        }
    }

    /**
     * While the peer is unreachable, retries [reconnect] every 1.5s until it succeeds or the
     * session gives up waiting (declares [ConnectionState.Abandoned]) or the peer reconnects on
     * its own (a same-socket blip, [ConnectionState.Connected] with no resume needed) — either
     * way [collectLatest] cancels the retry loop the moment [GuestMatchSession.connectionState]
     * moves off [ConnectionState.PeerUnreachable].
     */
    private fun watchConnection(session: GuestMatchSession) {
        val redial = reconnect ?: return
        scope.launch {
            session.connectionState.collectLatest { state ->
                if (state !is ConnectionState.PeerUnreachable) return@collectLatest
                while (true) {
                    val newTransport = runCatching { redial() }.getOrNull()
                    if (newTransport != null) {
                        session.reconnect(newTransport)
                        return@collectLatest
                    }
                    delay(1500.milliseconds)
                }
            }
        }
    }

    /**
     * The handshake reads from a real socket that the peer can drop at any point — a `Flow.first`
     * with nothing left to find throws `NoSuchElementException` once the channel closes, and a
     * malformed frame throws from the decoder — neither of which slice 6's heartbeat/reconnect
     * story exists yet to recover from. Catch anything but cancellation here so the surviving
     * device gets a clean dead end instead of a crash.
     */
    private suspend fun runOrConnectionLost(block: suspend () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.d("TTHandshake", "runOrConnectionLost: caught ${t::class.simpleName}: ${t.message}")
            _phase.value = MatchPhase.ConnectionLost
        }
    }

    /** Cancels every coroutine this controller started. Does *not* close [transport] — [LobbyController.leave] owns that, since the same transport predates and outlives any given match attempt on it. */
    public fun close() {
        scope.cancel()
    }
}
