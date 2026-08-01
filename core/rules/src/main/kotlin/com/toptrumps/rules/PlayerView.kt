package com.toptrumps.rules

public data class CardFace(
    val id: String,
    val name: String,
    val stats: Map<MetricKey, StatValue>,
    val imageFile: String,
)

public data class RevealedMetric(val metric: MetricKey, val value: StatValue)

/**
 * The opponent's card, redacted by construction. [Contested] has nowhere to put an unplayed
 * stat — see the structural-redaction ADR. A tiebreak chain naturally accumulates entries in
 * [Contested.revealed] as more metrics are played and tied.
 */
public sealed interface OpponentCardView {
    public data object FaceDown : OpponentCardView

    @ConsistentCopyVisibility
    public data class Contested internal constructor(val revealed: List<RevealedMetric>) : OpponentCardView

    @ConsistentCopyVisibility
    public data class Revealed internal constructor(val card: CardFace) : OpponentCardView
}

/**
 * The sole shape either seat's UI ever sees. Split into [InProgress] and [Finished] rather than
 * carrying a nullable "match over" branch, because once every card is in a pile there is no
 * current [self]/[opponent] card left to show — see [MatchState.outcome].
 */
public sealed interface PlayerView {
    public val revision: Long
    public val myScore: Int
    public val opponentScore: Int
    public val myPile: List<CardFace>

    /**
     * [revision] is monotonic because [kotlinx.coroutines.flow.StateFlow] conflates on
     * `equals()`, and a transition producing a structurally identical view would otherwise fail
     * to emit.
     */
    @ConsistentCopyVisibility
    public data class InProgress internal constructor(
        override val revision: Long,
        val self: CardFace,
        val opponent: OpponentCardView,
        val round: RoundState,
        val metrics: List<MetricSpec>,
        override val myScore: Int,
        override val opponentScore: Int,
        val roundNumber: Int,
        val totalRounds: Int,
        override val myPile: List<CardFace>,
    ) : PlayerView

    @ConsistentCopyVisibility
    public data class Finished internal constructor(
        override val revision: Long,
        /** `null` only reachable under [TieFallback.EACH_KEEPS_OWN]. */
        val winner: Seat?,
        override val myScore: Int,
        override val opponentScore: Int,
        override val myPile: List<CardFace>,
        /** [MatchState.cardsWonWith] for this viewer — the cards match history credits with a win, not everything in [myPile]. */
        val cardsWonWith: List<CardFace>,
    ) : PlayerView
}
