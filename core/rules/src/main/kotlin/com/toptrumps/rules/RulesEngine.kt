package com.toptrumps.rules

import kotlin.random.Random

/**
 * A synchronous, total reducer. No `suspend`, no clock, no IO — see TDD §2. Never switches on a
 * [MetricKey]; every metric-specific fact (label, unit, [Direction]) comes from the deck's
 * [MetricSpec]s, which is the "second deck, zero code change" guarantee.
 */
public object RulesEngine {

    /**
     * Splits the shuffled deck evenly between the two seats — one round per card in the shorter
     * hand. The first chooser is picked at random, same as who calls the first stat in a real
     * game; every round after that alternates strictly from whichever seat chose first (see
     * [applyAdvance]), regardless of who has been winning.
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
            config = config,
            hands = hands,
            piles = mapOf(Seat.HOST to emptyList(), Seat.GUEST to emptyList()),
            roundNumber = 1,
            totalRounds = minOf(hands.getValue(Seat.HOST).size, hands.getValue(Seat.GUEST).size),
            round = RoundState.AwaitingChoice(
                chooser = if (random.nextBoolean()) Seat.HOST else Seat.GUEST,
                remainingMetrics = deck.metrics.map { it.key },
                revealedMetrics = emptyList(),
            ),
            outcome = null,
            revision = 0L,
        )
    }

    public fun apply(state: MatchState, seat: Seat, intent: PlayerIntent): StepResult {
        return when (intent) {
            is PlayerIntent.ChooseMetric -> {
                val awaiting = state.round as? RoundState.AwaitingChoice
                    ?: return StepResult.Rejected("round is not awaiting a choice")
                if (seat != awaiting.chooser) {
                    return StepResult.Rejected("it is not $seat's turn to choose")
                }
                applyChoice(state, awaiting, intent.metric)
            }
            is PlayerIntent.AdvanceRound -> {
                val resolved = state.round as? RoundState.Resolved
                    ?: return StepResult.Rejected("round is not resolved")
                applyAdvance(state, resolved)
            }
        }
    }

    private fun applyChoice(state: MatchState, awaiting: RoundState.AwaitingChoice, metric: MetricKey): StepResult {
        if (metric !in awaiting.remainingMetrics) {
            return StepResult.Rejected("metric ${metric.id} is not available to choose")
        }
        val spec = state.deck.metrics.firstOrNull { it.key == metric }
            ?: return StepResult.Rejected("unknown metric: ${metric.id}")
        val hostCard = state.hands.getValue(Seat.HOST).first()
        val guestCard = state.hands.getValue(Seat.GUEST).first()
        val winner = winningSeat(hostCard.stats.getValue(metric), guestCard.stats.getValue(metric), spec.direction)
        val revealedMetrics = awaiting.revealedMetrics + metric

        if (winner != null) {
            return StepResult.Applied(
                state.copy(
                    round = RoundState.Resolved(
                        chooser = awaiting.chooser,
                        winner = winner,
                        decidingMetric = metric,
                        resolution = RoundResolution.METRIC_DECIDED,
                        revealedMetrics = revealedMetrics,
                    ),
                    revision = state.revision + 1,
                ),
            )
        }

        val remainingMetrics = awaiting.remainingMetrics - metric
        if (remainingMetrics.isEmpty()) {
            val fallbackWinner = when (state.config.allMetricsTieFallback) {
                TieFallback.CHOOSER_WINS -> awaiting.chooser
                TieFallback.DEFENDER_WINS -> awaiting.chooser.opponent()
                TieFallback.EACH_KEEPS_OWN -> null
            }
            return StepResult.Applied(
                state.copy(
                    round = RoundState.Resolved(
                        chooser = awaiting.chooser,
                        winner = fallbackWinner,
                        decidingMetric = metric,
                        resolution = RoundResolution.ALL_METRICS_TIED_FALLBACK,
                        revealedMetrics = revealedMetrics,
                    ),
                    revision = state.revision + 1,
                ),
            )
        }

        return StepResult.Applied(
            state.copy(
                round = RoundState.AwaitingChoice(
                    chooser = awaiting.chooser,
                    remainingMetrics = remainingMetrics,
                    revealedMetrics = revealedMetrics,
                ),
                revision = state.revision + 1,
            ),
        )
    }

    /** Moves both of the round's cards into a pile, then either deals the next round or ends the match. */
    private fun applyAdvance(state: MatchState, resolved: RoundState.Resolved): StepResult {
        val hostCard = state.hands.getValue(Seat.HOST).first()
        val guestCard = state.hands.getValue(Seat.GUEST).first()
        val newHands = mapOf(
            Seat.HOST to state.hands.getValue(Seat.HOST).drop(1),
            Seat.GUEST to state.hands.getValue(Seat.GUEST).drop(1),
        )
        val newPiles = when (resolved.winner) {
            Seat.HOST -> state.piles + (Seat.HOST to state.piles.getValue(Seat.HOST) + hostCard + guestCard)
            Seat.GUEST -> state.piles + (Seat.GUEST to state.piles.getValue(Seat.GUEST) + hostCard + guestCard)
            null -> mapOf(
                Seat.HOST to state.piles.getValue(Seat.HOST) + hostCard,
                Seat.GUEST to state.piles.getValue(Seat.GUEST) + guestCard,
            )
        }

        if (newHands.getValue(Seat.HOST).isEmpty() || newHands.getValue(Seat.GUEST).isEmpty()) {
            val hostScore = newPiles.getValue(Seat.HOST).size
            val guestScore = newPiles.getValue(Seat.GUEST).size
            val matchWinner = when {
                hostScore > guestScore -> Seat.HOST
                guestScore > hostScore -> Seat.GUEST
                else -> null
            }
            return StepResult.Applied(
                state.copy(
                    hands = newHands,
                    piles = newPiles,
                    outcome = MatchOutcome(matchWinner, hostScore, guestScore),
                    revision = state.revision + 1,
                ),
            )
        }

        return StepResult.Applied(
            state.copy(
                hands = newHands,
                piles = newPiles,
                roundNumber = state.roundNumber + 1,
                round = RoundState.AwaitingChoice(
                    chooser = resolved.chooser.opponent(),
                    remainingMetrics = state.deck.metrics.map { it.key },
                    revealedMetrics = emptyList(),
                ),
                revision = state.revision + 1,
            ),
        )
    }

    private fun winningSeat(hostValue: StatValue, guestValue: StatValue, direction: Direction): Seat? {
        val comparison = hostValue.raw.compareTo(guestValue.raw)
        if (comparison == 0) return null
        val hostIsHigher = comparison > 0
        return if (hostIsHigher == (direction == Direction.HIGH_WINS)) Seat.HOST else Seat.GUEST
    }

    /** The sole constructor of [PlayerView] — see the structural-redaction ADR. */
    public fun project(state: MatchState, viewer: Seat): PlayerView {
        val opponentSeat = viewer.opponent()
        val outcome = state.outcome
        if (outcome != null) {
            return PlayerView.Finished(
                revision = state.revision,
                winner = outcome.winner,
                myScore = state.piles.getValue(viewer).size,
                opponentScore = state.piles.getValue(opponentSeat).size,
                myPile = state.piles.getValue(viewer).map { it.toCardFace() },
            )
        }

        val selfCard = state.hands.getValue(viewer).first()
        val opponentCard = state.hands.getValue(opponentSeat).first()

        val opponentView = when (val round = state.round) {
            is RoundState.AwaitingChoice -> if (round.revealedMetrics.isEmpty()) {
                OpponentCardView.FaceDown
            } else {
                OpponentCardView.Contested(
                    revealed = round.revealedMetrics.map { RevealedMetric(it, opponentCard.stats.getValue(it)) },
                )
            }
            is RoundState.Resolved -> OpponentCardView.Revealed(opponentCard.toCardFace())
        }

        return PlayerView.InProgress(
            revision = state.revision,
            self = selfCard.toCardFace(),
            opponent = opponentView,
            round = state.round,
            metrics = state.deck.metrics,
            myScore = state.piles.getValue(viewer).size,
            opponentScore = state.piles.getValue(opponentSeat).size,
            roundNumber = state.roundNumber,
            totalRounds = state.totalRounds,
            myPile = state.piles.getValue(viewer).map { it.toCardFace() },
        )
    }

    private fun Card.toCardFace(): CardFace = CardFace(id, name, stats)
}
