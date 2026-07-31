@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.toptrumps.app

import android.content.res.AssetManager
import com.toptrumps.ai.AiOpponentDriver
import com.toptrumps.decks.DeckLoader
import com.toptrumps.decks.DeckValidationResult
import com.toptrumps.rules.MatchConfig
import com.toptrumps.session.GuestMatchSession
import com.toptrumps.session.HostMatchSession
import com.toptrumps.session.LoopbackTransport
import com.toptrumps.session.MatchSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.random.Random

/**
 * A hand-written dependency graph — no DI framework, per the module-structure ADR. Solo mode is
 * two real [MatchSession]s wired over a [LoopbackTransport]: the human plays the host, an
 * [AiOpponentDriver] plays the guest through the exact same session API a remote human would use.
 */
public class AppGraph(assets: AssetManager) {

    private val deckSource = AndroidAssetDeckSource(assets)

    // Confined to one worker: HostMatchSession mutates its authoritative state from both the
    // human's `submit()` call and the AI's guest-intent traffic, and the TDD requires that
    // mutation be single-dispatcher-confined rather than guarded some other way.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /** The human's session for a fresh solo round. Slice 1 has exactly one deck and one round. */
    public fun startSoloMatch(): MatchSession {
        val deck = when (val result = DeckLoader.load(deckSource, "test-deck")) {
            is DeckValidationResult.Valid -> result.deck
            is DeckValidationResult.Invalid -> error("test-deck failed validation: ${result.errors}")
        }

        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(deck, MatchConfig(deck.id), Random(System.nanoTime()), hostTransport, scope)
        val guest = GuestMatchSession(guestTransport, scope)

        AiOpponentDriver(guest, seatName = "GUEST", scope = scope).start()

        return host
    }

    /** Cancels every coroutine this graph started. Call from the owning component's teardown. */
    public fun close() {
        scope.cancel()
    }
}
