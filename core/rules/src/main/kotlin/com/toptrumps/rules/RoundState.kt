package com.toptrumps.rules

/**
 * Why a round resolved. Distinct from [RoundState.Resolved.decidingMetric] because "this metric
 * separated the cards" and "every metric tied, so the fallback fired" are different facts the UI
 * must be able to tell apart (see the WBS: "the UI states which metric finally settled a tied
 * round" and "`CHOOSER_WINS` fires correctly").
 */
public enum class RoundResolution { METRIC_DECIDED, ALL_METRICS_TIED_FALLBACK }

/**
 * Only [AwaitingChoice] and [Resolved] are authoritative — see TDD §3. Match completion is
 * modelled separately, on [MatchState.outcome], so this sealed interface never grows a third
 * "match over" variant that would otherwise duplicate [Resolved]'s data.
 */
public sealed interface RoundState {
    @ConsistentCopyVisibility
    public data class AwaitingChoice internal constructor(
        val chooser: Seat,
        /** Strictly decreasing across a tiebreak chain — see the termination proof in TDD §3. */
        val remainingMetrics: List<MetricKey>,
        /** Metrics already played (and tied) earlier in this same round's tiebreak chain. */
        val revealedMetrics: List<MetricKey>,
    ) : RoundState

    @ConsistentCopyVisibility
    public data class Resolved internal constructor(
        val chooser: Seat,
        /** `null` only under [TieFallback.EACH_KEEPS_OWN]. */
        val winner: Seat?,
        val decidingMetric: MetricKey,
        val resolution: RoundResolution,
        val revealedMetrics: List<MetricKey>,
    ) : RoundState
}

public sealed interface PlayerIntent {
    public data class ChooseMetric(val metric: MetricKey) : PlayerIntent

    /**
     * Advances a [RoundState.Resolved] round to the next round's [RoundState.AwaitingChoice] (or
     * ends the match). Not gated to a particular seat — the reveal it dismisses is already fully
     * public to both players, so there is no information advantage in submitting it first. See
     * TDD Open Question 5 and the WBS: "the player controls the pace of advancing."
     */
    public data object AdvanceRound : PlayerIntent
}

public sealed interface StepResult {
    public data class Applied(val state: MatchState) : StepResult
    public data class Rejected(val reason: String) : StepResult
}
