package com.toptrumps.rules

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
private val engineCapacity = MetricKey("engineCapacity")
private val weight = MetricKey("weight")
private val length = MetricKey("length")
private val allMetrics = listOf(topSpeed, year, engineCapacity, weight, length)

private fun testMetrics(): List<MetricSpec> = listOf(
    MetricSpec(topSpeed, "Top speed", "mph", Direction.HIGH_WINS),
    MetricSpec(year, "Year", "", Direction.LOW_WINS),
    MetricSpec(engineCapacity, "Engine capacity", "cc", Direction.HIGH_WINS),
    MetricSpec(weight, "Weight", "kg", Direction.LOW_WINS),
    MetricSpec(length, "Length", "mm", Direction.LOW_WINS),
)

private fun card(id: String, name: String, top: Double, yr: Double, cap: Double, wt: Double, len: Double) = Card(
    id = id,
    name = name,
    stats = mapOf(
        topSpeed to StatValue(top),
        year to StatValue(yr),
        engineCapacity to StatValue(cap),
        weight to StatValue(wt),
        length to StatValue(len),
    ),
)

/** Six cards (three rounds — an odd count, same shape as the real 15-round no-draw proof). */
private fun testDeck(): Deck = Deck(
    id = "test-deck",
    metrics = testMetrics(),
    cards = listOf(
        card("a", "Alpha", 120.0, 1970.0, 500.0, 200.0, 2000.0),
        card("b", "Bravo", 150.0, 1980.0, 750.0, 220.0, 2100.0),
        card("c", "Charlie", 100.0, 1990.0, 600.0, 190.0, 1950.0),
        card("d", "Delta", 90.0, 2000.0, 400.0, 180.0, 1900.0),
        card("e", "Echo", 175.0, 1965.0, 900.0, 240.0, 2200.0),
        card("f", "Foxtrot", 110.0, 1985.0, 550.0, 210.0, 2050.0),
    ),
)

class RulesEngineTest {

    @Test
    fun `deal splits the deck evenly between host and guest and sets up the first round`() {
        val state = RulesEngine.deal(testDeck(), MatchConfig("test-deck"), Random(1))

        assertEquals(3, state.hands.getValue(Seat.HOST).size)
        assertEquals(3, state.hands.getValue(Seat.GUEST).size)
        assertEquals(3, state.totalRounds)
        assertEquals(1, state.roundNumber)
        assertEquals(0, state.piles.getValue(Seat.HOST).size)
        assertEquals(0, state.piles.getValue(Seat.GUEST).size)
        assertNull(state.outcome)
        assertTrue(state.round is RoundState.AwaitingChoice)
        assertEquals(allMetrics.toSet(), (state.round as RoundState.AwaitingChoice).remainingMetrics.toSet())
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
        assertEquals(RoundResolution.METRIC_DECIDED, resolved.resolution)
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
    fun `only the chooser may pick a metric`() {
        val state = dealWithFixedHands()
        val result = RulesEngine.apply(state, Seat.GUEST, PlayerIntent.ChooseMetric(topSpeed))

        assertTrue(result is StepResult.Rejected)
    }

    @Test
    fun `choosing a metric already excluded by an earlier tie is rejected`() {
        val state = dealWithFixedHands().copy(
            round = RoundState.AwaitingChoice(Seat.HOST, remainingMetrics = allMetrics - topSpeed, revealedMetrics = listOf(topSpeed)),
        )
        val result = RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(topSpeed))

        assertTrue(result is StepResult.Rejected)
    }

    @Test
    fun `advancing before the round resolves is rejected`() {
        val state = dealWithFixedHands()
        val result = RulesEngine.apply(state, Seat.HOST, PlayerIntent.AdvanceRound)

        assertTrue(result is StepResult.Rejected)
    }

    @Test
    fun `a tied metric prompts the same chooser again with that metric excluded`() {
        val state = tiedOnTopSpeed()
        val result = RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(topSpeed))

        assertTrue(result is StepResult.Applied)
        val awaiting = (result as StepResult.Applied).state.round as RoundState.AwaitingChoice
        assertEquals(Seat.HOST, awaiting.chooser)
        assertFalse(topSpeed in awaiting.remainingMetrics)
        assertEquals(listOf(topSpeed), awaiting.revealedMetrics)
    }

    @Test
    fun `a tiebreak that ties again prompts once more, and a third distinguishing metric resolves it`() {
        // Two cards tied on topSpeed and year, distinct on engineCapacity.
        val deck = testDeck().let { deck ->
            deck.copy(
                cards = deck.cards.map {
                    when (it.id) {
                        "a" -> it.copy(stats = it.stats + (year to StatValue(1970.0)))
                        "b" -> it.copy(stats = it.stats + (topSpeed to StatValue(120.0)) + (year to StatValue(1970.0)))
                        else -> it
                    }
                },
            )
        }
        var state = dealt(deck)

        state = (RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(topSpeed)) as StepResult.Applied).state
        assertTrue(state.round is RoundState.AwaitingChoice, "first tie should still be awaiting a re-pick")

        state = (RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(year)) as StepResult.Applied).state
        assertTrue(state.round is RoundState.AwaitingChoice, "second tie should still be awaiting a re-pick")

        state = (RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(engineCapacity)) as StepResult.Applied).state
        val resolved = state.round as RoundState.Resolved
        assertEquals(RoundResolution.METRIC_DECIDED, resolved.resolution)
        assertEquals(engineCapacity, resolved.decidingMetric)
        assertEquals(listOf(topSpeed, year, engineCapacity), resolved.revealedMetrics)
        // Alpha (500cc) vs Bravo (750cc), HIGH_WINS -> guest wins.
        assertEquals(Seat.GUEST, resolved.winner)
    }

    @Test
    fun `CHOOSER_WINS fires when all five metrics tie`() {
        val resolved = resolveAllMetricsTied(TieFallback.CHOOSER_WINS, chooser = Seat.GUEST)

        assertEquals(RoundResolution.ALL_METRICS_TIED_FALLBACK, resolved.resolution)
        assertEquals(Seat.GUEST, resolved.winner)
        assertEquals(allMetrics.size, resolved.revealedMetrics.size)
    }

    @Test
    fun `DEFENDER_WINS fires when all five metrics tie`() {
        val resolved = resolveAllMetricsTied(TieFallback.DEFENDER_WINS, chooser = Seat.GUEST)

        assertEquals(Seat.HOST, resolved.winner)
    }

    @Test
    fun `EACH_KEEPS_OWN has no round winner when all five metrics tie`() {
        val resolved = resolveAllMetricsTied(TieFallback.EACH_KEEPS_OWN, chooser = Seat.GUEST)

        assertNull(resolved.winner)
    }

    @Test
    fun `an active tiebreak chain reveals exactly the metrics played so far and no more`() {
        val deck = testDeck().let { deck ->
            deck.copy(
                cards = deck.cards.map {
                    when (it.id) {
                        "a" -> it.copy(stats = it.stats + (year to StatValue(1970.0)))
                        "b" -> it.copy(stats = it.stats + (topSpeed to StatValue(120.0)) + (year to StatValue(1970.0)))
                        else -> it
                    }
                },
            )
        }
        var state = dealt(deck)
        state = (RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(topSpeed)) as StepResult.Applied).state
        state = (RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(year)) as StepResult.Applied).state

        val hostView = RulesEngine.project(state, Seat.HOST) as PlayerView.InProgress
        val opponent = hostView.opponent as OpponentCardView.Contested

        assertEquals(2, opponent.revealed.size)
        assertEquals(setOf(topSpeed, year), opponent.revealed.map { it.metric }.toSet())
    }

    @Test
    fun `awaiting-choice projection hides the opponent's card entirely on the first choice of a round`() {
        val state = dealWithFixedHands()
        val hostView = RulesEngine.project(state, Seat.HOST) as PlayerView.InProgress

        assertEquals(OpponentCardView.FaceDown, hostView.opponent)
    }

    @Test
    fun `resolved projection reveals the opponent's full card`() {
        val state = dealWithFixedHands()
        val applied = (RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(topSpeed)) as StepResult.Applied).state

        val hostView = RulesEngine.project(applied, Seat.HOST) as PlayerView.InProgress
        val opponent = hostView.opponent as OpponentCardView.Revealed

        assertEquals(150.0, opponent.card.stats.getValue(topSpeed).raw)
        assertEquals(1980.0, opponent.card.stats.getValue(year).raw)
    }

    @Test
    fun `advancing a decisive round moves both cards into the winner's pile and alternates the next chooser`() {
        val state = dealWithFixedHands()
        val resolved = (RulesEngine.apply(state, Seat.HOST, PlayerIntent.ChooseMetric(topSpeed)) as StepResult.Applied).state
        val advanced = (RulesEngine.apply(resolved, Seat.HOST, PlayerIntent.AdvanceRound) as StepResult.Applied).state

        // Guest's Bravo won topSpeed, so both Alpha and Bravo go to the guest's pile.
        assertEquals(2, advanced.piles.getValue(Seat.GUEST).size)
        assertEquals(0, advanced.piles.getValue(Seat.HOST).size)
        val nextAwaiting = advanced.round as RoundState.AwaitingChoice
        assertEquals(Seat.GUEST, nextAwaiting.chooser)
        assertEquals(allMetrics.toSet(), nextAwaiting.remainingMetrics.toSet())
    }

    @Test
    fun `a full match resolves with a winner, no draw, and piles summing to the whole deck`() {
        var state = RulesEngine.deal(testDeck(), MatchConfig("test-deck"), Random(7))
        val choosers = mutableListOf<Seat>()

        while (state.outcome == null) {
            val awaiting = state.round as RoundState.AwaitingChoice
            choosers += awaiting.chooser
            // Every card has a distinct topSpeed in testDeck(), so this always resolves outright
            // — the point of this test is the round loop and its invariants, not tiebreaks.
            state = (RulesEngine.apply(state, awaiting.chooser, PlayerIntent.ChooseMetric(topSpeed)) as StepResult.Applied).state
            state = (RulesEngine.apply(state, awaiting.chooser, PlayerIntent.AdvanceRound) as StepResult.Applied).state
        }

        assertEquals(3, choosers.size, "three rounds for a six-card deck")
        assertEquals(choosers[0], choosers[2], "turn alternation returns to the first chooser on round three")
        assertNotEquals(choosers[0], choosers[1], "turn alternation must flip every round")

        val outcome = state.outcome!!
        assertNotNull(outcome.winner, "an odd round count can never draw under CHOOSER_WINS")
        assertEquals(6, outcome.hostScore + outcome.guestScore)
        assertEquals(0, outcome.hostScore % 2, "every round awards both its cards to one player")
        assertEquals(0, outcome.guestScore % 2)
    }

    private fun resolveAllMetricsTied(fallback: TieFallback, chooser: Seat): RoundState.Resolved {
        val identical = mapOf(
            topSpeed to StatValue(120.0),
            year to StatValue(1970.0),
            engineCapacity to StatValue(500.0),
            weight to StatValue(200.0),
            length to StatValue(2000.0),
        )
        val deck = testDeck().let { deck ->
            deck.copy(cards = deck.cards.map { if (it.id == "a" || it.id == "b") it.copy(stats = identical) else it })
        }
        var state = dealt(deck, MatchConfig(deck.id, fallback)).copy(
            round = RoundState.AwaitingChoice(chooser, remainingMetrics = allMetrics, revealedMetrics = emptyList()),
        )
        for (metric in allMetrics) {
            state = (RulesEngine.apply(state, chooser, PlayerIntent.ChooseMetric(metric)) as StepResult.Applied).state
        }
        return state.round as RoundState.Resolved
    }

    private fun dealWithFixedHands(): MatchState = dealt(testDeck())

    /**
     * Fixes the active (first) card of each hand to Alpha/Bravo, but keeps a second card behind
     * each so that resolving and advancing round one doesn't immediately end the match — tests
     * that need a genuinely single-card, match-ending hand construct that directly instead.
     */
    private fun dealt(deck: Deck, config: MatchConfig = MatchConfig(deck.id)): MatchState {
        val hostCards = listOf(deck.cards.first { it.id == "a" }, deck.cards.first { it.id == "c" })
        val guestCards = listOf(deck.cards.first { it.id == "b" }, deck.cards.first { it.id == "d" })
        return RulesEngine.deal(deck, config, Random(1)).copy(
            hands = mapOf(Seat.HOST to hostCards, Seat.GUEST to guestCards),
            round = RoundState.AwaitingChoice(Seat.HOST, remainingMetrics = allMetrics, revealedMetrics = emptyList()),
        )
    }
}

private fun tiedOnTopSpeed(): MatchState {
    val deck = testDeck().let { deck ->
        deck.copy(
            cards = deck.cards.map { if (it.id == "b") it.copy(stats = it.stats + (topSpeed to StatValue(120.0))) else it },
        )
    }
    val hostCard = deck.cards.first { it.id == "a" }
    val guestCard = deck.cards.first { it.id == "b" }
    return RulesEngine.deal(deck, MatchConfig(deck.id), Random(1)).copy(
        hands = mapOf(Seat.HOST to listOf(hostCard), Seat.GUEST to listOf(guestCard)),
        round = RoundState.AwaitingChoice(Seat.HOST, remainingMetrics = allMetrics, revealedMetrics = emptyList()),
    )
}
