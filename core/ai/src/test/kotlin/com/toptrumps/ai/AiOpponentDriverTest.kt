@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.toptrumps.ai

import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.Direction
import com.toptrumps.rules.MatchConfig
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.MetricSpec
import com.toptrumps.rules.StatValue
import com.toptrumps.session.GuestMatchSession
import com.toptrumps.session.HostMatchSession
import com.toptrumps.session.LoopbackTransport
import com.toptrumps.session.RemoteRoundState
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

class AiOpponentDriverTest {

    @Test
    fun `the AI resolves the round on its own when it is the chooser`() = runTest(UnconfinedTestDispatcher()) {
        // Try enough seeds that at least one deals the AI (guest) the first choice.
        for (seed in 0 until 50) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(testDeck(), MatchConfig("test-deck"), Random(seed), hostTransport, backgroundScope)
            val guest = GuestMatchSession(guestTransport, backgroundScope)
            val ai = AiOpponentDriver(guest, "GUEST", backgroundScope)

            val awaiting = host.view.value!!.round as RemoteRoundState.AwaitingChoice
            if (awaiting.chooser != "GUEST") {
                host.close()
                guest.close()
                continue
            }

            ai.start()

            assertTrue(host.view.value!!.round is RemoteRoundState.Resolved, "AI should have chosen and resolved the round")
            host.close()
            guest.close()
            return@runTest
        }
        throw AssertionError("no seed in range dealt the AI the first choice — widen the range")
    }

    @Test
    fun `chooseMetric deterministically picks the metric with the highest direction-adjusted score`() {
        val view = com.toptrumps.session.MatchView(
            revision = 0,
            self = com.toptrumps.session.RemoteCardFace("x", "X", mapOf("topSpeed" to 50.0, "year" to 1960.0)),
            opponent = com.toptrumps.session.RemoteOpponentView.FaceDown,
            round = RemoteRoundState.AwaitingChoice("GUEST"),
            metrics = listOf(
                com.toptrumps.session.RemoteMetricSpec("topSpeed", "Top Speed", "mph", "HIGH_WINS"),
                com.toptrumps.session.RemoteMetricSpec("year", "Year", "", "LOW_WINS"),
            ),
        )
        val driver = AiOpponentDriver(
            session = object : com.toptrumps.session.MatchSession {
                override val view = kotlinx.coroutines.flow.MutableStateFlow<com.toptrumps.session.MatchView?>(null)
                override fun submit(intent: com.toptrumps.rules.PlayerIntent.ChooseMetric) = Unit
                override fun close() = Unit
            },
            seatName = "GUEST",
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )

        // score(topSpeed) = 50 (HIGH_WINS, unadjusted); score(year) = -1960 (LOW_WINS, negated).
        // 50 > -1960, so the heuristic picks topSpeed.
        val chosen = driver.chooseMetric(view)
        assertEquals(topSpeed, chosen.metric)
    }
}
