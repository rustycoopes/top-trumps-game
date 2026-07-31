package com.toptrumps.rules

public data class CardFace(
    val id: String,
    val name: String,
    val stats: Map<MetricKey, StatValue>,
)

public data class RevealedMetric(val metric: MetricKey, val value: StatValue)

/**
 * The opponent's card, redacted by construction. [Contested] has nowhere to put an unplayed
 * stat — see the structural-redaction ADR.
 */
public sealed interface OpponentCardView {
    public data object FaceDown : OpponentCardView

    @ConsistentCopyVisibility
    public data class Contested internal constructor(val revealed: List<RevealedMetric>) : OpponentCardView

    @ConsistentCopyVisibility
    public data class Revealed internal constructor(val card: CardFace) : OpponentCardView
}

/**
 * The sole shape either seat's UI ever sees. [revision] is monotonic because [kotlinx.coroutines.flow.StateFlow]
 * conflates on `equals()`, and a transition producing a structurally identical view would
 * otherwise fail to emit.
 */
@ConsistentCopyVisibility
public data class PlayerView internal constructor(
    val revision: Long,
    val self: CardFace,
    val opponent: OpponentCardView,
    val round: RoundState,
    val metrics: List<MetricSpec>,
)
