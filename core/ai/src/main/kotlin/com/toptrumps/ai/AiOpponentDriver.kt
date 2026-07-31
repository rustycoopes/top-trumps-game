package com.toptrumps.ai

import com.toptrumps.rules.Deck
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.session.MatchSession
import com.toptrumps.session.MatchView
import com.toptrumps.session.RemoteRoundState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives a [MatchSession] exactly the way a human's UI would — through [MatchView] and
 * [MatchSession.submit] — so it structurally cannot see anything a real remote opponent
 * couldn't. See TDD §1: "the AI drives the guest through the same public interface a human's
 * UI uses." [deck] is the shared, public deck definition (every card's stats), not hidden
 * per-seat state — the same information a human familiar with this deck would reason from — so
 * consuming it does not violate the redaction model; [MatchView] never carries the opponent's
 * unplayed stats regardless.
 */
public class AiOpponentDriver(
    private val session: MatchSession,
    private val seatName: String,
    private val deck: Deck,
    private val scope: CoroutineScope,
) {
    public fun start() {
        scope.launch {
            session.view.collect { view ->
                if (view is MatchView.InProgress && view.isMyTurn()) {
                    session.submit(chooseMetric(view))
                }
            }
        }
    }

    private fun MatchView.InProgress.isMyTurn(): Boolean {
        val round = round
        return round is RemoteRoundState.AwaitingChoice && round.chooser == seatName
    }

    /**
     * Plausible rather than trivial: picks the metric on which its current card ranks highest
     * within the whole deck, not just the highest raw (direction-adjusted) value on its own
     * card — a metric where a mediocre value is nonetheless the deck's best is a better bet than
     * a big number on a metric everyone has a bigger one for. Needs no special case for
     * tiebreaks: [RemoteRoundState.AwaitingChoice.remainingMetrics] already excludes metrics
     * tied earlier in this round, so the same ranking logic re-picks from what's left.
     */
    internal fun chooseMetric(view: MatchView.InProgress): PlayerIntent.ChooseMetric {
        val awaiting = view.round as RemoteRoundState.AwaitingChoice
        val best = awaiting.remainingMetrics.maxBy { metricId ->
            val spec = view.metrics.first { it.key == metricId }
            val selfValue = view.self.stats.getValue(metricId)
            rankWithinDeck(MetricKey(metricId), selfValue, spec.direction)
        }
        return PlayerIntent.ChooseMetric(MetricKey(best))
    }

    /**
     * The fraction of the deck [selfValue] beats or ties on [metric] — 1.0 is deck-best, low is
     * deck-worst. Comparable across differently-scaled metrics, unlike a raw value.
     */
    private fun rankWithinDeck(metric: MetricKey, selfValue: Double, direction: String): Double {
        val allValues = deck.cards.map { it.stats.getValue(metric).raw }
        // "direction" is a wire string rather than the Direction enum (MatchView is a plain
        // serializable mirror — see MatchView.kt), but DeckLoader only ever accepts
        // "HIGH_WINS"/"LOW_WINS" into a validated deck, so any other value is unreachable here.
        val beatsOrTies = allValues.count { other -> if (direction == "HIGH_WINS") selfValue >= other else selfValue <= other }
        return beatsOrTies.toDouble() / allValues.size
    }
}
