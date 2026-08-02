@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.toptrumps.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import coil3.ImageLoader
import com.toptrumps.ai.AiOpponentDriver
import com.toptrumps.decks.DeckLoader
import com.toptrumps.decks.DeckValidationResult
import com.toptrumps.feature.history.CardWin
import com.toptrumps.feature.history.HistoryDatabase
import com.toptrumps.feature.history.MatchHistoryRepository
import com.toptrumps.feature.history.Outcome
import com.toptrumps.platform.net.NsdLobbyDiscovery
import com.toptrumps.platform.net.NsdLobbyRegistration
import com.toptrumps.platform.net.WifiNetworkProvider
import com.toptrumps.rules.Deck
import com.toptrumps.rules.DeckTheme
import com.toptrumps.rules.MatchConfig
import com.toptrumps.rules.MatchResult
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
import com.toptrumps.session.RecordedMatch
import com.toptrumps.session.Role
import com.toptrumps.session.TcpTransport
import com.toptrumps.session.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** The opponent name recorded for a solo match — there's no real display name to decorate it with. */
private const val AI_OPPONENT_NAME = "AI"

/** The AI's own guest-side session needs an opponentName too, but nothing ever reads it — only the human's own (host) [MatchSession.completedMatch] is tracked into history. */
private const val UNREAD_SOLO_GUEST_OPPONENT_NAME = "You"

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

    /** `:feature:history`'s entire surface — `:app` is the only module that knows both it and `:core:session` exist (the collector-not-dependency seam, TDD §11). */
    public val historyRepository: MatchHistoryRepository = MatchHistoryRepository(HistoryDatabase.create(context))

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
     * The app's one Coil [ImageLoader] — every card image comes from local assets (TDD §7), so the
     * disk cache is disabled. Owned here rather than built per-screen: once the deck picker shows
     * images too, a second loader would mean a second decode pool and memory cache, and sharing
     * one screen's loader would leave that screen's own teardown shutting it down out from under
     * the other. Released in [close].
     */
    public val imageLoader: ImageLoader = ImageLoader.Builder(appContext).diskCache(null).build()

    /**
     * Resolved themes for every deck seen so far — [listDecks] runs on the caller's own thread
     * (a plain `remember {}` call from Compose's main thread), while [deckTheme] can also be
     * reached from a [MatchController]'s `matchScope` (a background dispatcher — see
     * `AppGraph.scope`), so this must tolerate concurrent access. `ConcurrentHashMap`, not a
     * `MutableMap` guarded by `getOrPut`: an invalid `deckId` simply isn't cached (see [deckTheme])
     * rather than needing a null-vs-absent sentinel, and every write here is idempotent — two
     * threads resolving the same valid deck concurrently always compute the same [DeckTheme].
     */
    private val deckThemeCache = ConcurrentHashMap<String, DeckTheme>()

    /**
     * Every deck folder under `/decks` that actually validates, at launch — the picker's entire
     * data source, per the PRD's "adding a deck later needs no new UI" requirement (see the
     * deck-storage ADR). A folder whose manifest fails validation is excluded rather than shown
     * broken; [DeckLoader] already refuses it loudly via its own validation errors. Populates
     * [deckThemeCache] from the same [Deck] load, rather than [deckTheme] re-loading it later.
     */
    public fun listDecks(): List<DeckSummary> = deckSource.listDecks().mapNotNull { id ->
        when (val result = DeckLoader.load(deckSource, id)) {
            is DeckValidationResult.Valid -> {
                deckThemeCache.computeIfAbsent(id) { resolveDeckTheme(result.deck) }
                DeckSummary(id, result.deck.name)
            }
            is DeckValidationResult.Invalid -> null
        }
    }

    /**
     * [deckId]'s resolved theme — colours plus a [DeckTheme.heroCardId] guaranteed to name an
     * actual card (see [resolveDeckTheme]). `null` only if [deckId] doesn't resolve to a valid
     * deck — that case is deliberately never cached, since it's the rare/degenerate path and
     * caching it would need a null-vs-absent sentinel for no real benefit. `MatchScreen`/the guest
     * path only ever have a `deckId: String`, never a loaded [Deck] (the guest never loads one
     * locally — TDD decision 6), so this is the only route to a theme from that call site. A
     * *valid* [deckId] already resolved — by [listDecks] or a prior call here — never re-invokes
     * [DeckLoader.load].
     */
    public fun deckTheme(deckId: String): DeckTheme? {
        deckThemeCache[deckId]?.let { return it }
        val deck = (DeckLoader.load(deckSource, deckId) as? DeckValidationResult.Valid)?.deck ?: return null
        return deckThemeCache.computeIfAbsent(deckId) { resolveDeckTheme(deck) }
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
        val host = HostMatchSession(deck, MatchConfig(deck.id), Random(System.nanoTime()), hostTransport, matchScope, soloSessionToken, AI_OPPONENT_NAME)
        val guest = GuestMatchSession(guestTransport, matchScope, soloSessionToken, UNREAD_SOLO_GUEST_OPPONENT_NAME)

        AiOpponentDriver(guest, seatName = "GUEST", deck = deck, scope = matchScope).start()
        trackHistory(host, deck.id, matchScope)

        return SoloMatchSession(host, matchScope)
    }

    /**
     * Subscribed on the graph's own long-lived [scope], not [matchScope] — a player who taps away
     * from the result screen the instant it appears calls `close()`/`leave()` on the match's own
     * scope almost immediately, and that must not race the DB write. But the same reasoning cuts
     * the other way for a match that's quit or abandoned rather than finished: nothing would ever
     * cancel this collector, and it would sit parked on the process-lifetime [scope] holding the
     * whole [session]/deck/transport graph forever. Racing against [matchScope]'s own completion —
     * which every real exit path (`close()`, `leave()`, a rematch, an Activity-scoped abandonment)
     * eventually triggers — bounds it either way.
     */
    private fun trackHistory(session: MatchSession, deckId: String, matchScope: CoroutineScope) {
        scope.launch {
            runCatching {
                val recorded = awaitCompletedOrScopeEnd(session, matchScope) ?: return@launch
                // A single deck's manifest, not the full listDecks() catalog walk — and off the
                // single-worker dispatcher every live MatchSession mutates its state on.
                val deckName = withContext(Dispatchers.IO) {
                    when (val result = DeckLoader.load(deckSource, deckId)) {
                        is DeckValidationResult.Valid -> result.deck.name
                        is DeckValidationResult.Invalid -> deckId
                    }
                }
                historyRepository.recordMatch(
                    timestampEpochMillis = recorded.timestamp.toEpochMilliseconds(),
                    deckId = deckId,
                    deckName = deckName,
                    opponentName = recorded.opponentName,
                    outcome = recorded.summary.result.toOutcome(),
                    myScore = recorded.summary.myScore,
                    opponentScore = recorded.summary.opponentScore,
                    cardsWon = recorded.summary.cardsWon.map { CardWin(it.id, it.name) },
                )
                // History is the deliberately droppable slice (the WBS): a failed write here must
                // never take the app down with it — only ever log, and only in debug.
            }.onFailure { if (BuildConfig.DEBUG) it.printStackTrace() }
        }
    }

    /** `null` if [matchScope] ends first (the match was quit, abandoned, or otherwise torn down without ever finishing) — see [trackHistory]. */
    private suspend fun awaitCompletedOrScopeEnd(session: MatchSession, matchScope: CoroutineScope): RecordedMatch? {
        val matchJob = matchScope.coroutineContext[Job] ?: return session.completedMatch.filterNotNull().first()
        return coroutineScope {
            val completed = async { session.completedMatch.filterNotNull().first() }
            val scopeEnded = async { matchJob.join() }
            val result = select<RecordedMatch?> {
                completed.onAwait { it }
                scopeEnded.onAwait { null }
            }
            completed.cancel()
            scopeEnded.cancel()
            result
        }
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
            peerDisplayName = peerDisplayName,
            reconnect = if (role == Role.GUEST) {
                { redialGuestTransport(peerInstanceId, hostAddressHint, lobbyController, matchScope) }
            } else {
                null
            },
            onMatchStarted = ::trackHistory,
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
        imageLoader.shutdown()
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
    override val completedMatch: StateFlow<RecordedMatch?> get() = host.completedMatch
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

/** `:core:rules`' vocabulary translated into `:feature:history`'s own — the one place both are ever imported together. */
private fun MatchResult.toOutcome(): Outcome = when (this) {
    MatchResult.WIN -> Outcome.WIN
    MatchResult.LOSS -> Outcome.LOSS
    MatchResult.DRAW -> Outcome.DRAW
}
