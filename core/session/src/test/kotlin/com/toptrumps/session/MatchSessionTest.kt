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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

private val topSpeed = MetricKey("topSpeed")
private val year = MetricKey("year")

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

/** Submits from whichever side [RulesEngine.deal] actually picked as the first chooser. */
private fun chooseMetric(host: HostMatchSession, guest: GuestMatchSession, metric: MetricKey) {
    val chooser = (host.view.value!!.round as RemoteRoundState.AwaitingChoice).chooser
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

        val hostWinner = (host.view.value!!.round as RemoteRoundState.Resolved).winner
        val guestWinner = (guest.view.value!!.round as RemoteRoundState.Resolved).winner
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
            assertNotNull((host.view.value!!.round as RemoteRoundState.Resolved).winner)
        }
        run {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(testDeck(), MatchConfig("test-deck"), Random(2), hostTransport, backgroundScope)
            val guest = GuestMatchSession(guestTransport, backgroundScope)
            chooseMetric(host, guest, year) // LOW_WINS
            assertNotNull((host.view.value!!.round as RemoteRoundState.Resolved).winner)
        }
    }

    @Test
    fun `only the played metric appears in the guest's opponent view`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(testDeck(), MatchConfig("test-deck"), Random(1), hostTransport, backgroundScope)
        val guest = GuestMatchSession(guestTransport, backgroundScope)

        chooseMetric(host, guest, topSpeed)

        val opponent = guest.view.value!!.opponent as RemoteOpponentView.Contested
        assertEquals(1, opponent.revealed.size)
        assertEquals(topSpeed.id, opponent.revealed.first().metric)
    }

    @Test
    fun `no unplayed stat value survives the wire as JSON`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(testDeck(), MatchConfig("test-deck"), Random(1), hostTransport, backgroundScope)
        val guest = GuestMatchSession(guestTransport, backgroundScope)

        chooseMetric(host, guest, topSpeed)

        val guestView = guest.view.value!!
        val json = String(ProtocolCodec.encodeView(guestView))
        val ownYearValue = guestView.self.stats.getValue(year.id)

        // Every card's year value is distinct. The guest's own card's year legitimately appears
        // (it's their own hand); nobody else's should — that would mean an un-contested opponent
        // stat, or another card's stat entirely, crossed the wire.
        val allYearValues = testDeck().cards.map { it.stats.getValue(year).raw }
        for (value in allYearValues) {
            if (value == ownYearValue) continue
            assertFalse(json.contains(value.toString()), "un-contested year value $value leaked into: $json")
        }

        host.close()
        guest.close()
    }
}
