package com.toptrumps.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

private val topSpeed = MetricKey("topSpeed")
private val year = MetricKey("year")

private fun testDeck(): Deck = Deck(
    id = "test-deck",
    metrics = listOf(
        MetricSpec(topSpeed, "Top speed", "mph", Direction.HIGH_WINS),
        MetricSpec(year, "Year", "", Direction.LOW_WINS),
    ),
    cards = listOf(
        Card("a", "Alpha", mapOf(topSpeed to StatValue(120.0), year to StatValue(1970.0))),
        Card("b", "Bravo", mapOf(topSpeed to StatValue(150.0), year to StatValue(1980.0))),
        Card("c", "Charlie", mapOf(topSpeed to StatValue(100.0), year to StatValue(1990.0))),
        Card("d", "Delta", mapOf(topSpeed to StatValue(90.0), year to StatValue(2000.0))),
    ),
)

class RulesEngineTest {

    @Test
    fun `deal splits the deck evenly between host and guest`() {
        val state = RulesEngine.deal(testDeck(), MatchConfig("test-deck"), Random(1))

        assertEquals(2, state.hands.getValue(Seat.HOST).size)
        assertEquals(2, state.hands.getValue(Seat.GUEST).size)
        assertTrue(state.round is RoundState.AwaitingChoice)
    }

    @Test
    fun `deal picks a first chooser at random, not always the host`() {
        val choosers = (0 until 50).map { seed ->
            (RulesEngine.deal(testDeck(), MatchConfig("test-deck"), Random(seed)).round as RoundState.AwaitingChoice).chooser
        }
        assertTrue(Seat.HOST in choosers)
        assertTrue(Seat.GUEST in choosers)
    }

    @Test
    fun `high-wins metric is won by the higher value`() {
        val state = dealWithFixedHands()
        val result = RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(topSpeed))

        assertTrue(result is StepResult.Applied)
        val resolved = (result as StepResult.Applied).state.round as RoundState.Resolved
        assertEquals(topSpeed, resolved.decidingMetric)
        // Alpha (120) vs Bravo (150) on HIGH_WINS -> guest's Bravo wins.
        assertEquals(Seat.GUEST, resolved.winner)
    }

    @Test
    fun `low-wins metric is won by the lower value`() {
        val state = dealWithFixedHands()
        val result = RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(year))

        val resolved = (result as StepResult.Applied).state.round as RoundState.Resolved
        // Alpha (1970) vs Bravo (1980) on LOW_WINS -> host's Alpha wins.
        assertEquals(Seat.HOST, resolved.winner)
    }

    @Test
    fun `a tied metric has no winner`() {
        val deck = testDeck().let { deck ->
            deck.copy(cards = deck.cards.map { if (it.id == "b") it.copy(stats = it.stats + (topSpeed to StatValue(120.0))) else it })
        }
        val state = RulesEngine.deal(deck, MatchConfig(deck.id), Random(1)).let { dealt ->
            dealt.copy(
                hands = mapOf(Seat.HOST to listOf(deck.cards[0]), Seat.GUEST to listOf(deck.cards[1])),
                round = RoundState.AwaitingChoice(Seat.HOST),
            )
        }
        val result = RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(topSpeed))

        val resolved = (result as StepResult.Applied).state.round as RoundState.Resolved
        assertNull(resolved.winner)
    }

    @Test
    fun `only the chooser may pick a metric`() {
        val state = dealWithFixedHands()
        val result = RulesEngine.apply(state, Seat.GUEST, PlayerIntent.ChooseMetric(topSpeed))

        assertTrue(result is StepResult.Rejected)
    }

    @Test
    fun `mid-round projection exposes only the contested metric on the opponent's card`() {
        val state = dealWithFixedHands()
        val applied = (RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(topSpeed)) as StepResult.Applied).state

        val hostView = RulesEngine.project(applied, Seat.HOST)
        val opponent = hostView.opponent as OpponentCardView.Contested

        assertEquals(1, opponent.revealed.size)
        assertEquals(topSpeed, opponent.revealed.first().metric)
    }

    @Test
    fun `awaiting-choice projection hides the opponent's card entirely`() {
        val state = dealWithFixedHands()
        val hostView = RulesEngine.project(state, Seat.HOST)

        assertEquals(OpponentCardView.FaceDown, hostView.opponent)
    }

    private fun dealWithFixedHands(): MatchState {
        val deck = testDeck()
        val dealt = RulesEngine.deal(deck, MatchConfig(deck.id), Random(1))
        return dealt.copy(
            hands = mapOf(Seat.HOST to listOf(deck.cards[0]), Seat.GUEST to listOf(deck.cards[1])),
            round = RoundState.AwaitingChoice(Seat.HOST),
        )
    }
}
