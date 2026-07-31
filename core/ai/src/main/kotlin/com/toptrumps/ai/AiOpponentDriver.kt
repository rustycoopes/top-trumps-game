package com.toptrumps.ai

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
 * UI uses."
 */
public class AiOpponentDriver(
    private val session: MatchSession,
    private val seatName: String,
    private val scope: CoroutineScope,
) {
    public fun start() {
        scope.launch {
            session.view.collect { view ->
                if (view != null && view.isMyTurn()) {
                    session.submit(chooseMetric(view))
                }
            }
        }
    }

    private fun MatchView.isMyTurn(): Boolean {
        val round = round
        return round is RemoteRoundState.AwaitingChoice && round.chooser == seatName
    }

    /**
     * Trivial by design: ranks its own stats by a direction-adjusted score and plays the top
     * one. It has no visibility into how each metric is distributed across the deck (only its
     * own card and the metrics' labels/units/directions — the same information a human's UI
     * shows), so this is not a fair comparison across differently-scaled metrics. That is an
     * acceptable placeholder for a "thinnest possible path" slice; a smarter opponent is future
     * work.
     */
    internal fun chooseMetric(view: MatchView): PlayerIntent.ChooseMetric {
        val best = view.metrics.maxBy { spec ->
            val value = view.self.stats.getValue(spec.key)
            // "direction" is a wire string rather than the Direction enum (MatchView is a plain
            // serializable mirror — see MatchView.kt), but DeckLoader only ever accepts
            // "HIGH_WINS"/"LOW_WINS" into a validated deck, so any other value is unreachable here.
            if (spec.direction == "HIGH_WINS") value else -value
        }
        return PlayerIntent.ChooseMetric(MetricKey(best.key))
    }
}
