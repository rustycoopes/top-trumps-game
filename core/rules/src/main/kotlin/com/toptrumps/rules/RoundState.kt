package com.toptrumps.rules

/**
 * Client-side-shaped round phases. Slice 1 deliberately has only one round and no tiebreak
 * recursion, so [Resolved.winner] is `null` only for a tied metric — a case this slice's test
 * deck avoids, and which slice 2's tiebreak chain will replace with a real re-pick.
 */
public sealed interface RoundState {
    @ConsistentCopyVisibility
    public data class AwaitingChoice internal constructor(
        val chooser: Seat,
    ) : RoundState

    @ConsistentCopyVisibility
    public data class Resolved internal constructor(
        val decidingMetric: MetricKey,
        val winner: Seat?,
    ) : RoundState
}

public sealed interface PlayerIntent {
    public data class ChooseMetric(val metric: MetricKey) : PlayerIntent
}

public sealed interface StepResult {
    public data class Applied(val state: MatchState) : StepResult
    public data class Rejected(val reason: String) : StepResult
}
