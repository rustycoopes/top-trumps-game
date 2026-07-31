@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.toptrumps.ai

import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.Direction
import com.toptrumps.rules.MatchConfig
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.MetricSpec
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.rules.StatValue
import com.toptrumps.session.GuestMatchSession
import com.toptrumps.session.HostMatchSession
import com.toptrumps.session.LoopbackTransport
import com.toptrumps.session.MatchSession
import com.toptrumps.session.MatchView
import com.toptrumps.session.RemoteCardFace
import com.toptrumps.session.RemoteMetricSpec
import com.toptrumps.session.RemoteOpponentView
import com.toptrumps.session.RemoteRoundState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

/** X's topSpeed is the deck's worst; X's year is the deck's best under LOW_WINS. */
private fun rankingDeck(): Deck = Deck(
    id = "ranking-deck",
    metrics = listOf(
        MetricSpec(topSpeed, "Top Speed", "mph", Direction.HIGH_WINS),
        MetricSpec(year, "Year", "", Direction.LOW_WINS),
    ),
    cards = listOf(
        Card("x", "X", mapOf(topSpeed to StatValue(50.0), year to StatValue(1960.0))),
        Card("p", "P", mapOf(topSpeed to StatValue(200.0), year to StatValue(1990.0))),
        Card("q", "Q", mapOf(topSpeed to StatValue(210.0), year to StatValue(2000.0))),
        Card("r", "R", mapOf(topSpeed to StatValue(220.0), year to StatValue(2010.0))),
    ),
)

private fun fakeSession(): MatchSession = object : MatchSession {
    override val view = MutableStateFlow<MatchView?>(null)
    override fun submit(intent: PlayerIntent) = Unit
    override fun close() = Unit
}

class AiOpponentDriverTest {

    @Test
    fun `the AI resolves the round on its own when it is the chooser`() = runTest(UnconfinedTestDispatcher()) {
        // Try enough seeds that at least one deals the AI (guest) the first choice.
        for (seed in 0 until 50) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(testDeck(), MatchConfig("test-deck"), Random(seed), hostTransport, backgroundScope)
            val guest = GuestMatchSession(guestTransport, backgroundScope)
            val ai = AiOpponentDriver(guest, "GUEST", testDeck(), backgroundScope)

            val awaiting = (host.view.value as MatchView.InProgress).round as RemoteRoundState.AwaitingChoice
            if (awaiting.chooser != "GUEST") {
                host.close()
                guest.close()
                continue
            }

            ai.start()

            assertTrue(
                (host.view.value as MatchView.InProgress).round is RemoteRoundState.Resolved,
                "AI should have chosen and resolved the round",
            )
            host.close()
            guest.close()
            return@runTest
        }
        throw AssertionError("no seed in range dealt the AI the first choice — widen the range")
    }

    @Test
    fun `chooseMetric picks the metric where the card ranks highest within the deck, not the biggest raw number`() {
        val view = MatchView.InProgress(
            revision = 0,
            self = RemoteCardFace("x", "X", mapOf("topSpeed" to 50.0, "year" to 1960.0)),
            opponent = RemoteOpponentView.FaceDown,
            round = RemoteRoundState.AwaitingChoice("GUEST", remainingMetrics = listOf("topSpeed", "year"), revealedMetrics = emptyList()),
            metrics = listOf(
                RemoteMetricSpec("topSpeed", "Top Speed", "mph", "HIGH_WINS"),
                RemoteMetricSpec("year", "Year", "", "LOW_WINS"),
            ),
            myScore = 0,
            opponentScore = 0,
            roundNumber = 1,
            totalRounds = 2,
            myPile = emptyList(),
        )
        val driver = AiOpponentDriver(
            session = fakeSession(),
            seatName = "GUEST",
            deck = rankingDeck(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        // A raw-value heuristic would pick topSpeed (50, HIGH_WINS-unadjusted, beats -1960,
        // year negated for LOW_WINS). Deck-relative ranking correctly picks year instead: 50 is
        // this deck's worst topSpeed, but 1960 is this deck's best (oldest) year.
        val chosen = driver.chooseMetric(view)
        assertEquals(year, chosen.metric)
    }

    @Test
    fun `chooseMetric only offers metrics still available in a tiebreak`() {
        val view = MatchView.InProgress(
            revision = 0,
            self = RemoteCardFace("x", "X", mapOf("topSpeed" to 50.0, "year" to 1960.0)),
            opponent = RemoteOpponentView.FaceDown,
            round = RemoteRoundState.AwaitingChoice("GUEST", remainingMetrics = listOf("topSpeed"), revealedMetrics = listOf("year")),
            metrics = listOf(
                RemoteMetricSpec("topSpeed", "Top Speed", "mph", "HIGH_WINS"),
                RemoteMetricSpec("year", "Year", "", "LOW_WINS"),
            ),
            myScore = 0,
            opponentScore = 0,
            roundNumber = 1,
            totalRounds = 2,
            myPile = emptyList(),
        )
        val driver = AiOpponentDriver(fakeSession(), "GUEST", rankingDeck(), CoroutineScope(Dispatchers.Unconfined))

        // year is deck-best but was tied earlier and excluded — topSpeed is the only option left.
        val chosen = driver.chooseMetric(view)
        assertEquals(topSpeed, chosen.metric)
    }
}
