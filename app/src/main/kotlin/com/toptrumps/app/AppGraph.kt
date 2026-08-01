@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.toptrumps.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import com.toptrumps.ai.AiOpponentDriver
import com.toptrumps.decks.DeckLoader
import com.toptrumps.decks.DeckValidationResult
import com.toptrumps.platform.net.NsdLobbyDiscovery
import com.toptrumps.platform.net.NsdLobbyRegistration
import com.toptrumps.platform.net.WifiNetworkProvider
import com.toptrumps.rules.MatchConfig
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.session.ConnectionState
import com.toptrumps.session.GuestMatchSession
import com.toptrumps.session.HostMatchSession
import com.toptrumps.session.HostToGuest
import com.toptrumps.session.LOBBY_PORT
import com.toptrumps.session.LoopbackTransport
import com.toptrumps.session.MatchSession
import com.toptrumps.session.MatchView
import com.toptrumps.session.ProtocolCodec
import com.toptrumps.session.Role
import com.toptrumps.session.TcpTransport
import com.toptrumps.session.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.random.Random

/** A deck folder available in the picker — id is the asset folder name, name is its manifest label. */
public data class DeckSummary(val id: String, val name: String)

/**
 * A hand-written dependency graph — no DI framework, per the module-structure ADR. Solo mode is
 * two real [MatchSession]s wired over a [LoopbackTransport]: the human plays the host, an
 * [AiOpponentDriver] plays the guest through the exact same session API a remote human would use.
 */
public class AppGraph(context: Context) {

    private val appContext = context.applicationContext
    private val deckSource = AndroidAssetDeckSource(context.assets)

    public val displayNamePreferences: DisplayNamePreferences = DisplayNamePreferences(context)

    /** A per-launch instance id, encoded into the mDNS instance name for resolve-free self-filtering — see the instance-name ADR. */
    private val instanceId: String = UUID.randomUUID().toString().replace("-", "").take(8)

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val nsdDiscovery = NsdLobbyDiscovery(nsdManager)
    private val wifiNetworkProvider =
        WifiNetworkProvider(context.getSystemService(ConnectivityManager::class.java))

    // Confined to one worker: HostMatchSession mutates its authoritative state from both the
    // human's `submit()` call and the AI's guest-intent traffic, and the TDD requires that
    // mutation be single-dispatcher-confined rather than guarded some other way.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    // Constructed here, in the constructor [TopTrumpsApplication.onCreate] runs — "preloaded at
    // app start" (WBS slice-7-polish), not lazily on the first match screen visit.
    public val soundPreferences: SoundPreferences = SoundPreferences(appContext, scope)
    public val soundEffects: SoundEffects = SoundEffects(appContext, soundPreferences.muted)

    /**
     * Every deck folder under `/decks` that actually validates, at launch — the picker's entire
     * data source, per the PRD's "adding a deck later needs no new UI" requirement (see the
     * deck-storage ADR). A folder whose manifest fails validation is excluded rather than shown
     * broken; [DeckLoader] already refuses it loudly via its own validation errors.
     */
    public fun listDecks(): List<DeckSummary> = deckSource.listDecks().mapNotNull { id ->
        when (val result = DeckLoader.load(deckSource, id)) {
            is DeckValidationResult.Valid -> DeckSummary(id, result.deck.name)
            is DeckValidationResult.Invalid -> null
        }
    }

    /**
     * The human's session for a fresh solo match against the chosen deck. Callable again for a
     * rematch — the returned [MatchSession.close] tears down everything this match started,
     * including the guest side and its [AiOpponentDriver] collector, which otherwise outlive the
     * human's own session (see slice 2's code review: closing only the host's transport left the
     * previous match's guest session and AI coroutine parked forever on the shared graph scope).
     */
    public fun startSoloMatch(deckId: String): MatchSession {
        val deck = DeckLoader.loadOrThrow(deckSource, deckId)

        // A child of `scope` rather than `scope` itself: every coroutine this match starts (both
        // sessions' collectors, the AI's) is cancelled together when the match ends, without
        // touching the next match's — and without leaving `scope` itself cancellable more than
        // once across the graph's lifetime.
        val matchScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        // Solo mode has no real handshake to mint one, and never resumes — LoopbackTransport
        // never drops — so a throwaway token is fine here.
        val soloSessionToken = UUID.randomUUID().toString()
        val host = HostMatchSession(deck, MatchConfig(deck.id), Random(System.nanoTime()), hostTransport, matchScope, soloSessionToken)
        val guest = GuestMatchSession(guestTransport, matchScope, soloSessionToken)

        AiOpponentDriver(guest, seatName = "GUEST", deck = deck, scope = matchScope).start()

        return SoloMatchSession(host, matchScope)
    }

    // The one two-device match in progress, if any — mirrors the "at most one at a time" pattern
    // already implicit in LobbyController's own invitation state. Tracked here (rather than left
    // purely Composable-scoped) so the foreground service and a reconnecting guest's inbound
    // resume — both of which can arrive with no UI currently observing them — have something to
    // find. See the foreground-service and reconnect-resync ADRs.
    private val _activeMatch = MutableStateFlow<MatchController?>(null)
    public val activeMatch: StateFlow<MatchController?> = _activeMatch.asStateFlow()

    private val _activeMatchPeerName = MutableStateFlow<String?>(null)
    public val activeMatchPeerName: StateFlow<String?> = _activeMatchPeerName.asStateFlow()

    /**
     * A fresh [MatchController] for one visit to the connected screen — call [clearActiveMatch]
     * on leaving it. [transport] is the same connected socket [LobbyController] handed over on
     * [com.toptrumps.session.InvitationState.Connected]; this graph never dials or accepts one
     * itself, except for a guest's reconnect redial. [lobbyController] is the caller's own —
     * still needed after the handoff for its live Wi-Fi socket factory (see [redialGuestTransport]).
     */
    public fun createMatchController(
        transport: Transport,
        role: Role,
        displayName: String,
        peerInstanceId: String,
        peerDisplayName: String,
        lobbyController: LobbyController,
    ): MatchController {
        val matchScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        // The guest never dialled this connection — the host always did, even in the mutual-tap
        // tiebreak case (see the reconnect-resync ADR) — so the only address a guest has for a
        // direct redial is the one its already-accepted socket reports.
        val hostAddressHint = (transport as? TcpTransport)?.remoteHost
        val controller = MatchController(
            transport = transport,
            role = role,
            displayName = displayName,
            instanceId = instanceId,
            deckSource = deckSource,
            listDecks = ::listDecks,
            scope = matchScope,
            reconnect = if (role == Role.GUEST) {
                { redialGuestTransport(peerInstanceId, hostAddressHint, lobbyController, matchScope) }
            } else {
                null
            },
        )
        _activeMatch.value = controller
        _activeMatchPeerName.value = peerDisplayName
        appContext.startForegroundService(Intent(appContext, MatchForegroundService::class.java))
        return controller
    }

    /** Call once the connected screen is left — stops the foreground service and forgets this match, so a late resume attempt is correctly told UNKNOWN_SESSION instead of finding a stale controller. */
    public fun clearActiveMatch(controller: MatchController) {
        if (_activeMatch.value !== controller) return // superseded by a newer match already
        _activeMatch.value = null
        _activeMatchPeerName.value = null
        appContext.stopService(Intent(appContext, MatchForegroundService::class.java))
    }

    /** The live [HostMatchSession] for the current match, if we're hosting one — [LobbyController]'s inbound-resume sniffing hands a reconnecting guest's frame here. */
    public fun currentHostSession(): HostMatchSession? =
        (_activeMatch.value?.phase?.value as? MatchPhase.InMatch)?.session as? HostMatchSession

    /** Direct redial first (no NSD re-resolve), NSD resolve-and-dial only if that fails — per the foreground-service ADR. */
    private suspend fun redialGuestTransport(
        peerInstanceId: String,
        hostAddressHint: String?,
        lobbyController: LobbyController,
        matchScope: CoroutineScope,
    ): Transport {
        val factory = lobbyController.currentSocketFactory() ?: error("no Wi-Fi network available to redial on")
        if (hostAddressHint != null) {
            val direct = runCatching { TcpTransport.connect(factory, hostAddressHint, LOBBY_PORT, matchScope, Dispatchers.IO) }
            if (direct.isSuccess) return direct.getOrThrow()
        }
        val resolved = nsdDiscovery.resolve(peerInstanceId)
        return TcpTransport.connect(factory, resolved.host, resolved.port, matchScope, Dispatchers.IO)
    }

    private var lobbyController: LobbyController? = null
    private var lobbyControllerDisplayName: String? = null

    /**
     * The one [LobbyController] for the process's life, not a fresh one per visit — it owns the
     * live socket for whatever match is in progress, and that socket (like [activeMatch] itself)
     * must survive an Activity recreation, not just [AppGraph] doing so nominally. Reused as long
     * as [displayName] hasn't changed (a rename re-registers under the new name); [LobbyController.start]
     * is itself idempotent, so calling this again after a recreation is safe.
     */
    public fun lobbyController(displayName: String): LobbyController {
        lobbyController?.let { existing ->
            if (lobbyControllerDisplayName == displayName) return existing
        }
        val fresh = LobbyController(
            displayName = displayName,
            instanceId = instanceId,
            discovery = nsdDiscovery,
            registration = NsdLobbyRegistration(nsdManager),
            wifiNetworkProvider = wifiNetworkProvider,
            parentScope = scope,
            onResumeAttempt = { transport, resume ->
                val hostSession = currentHostSession()
                if (hostSession != null) {
                    hostSession.acceptResume(transport, resume)
                } else {
                    // No live match to resume into (already left, or this session was never the
                    // host) — tell the guest so, rather than leaving it to time out its own countdown.
                    transport.send(ProtocolCodec.encodeHostToGuest(HostToGuest.ResumeRejected("UNKNOWN_SESSION")))
                    transport.close()
                }
            },
        )
        lobbyController = fresh
        lobbyControllerDisplayName = displayName
        return fresh
    }

    /** Cancels every coroutine this graph started. Call from the owning component's teardown. */
    public fun close() {
        soundEffects.release()
        scope.cancel()
    }
}

/** Closing a solo match must tear down its guest side and AI driver too, not just the host's. */
private class SoloMatchSession(
    private val host: MatchSession,
    private val matchScope: CoroutineScope,
) : MatchSession {
    override val view: StateFlow<MatchView?> get() = host.view
    override val connectionState: StateFlow<ConnectionState> get() = host.connectionState
    override val hasPendingIntent: StateFlow<Boolean> get() = host.hasPendingIntent
    override val lastResync: StateFlow<Long?> get() = host.lastResync
    override fun submit(intent: PlayerIntent): Unit = host.submit(intent)

    override fun leave() {
        host.leave()
        matchScope.cancel()
    }

    override fun close() {
        host.close()
        matchScope.cancel()
    }
}
