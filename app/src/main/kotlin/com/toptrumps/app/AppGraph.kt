@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.toptrumps.app

import android.content.Context
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
import com.toptrumps.session.GuestMatchSession
import com.toptrumps.session.HostMatchSession
import com.toptrumps.session.LoopbackTransport
import com.toptrumps.session.MatchSession
import com.toptrumps.session.MatchView
import com.toptrumps.session.Role
import com.toptrumps.session.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
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
        val host = HostMatchSession(deck, MatchConfig(deck.id), Random(System.nanoTime()), hostTransport, matchScope)
        val guest = GuestMatchSession(guestTransport, matchScope)

        AiOpponentDriver(guest, seatName = "GUEST", deck = deck, scope = matchScope).start()

        return SoloMatchSession(host, matchScope)
    }

    /**
     * A fresh [MatchController] for one visit to the connected screen — call [MatchController.close]
     * on leaving it. [transport] is the same connected socket [LobbyController] handed over on
     * [com.toptrumps.session.InvitationState.Connected]; this graph never dials or accepts one itself.
     */
    public fun createMatchController(transport: Transport, role: Role, displayName: String): MatchController {
        val matchScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        return MatchController(
            transport = transport,
            role = role,
            displayName = displayName,
            instanceId = instanceId,
            deckSource = deckSource,
            listDecks = ::listDecks,
            scope = matchScope,
        )
    }

    /** A fresh [LobbyController] for one visit to the lobby screen — call [LobbyController.close] on leaving it. */
    public fun createLobbyController(displayName: String): LobbyController = LobbyController(
        displayName = displayName,
        instanceId = instanceId,
        discovery = nsdDiscovery,
        registration = NsdLobbyRegistration(nsdManager),
        wifiNetworkProvider = wifiNetworkProvider,
        parentScope = scope,
    )

    /** Cancels every coroutine this graph started. Call from the owning component's teardown. */
    public fun close() {
        scope.cancel()
    }
}

/** Closing a solo match must tear down its guest side and AI driver too, not just the host's. */
private class SoloMatchSession(
    private val host: MatchSession,
    private val matchScope: CoroutineScope,
) : MatchSession {
    override val view: StateFlow<MatchView?> get() = host.view
    override fun submit(intent: PlayerIntent): Unit = host.submit(intent)

    override fun close() {
        host.close()
        matchScope.cancel()
    }
}
