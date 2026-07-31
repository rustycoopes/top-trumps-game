package com.toptrumps.rules

import kotlin.random.Random

/**
 * A synchronous, total reducer. No `suspend`, no clock, no IO — see TDD §2. Never switches on a
 * [MetricKey]; every metric-specific fact (label, unit, [Direction]) comes from the deck's
 * [MetricSpec]s, which is the "second deck, zero code change" guarantee.
 */
public object RulesEngine {

    /**
     * Splits the shuffled deck evenly between the two seats. Only each hand's first card is
     * active this slice — win piles and the round-to-round loop are out of scope (see the
     * slice-1 WBS). The first chooser is picked at random, same as who calls the first stat in
     * a real game — solo mode's AI needs a turn sometimes, not just the human.
     */
    public fun deal(deck: Deck, config: MatchConfig, random: Random): MatchState {
        require(deck.cards.size >= 2) { "a deck needs at least two cards to deal a hand to each seat" }
        val shuffled = deck.cards.shuffled(random)
        val half = shuffled.size / 2
        val hands = mapOf(
            Seat.HOST to shuffled.subList(0, half),
            Seat.GUEST to shuffled.subList(half, shuffled.size),
        )
        return MatchState(
            deck = deck,
            hands = hands,
            round = RoundState.AwaitingChoice(chooser = if (random.nextBoolean()) Seat.HOST else Seat.GUEST),
            revision = 0L,
        )
    }

    public fun apply(state: MatchState, seat: Seat, intent: PlayerIntent): StepResult {
        val awaiting = state.round as? RoundState.AwaitingChoice
            ?: return StepResult.Rejected("round is not awaiting a choice")
        if (seat != awaiting.chooser) {
            return StepResult.Rejected("it is not $seat's turn to choose")
        }
        return when (intent) {
            is PlayerIntent.ChooseMetric -> applyChoice(state, intent.metric)
        }
    }

    private fun applyChoice(state: MatchState, metric: MetricKey): StepResult {
        val spec = state.deck.metrics.firstOrNull { it.key == metric }
            ?: return StepResult.Rejected("unknown metric: ${metric.id}")
        val hostCard = state.hands.getValue(Seat.HOST).first()
        val guestCard = state.hands.getValue(Seat.GUEST).first()
        val hostValue = hostCard.stats.getValue(metric)
        val guestValue = guestCard.stats.getValue(metric)
        val winner = winningSeat(hostValue, guestValue, spec.direction)

        val resolved = state.copy(
            round = RoundState.Resolved(decidingMetric = metric, winner = winner),
            revision = state.revision + 1,
        )
        return StepResult.Applied(resolved)
    }

    private fun winningSeat(hostValue: StatValue, guestValue: StatValue, direction: Direction): Seat? {
        val comparison = hostValue.raw.compareTo(guestValue.raw)
        if (comparison == 0) return null
        val hostIsHigher = comparison > 0
        return if (hostIsHigher == (direction == Direction.HIGH_WINS)) Seat.HOST else Seat.GUEST
    }

    /** The sole constructor of [PlayerView] — see the structural-redaction ADR. */
    public fun project(state: MatchState, viewer: Seat): PlayerView {
        val selfCard = state.hands.getValue(viewer).first()
        val opponentCard = state.hands.getValue(viewer.opponent()).first()

        val opponentView = when (val round = state.round) {
            is RoundState.AwaitingChoice -> OpponentCardView.FaceDown
            is RoundState.Resolved -> OpponentCardView.Contested(
                revealed = listOf(RevealedMetric(round.decidingMetric, opponentCard.stats.getValue(round.decidingMetric))),
            )
        }

        return PlayerView(
            revision = state.revision,
            self = CardFace(selfCard.id, selfCard.name, selfCard.stats),
            opponent = opponentView,
            round = state.round,
            metrics = state.deck.metrics,
        )
    }
}
