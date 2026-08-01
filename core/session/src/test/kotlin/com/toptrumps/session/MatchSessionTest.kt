@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.toptrumps.session

import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.Direction
import com.toptrumps.rules.MatchConfig
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.MetricSpec
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.rules.StatValue
import com.toptrumps.rules.TieFallback
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

private val topSpeed = MetricKey("topSpeed")
private val year = MetricKey("year")
private val weight = MetricKey("weight")

private fun testDeck(): Deck = Deck(
    id = "test-deck",
    metrics = listOf(
        MetricSpec(topSpeed, "Top Speed", "mph", Direction.HIGH_WINS),
        MetricSpec(year, "Year", "", Direction.LOW_WINS),
    ),
    cards = listOf(
        Card("a", "Alpha", mapOf(topSpeed to StatValue(120.0), year to StatValue(1975.0))),
        Card("b", "Bravo", mapOf(topSpeed to StatValue(150.0), year to StatValue(1988.0))),
        Card("c", "Charlie", mapOf(topSpeed to StatValue(95.0), year to StatValue(1962.0))),
        Card("d", "Delta", mapOf(topSpeed to StatValue(175.0), year to StatValue(2001.0))),
    ),
)

/**
 * Every card ties every other card on topSpeed and year, but all four weights are distinct — so
 * regardless of which two cards a shuffle happens to pair for round one, that round always ties
 * twice and is always settled by weight on the third pick.
 */
private fun tieableDeck(): Deck = Deck(
    id = "tieable-deck",
    metrics = listOf(
        MetricSpec(topSpeed, "Top Speed", "mph", Direction.HIGH_WINS),
        MetricSpec(year, "Year", "", Direction.LOW_WINS),
        MetricSpec(weight, "Weight", "kg", Direction.LOW_WINS),
    ),
    cards = listOf(
        Card("a", "Alpha", mapOf(topSpeed to StatValue(120.0), year to StatValue(1975.0), weight to StatValue(200.0))),
        Card("b", "Bravo", mapOf(topSpeed to StatValue(120.0), year to StatValue(1975.0), weight to StatValue(180.0))),
        Card("c", "Charlie", mapOf(topSpeed to StatValue(120.0), year to StatValue(1975.0), weight to StatValue(210.0))),
        Card("d", "Delta", mapOf(topSpeed to StatValue(120.0), year to StatValue(1975.0), weight to StatValue(190.0))),
    ),
)

/**
 * Every card is identical on every metric, so whichever two cards a shuffle happens to pair for
 * round one, that round is always an all-metrics tie — the tests below don't need to control
 * [RulesEngine.deal]'s shuffle to exercise the fallback.
 */
private fun allTiedDeck(): Deck = Deck(
    id = "all-tied-deck",
    metrics = listOf(
        MetricSpec(topSpeed, "Top Speed", "mph", Direction.HIGH_WINS),
        MetricSpec(year, "Year", "", Direction.LOW_WINS),
    ),
    cards = listOf(
        Card("a", "Alpha", mapOf(topSpeed to StatValue(120.0), year to StatValue(1975.0))),
        Card("b", "Bravo", mapOf(topSpeed to StatValue(120.0), year to StatValue(1975.0))),
        Card("c", "Charlie", mapOf(topSpeed to StatValue(120.0), year to StatValue(1975.0))),
        Card("d", "Delta", mapOf(topSpeed to StatValue(120.0), year to StatValue(1975.0))),
    ),
)

/** Six distinct-topSpeed cards (three rounds) so a full match always resolves on the first pick. */
private fun sixCardDeck(): Deck = Deck(
    id = "six-card-deck",
    metrics = listOf(MetricSpec(topSpeed, "Top Speed", "mph", Direction.HIGH_WINS)),
    cards = listOf(
        Card("a", "Alpha", mapOf(topSpeed to StatValue(120.0))),
        Card("b", "Bravo", mapOf(topSpeed to StatValue(150.0))),
        Card("c", "Charlie", mapOf(topSpeed to StatValue(95.0))),
        Card("d", "Delta", mapOf(topSpeed to StatValue(175.0))),
        Card("e", "Echo", mapOf(topSpeed to StatValue(200.0))),
        Card("f", "Foxtrot", mapOf(topSpeed to StatValue(110.0))),
    ),
)

/** Submits from whichever side [com.toptrumps.rules.RulesEngine.deal] actually picked as the first chooser. */
private fun chooseMetric(host: HostMatchSession, guest: GuestMatchSession, metric: MetricKey) {
    val chooser = (host.view.value as MatchView.InProgress).round.let { it as RemoteRoundState.AwaitingChoice }.chooser
    val intent = PlayerIntent.ChooseMetric(metric)
    if (chooser == "HOST") host.submit(intent) else guest.submit(intent)
}

/**
 * The primary seam: two [MatchSession]s over an in-memory [Transport]. Because [Transport]
 * carries bytes, this exercises the real [ProtocolCodec], not just [com.toptrumps.rules.RulesEngine].
 * [UnconfinedTestDispatcher] runs each session's internal coroutine eagerly, so no manual
 * `advanceUntilIdle()` choreography is needed to observe an update.
 */
class MatchSessionTest {

    @Test
    fun `a chosen metric resolves identically on host and guest`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(testDeck(), MatchConfig("test-deck"), Random(1), hostTransport, backgroundScope)
        val guest = GuestMatchSession(guestTransport, backgroundScope)

        assertNotNull(guest.view.value, "guest should receive an initial view over the wire")

        chooseMetric(host, guest, topSpeed)

        val hostWinner = ((host.view.value as MatchView.InProgress).round as RemoteRoundState.Resolved).winner
        val guestWinner = ((guest.view.value as MatchView.InProgress).round as RemoteRoundState.Resolved).winner
        assertEquals(hostWinner, guestWinner)

        host.close()
        guest.close()
    }

    @Test
    fun `win direction works both ways over the wire`() = runTest(UnconfinedTestDispatcher()) {
        run {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(testDeck(), MatchConfig("test-deck"), Random(1), hostTransport, backgroundScope)
            val guest = GuestMatchSession(guestTransport, backgroundScope)
            chooseMetric(host, guest, topSpeed) // HIGH_WINS
            assertNotNull(((host.view.value as MatchView.InProgress).round as RemoteRoundState.Resolved).winner)
        }
        run {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(testDeck(), MatchConfig("test-deck"), Random(2), hostTransport, backgroundScope)
            val guest = GuestMatchSession(guestTransport, backgroundScope)
            chooseMetric(host, guest, year) // LOW_WINS
            assertNotNull(((host.view.value as MatchView.InProgress).round as RemoteRoundState.Resolved).winner)
        }
    }

    @Test
    fun `the opponent's full card is revealed on the guest once the round resolves`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(testDeck(), MatchConfig("test-deck"), Random(1), hostTransport, backgroundScope)
        val guest = GuestMatchSession(guestTransport, backgroundScope)

        chooseMetric(host, guest, topSpeed)

        val opponent = (guest.view.value as MatchView.InProgress).opponent as RemoteOpponentView.Revealed
        assertEquals(2, opponent.card.stats.size, "every stat, not just the played one, is now visible")
    }

    @Test
    fun `during an active tiebreak chain, only the metrics played so far appear on the guest's opponent view`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(tieableDeck(), MatchConfig("tieable-deck"), Random(1), hostTransport, backgroundScope)
            val guest = GuestMatchSession(guestTransport, backgroundScope)
            val driver = MatchDriver(host, guest)

            driver.choose(topSpeed) // ties
            driver.choose(year) // ties again

            val opponent = (guest.view.value as MatchView.InProgress).opponent as RemoteOpponentView.Contested
            assertEquals(2, opponent.revealed.size)
            assertEquals(setOf("topSpeed", "year"), opponent.revealed.map { it.metric }.toSet())

            host.close()
            guest.close()
        }

    @Test
    fun `no unplayed stat value survives the wire as JSON, including across a two-step tiebreak`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(tieableDeck(), MatchConfig("tieable-deck"), Random(1), hostTransport, backgroundScope)
            val guest = GuestMatchSession(guestTransport, backgroundScope)
            val driver = MatchDriver(host, guest)

            driver.choose(topSpeed) // ties
            driver.choose(year) // ties again — two stats now legitimately visible

            val guestView = guest.view.value as MatchView.InProgress
            val json = String(ProtocolCodec.encodeHostToGuest(HostToGuest.View(guestView)))
            val ownWeightValue = guestView.self.stats.getValue(weight.id)

            // Every card's weight value is distinct, and weight hasn't been played yet. The
            // guest's own weight legitimately appears (it's their own hand); nobody else's should.
            val allWeightValues = tieableDeck().cards.map { it.stats.getValue(weight).raw }
            for (value in allWeightValues) {
                if (value == ownWeightValue) continue
                assertFalse(json.contains(value.toString()), "un-contested weight value $value leaked into: $json")
            }

            host.close()
            guest.close()
        }

    @Test
    fun `CHOOSER_WINS travels on the wire and the guest agrees on the winner`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val config = MatchConfig("all-tied-deck", TieFallback.CHOOSER_WINS)
        val host = HostMatchSession(allTiedDeck(), config, Random(1), hostTransport, backgroundScope)
        val guest = GuestMatchSession(guestTransport, backgroundScope)
        val driver = MatchDriver(host, guest)
        val chooser = (driver.currentView().round as RemoteRoundState.AwaitingChoice).chooser

        driver.choose(topSpeed)
        driver.choose(year)

        val hostResolved = (host.view.value as MatchView.InProgress).round as RemoteRoundState.Resolved
        val guestResolved = (guest.view.value as MatchView.InProgress).round as RemoteRoundState.Resolved
        assertEquals("ALL_METRICS_TIED_FALLBACK", hostResolved.resolution)
        assertEquals(chooser, hostResolved.winner)
        assertEquals(hostResolved.winner, guestResolved.winner)

        host.close()
        guest.close()
    }

    @Test
    fun `DEFENDER_WINS travels on the wire and the guest agrees on the winner`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val config = MatchConfig("all-tied-deck", TieFallback.DEFENDER_WINS)
        val host = HostMatchSession(allTiedDeck(), config, Random(1), hostTransport, backgroundScope)
        val guest = GuestMatchSession(guestTransport, backgroundScope)
        val driver = MatchDriver(host, guest)
        val chooser = (driver.currentView().round as RemoteRoundState.AwaitingChoice).chooser
        val defender = if (chooser == "HOST") "GUEST" else "HOST"

        driver.choose(topSpeed)
        driver.choose(year)

        val guestResolved = (guest.view.value as MatchView.InProgress).round as RemoteRoundState.Resolved
        assertEquals(defender, guestResolved.winner)

        host.close()
        guest.close()
    }

    @Test
    fun `EACH_KEEPS_OWN travels on the wire and both sides see no round winner`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val config = MatchConfig("all-tied-deck", TieFallback.EACH_KEEPS_OWN)
        val host = HostMatchSession(allTiedDeck(), config, Random(1), hostTransport, backgroundScope)
        val guest = GuestMatchSession(guestTransport, backgroundScope)
        val driver = MatchDriver(host, guest)

        driver.choose(topSpeed)
        driver.choose(year)

        val hostResolved = (host.view.value as MatchView.InProgress).round as RemoteRoundState.Resolved
        val guestResolved = (guest.view.value as MatchView.InProgress).round as RemoteRoundState.Resolved
        assertNull(hostResolved.winner)
        assertNull(guestResolved.winner)

        host.close()
        guest.close()
    }

    @Test
    fun `a full match resolves identically on host and guest, with no draw and turn alternation held`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(sixCardDeck(), MatchConfig("six-card-deck"), Random(3), hostTransport, backgroundScope)
            val guest = GuestMatchSession(guestTransport, backgroundScope)
            val driver = MatchDriver(host, guest)
            val choosers = mutableListOf<String>()

            driver.playMatch { view, round ->
                choosers += round.chooser
                view.metrics.first().key // sixCardDeck has one metric, and every card's value is distinct.
            }

            assertEquals(3, choosers.size)
            assertEquals(choosers[0], choosers[2])
            assertNotEquals(choosers[0], choosers[1])

            val hostFinal = host.view.value as MatchView.Finished
            val guestFinal = guest.view.value as MatchView.Finished
            assertEquals(hostFinal.winner, guestFinal.winner)
            assertNotNull(hostFinal.winner, "an odd round count can never draw under the default CHOOSER_WINS config")
            assertEquals(6, hostFinal.myScore + hostFinal.opponentScore)
            assertTrue(hostFinal.myScore % 2 == 0 && hostFinal.opponentScore % 2 == 0)

            host.close()
            guest.close()
        }

    @Test
    fun `MatchStart carries a duplicate-free hand of the right size, reproducible from a seed`() =
        runTest(UnconfinedTestDispatcher()) {
            fun guestHandIds(seed: Long): List<String> {
                val (hostTransport, guestTransportRaw) = LoopbackTransport.createPair()
                val recording = RecordingTransport(guestTransportRaw)
                HostMatchSession(sixCardDeck(), MatchConfig("six-card-deck"), Random(seed), hostTransport, backgroundScope)
                GuestMatchSession(recording, backgroundScope)
                val matchStart = recording.frames
                    .map { ProtocolCodec.decodeHostToGuest(it) }
                    .filterIsInstance<HostToGuest.MatchStart>()
                    .single()
                return matchStart.yourHand.map { it.id }
            }

            val dealtHand = guestHandIds(seed = 42)
            assertEquals(3, dealtHand.size)
            assertEquals(dealtHand.toSet().size, dealtHand.size, "no duplicate cards in the guest's dealt hand")
            assertTrue(dealtHand.all { id -> sixCardDeck().cards.any { it.id == id } }, "every dealt card comes from the deck")
            assertEquals(dealtHand, guestHandIds(seed = 42), "the same seed deals the same hand")
        }

    @Test
    fun `no unplayed stat leaks in any frame sent across a full match, not just the final one`() =
        runTest(UnconfinedTestDispatcher()) {
            val deck = tieableDeck()
            val (hostTransport, guestTransportRaw) = LoopbackTransport.createPair()
            val recording = RecordingTransport(guestTransportRaw)
            val host = HostMatchSession(deck, MatchConfig(deck.id), Random(1), hostTransport, backgroundScope)
            val guest = GuestMatchSession(recording, backgroundScope)
            val driver = MatchDriver(host, guest)

            driver.playMatch { _, round -> round.remainingMetrics.first() }

            val allStatValues = deck.cards.flatMap { it.stats.values.map { value -> value.raw } }.toSet()
            val inProgressFrames = recording.frames.filter {
                (ProtocolCodec.decodeHostToGuest(it) as? HostToGuest.View)?.view is MatchView.InProgress
            }
            assertTrue(inProgressFrames.isNotEmpty(), "the match should have produced at least one in-progress view")

            for (frame in inProgressFrames) {
                val view = ((ProtocolCodec.decodeHostToGuest(frame) as HostToGuest.View).view as MatchView.InProgress)
                // self, the whole pile (already permanently won, both cards from every settled
                // round) and whatever's been revealed so far on the current opponent card are all
                // legitimately visible; anything else from the deck must not appear.
                val legitimatelyVisible = view.self.stats.values.toSet() +
                    view.myPile.flatMap { it.stats.values }.toSet() +
                    when (val opponent = view.opponent) {
                        is RemoteOpponentView.FaceDown -> emptySet()
                        is RemoteOpponentView.Contested -> opponent.revealed.map { it.value }.toSet()
                        is RemoteOpponentView.Revealed -> opponent.card.stats.values.toSet()
                    }
                val json = String(frame)
                for (value in allStatValues) {
                    if (value in legitimatelyVisible) continue
                    // A plain substring check, not JSON-token-aware — safe here because
                    // tieableDeck()'s values (120.0, 1975.0, 180.0–210.0) are digit-disjoint. A
                    // future fixture whose values are substrings of each other (e.g. 2.0 and
                    // 12.0) would need a token-boundary-aware check instead.
                    assertFalse(json.contains(value.toString()), "un-owned, unrevealed value $value leaked into: $json")
                }
            }

            host.close()
            guest.close()
        }
}
