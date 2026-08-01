@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.toptrumps.session

import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.Direction
import com.toptrumps.rules.MatchConfig
import com.toptrumps.rules.MatchResult
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.MetricSpec
import com.toptrumps.rules.StatValue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val TOKEN = "completed-match-test-token"
private val speed = MetricKey("speed")
private val FIXED_INSTANT = Instant.parse("2026-08-01T12:00:00Z")

/** Proves [HostMatchSession]/[GuestMatchSession] actually call the injected [Clock] rather than [Clock.System] — the whole point of taking one as a constructor param. */
private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** Six distinct-speed cards (three rounds) so every round resolves on the first pick — this file tests [MatchSession.completedMatch], not tiebreak walking. */
private fun sixCardDeck(): Deck = Deck(
    id = "completed-match-deck",
    metrics = listOf(MetricSpec(speed, "Speed", "mph", Direction.HIGH_WINS)),
    cards = listOf(
        Card("a", "Alpha", mapOf(speed to StatValue(120.0))),
        Card("b", "Bravo", mapOf(speed to StatValue(150.0))),
        Card("c", "Charlie", mapOf(speed to StatValue(95.0))),
        Card("d", "Delta", mapOf(speed to StatValue(175.0))),
        Card("e", "Echo", mapOf(speed to StatValue(200.0))),
        Card("f", "Foxtrot", mapOf(speed to StatValue(110.0))),
    ),
)

/**
 * [MatchSession.completedMatch] is the collector-not-dependency seam the match-history slice is
 * built on — these tests are the "abandoned or quit match is not recorded" acceptance criterion,
 * proven at the one seam that actually decides it (`:feature:history` is never involved: it's
 * simply never called when this stays `null`).
 */
class CompletedMatchTest {

    @Test
    fun `completedMatch fires exactly once, on both sides, with the opponent name and correct per-seat result`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(sixCardDeck(), MatchConfig("completed-match-deck"), Random(3), hostTransport, backgroundScope, TOKEN, "Bo", FixedClock(FIXED_INSTANT))
            val guest = GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Alex", FixedClock(FIXED_INSTANT))

            assertNull(host.completedMatch.value)
            assertNull(guest.completedMatch.value)

            val driver = MatchDriver(host, guest)
            driver.playMatch { view, _ -> view.metrics.first().key } // sixCardDeck has one metric, every card's value distinct.

            val hostRecorded = checkNotNull(host.completedMatch.value) { "host should have recorded a completed match" }
            val guestRecorded = checkNotNull(guest.completedMatch.value) { "guest should have recorded a completed match" }

            assertEquals("Bo", hostRecorded.opponentName, "the host's recorded opponent is the guest's name")
            assertEquals("Alex", guestRecorded.opponentName, "the guest's recorded opponent is the host's name")
            assertEquals(FIXED_INSTANT, hostRecorded.timestamp, "the injected clock, not Clock.System, must be what stamps the record")
            assertEquals(FIXED_INSTANT, guestRecorded.timestamp)

            // An odd round count under the default CHOOSER_WINS config never draws — one side won.
            assertEquals(hostRecorded.summary.myScore, guestRecorded.summary.opponentScore)
            assertEquals(hostRecorded.summary.opponentScore, guestRecorded.summary.myScore)
            val winner = if (hostRecorded.summary.result == MatchResult.WIN) hostRecorded else guestRecorded
            val loser = if (winner === hostRecorded) guestRecorded else hostRecorded
            assertEquals(MatchResult.WIN, winner.summary.result)
            assertEquals(MatchResult.LOSS, loser.summary.result)
            // Every one of the three rounds has a winner (CHOOSER_WINS default, all-distinct
            // speeds — no ties), and cardsWon credits exactly one card per round won, never the
            // captured opposing card — see MatchSummary's doc.
            assertEquals(3, winner.summary.cardsWon.size + loser.summary.cardsWon.size)
            assertTrue(winner.summary.cardsWon.isNotEmpty())
            assertTrue(winner.summary.cardsWon.size < winner.summary.myScore, "cardsWon must be strictly fewer than the final pile once any round is won (the pile also holds the captured card)")

            host.close()
            guest.close()
        }

    @Test
    fun `a deliberate quit partway through a match never populates completedMatch on either side`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(sixCardDeck(), MatchConfig("completed-match-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Bo")
        val guest = GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Alex")
        val driver = MatchDriver(host, guest)

        driver.playRound { view, _ -> view.metrics.first().key } // one of three rounds settled, match still in progress
        assertTrue(host.view.value is MatchView.InProgress, "the quit below must land mid-match, not after it already finished")

        host.leave()

        assertNull(host.completedMatch.value)
        assertNull(guest.completedMatch.value)
    }

    @Test
    fun `a grace-expired abandonment partway through a match never populates completedMatch`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(sixCardDeck(), MatchConfig("completed-match-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Bo")
        val guest = GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Alex")
        val driver = MatchDriver(host, guest)

        driver.playRound { view, _ -> view.metrics.first().key } // one of three rounds settled, match still in progress
        assertTrue(host.view.value is MatchView.InProgress, "the drop below must land mid-match, not after it already finished")

        hostTransport.dropConnection()
        // The round just played leaves the watchdog's "traffic seen" flag stale into its very
        // first post-drop tick (real traffic, just from before the drop) — up to one extra 2s
        // heartbeat tick before it's actually declared unreachable, versus dropping cold. A little
        // slack here covers that without the test caring about the watchdog's internal tick count.
        advanceTimeBy(10.seconds)
        runCurrent()
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(ConnectionState.Abandoned(AbandonReason.GRACE_EXPIRED), host.connectionState.value)

        assertNull(host.completedMatch.value)
    }
}
