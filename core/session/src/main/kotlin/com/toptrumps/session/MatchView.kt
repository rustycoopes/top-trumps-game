package com.toptrumps.session

import com.toptrumps.rules.CardFace
import com.toptrumps.rules.MatchResult
import com.toptrumps.rules.MatchSummary
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.OpponentCardView
import com.toptrumps.rules.PlayerView
import com.toptrumps.rules.RoundState
import com.toptrumps.rules.Seat
import com.toptrumps.rules.StatValue
import com.toptrumps.rules.displayDirection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A wire-serializable mirror of [PlayerView]. `:core:rules`'s [PlayerView] has an internal
 * constructor (see the structural-redaction ADR), so a different module cannot reconstruct one
 * from decoded bytes — this type is what the guest side of [MatchSession] holds instead. It is
 * structurally identical to [PlayerView] (in particular, [RemoteOpponentView.Contested] has
 * nowhere to put an unplayed stat either), so the redaction guarantee carries over at the value
 * level. The public constructors here are conventionally reserved for [PlayerView.toMatchView]
 * and [ProtocolCodec] — nothing enforces that at compile time the way [PlayerView]'s `internal`
 * constructor does, since every legitimate value still traces back to a real [PlayerView].
 */
@Serializable
public data class RemoteMetricSpec(
    val key: String,
    val label: String,
    val unit: String,
    val direction: String,
    val display: String = "RAW",
)

@Serializable
public data class RemoteCardFace(
    val id: String,
    val name: String,
    val stats: Map<String, Double>,
    val imageFile: String = "",
)

@Serializable
public data class RemoteRevealedMetric(val metric: String, val value: Double)

@Serializable
public sealed interface RemoteOpponentView {
    @Serializable
    @SerialName("faceDown")
    public data object FaceDown : RemoteOpponentView

    @Serializable
    @SerialName("contested")
    public data class Contested(val revealed: List<RemoteRevealedMetric>) : RemoteOpponentView

    @Serializable
    @SerialName("revealed")
    public data class Revealed(val card: RemoteCardFace) : RemoteOpponentView
}

@Serializable
public sealed interface RemoteRoundState {
    @Serializable
    @SerialName("awaitingChoice")
    public data class AwaitingChoice(
        val chooser: String,
        val remainingMetrics: List<String>,
        val revealedMetrics: List<String>,
    ) : RemoteRoundState

    @Serializable
    @SerialName("resolved")
    public data class Resolved(
        val chooser: String,
        val winner: String?,
        val decidingMetric: String,
        val resolution: String,
        val revealedMetrics: List<String>,
    ) : RemoteRoundState
}

@Serializable
public sealed interface MatchView {
    public val revision: Long
    public val myScore: Int
    public val opponentScore: Int
    public val myPile: List<RemoteCardFace>

    @Serializable
    @SerialName("inProgress")
    public data class InProgress(
        override val revision: Long,
        val self: RemoteCardFace,
        val opponent: RemoteOpponentView,
        val round: RemoteRoundState,
        val metrics: List<RemoteMetricSpec>,
        override val myScore: Int,
        override val opponentScore: Int,
        val roundNumber: Int,
        val totalRounds: Int,
        override val myPile: List<RemoteCardFace>,
    ) : MatchView

    @Serializable
    @SerialName("finished")
    public data class Finished(
        override val revision: Long,
        val winner: String?,
        override val myScore: Int,
        override val opponentScore: Int,
        override val myPile: List<RemoteCardFace>,
        /** [com.toptrumps.rules.PlayerView.Finished.cardsWonWith]'s wire twin — see [toMatchSummary]. */
        val cardsWonWith: List<RemoteCardFace>,
    ) : MatchView
}

/** Shared by every `-> RemoteCardFace` mapper, in this file and [HostMatchSession]'s guest-hand one — the wire's stat-map shape in one place. */
internal fun Map<MetricKey, StatValue>.toRemoteStats(): Map<String, Double> =
    mapKeys { it.key.id }.mapValues { it.value.raw }

private fun CardFace.toRemote(): RemoteCardFace = RemoteCardFace(id, name, stats.toRemoteStats(), imageFile)

public fun PlayerView.toMatchView(): MatchView = when (this) {
    is PlayerView.Finished -> MatchView.Finished(
        revision = revision,
        winner = winner?.name,
        myScore = myScore,
        opponentScore = opponentScore,
        myPile = myPile.map { it.toRemote() },
        cardsWonWith = cardsWonWith.map { it.toRemote() },
    )
    is PlayerView.InProgress -> MatchView.InProgress(
        revision = revision,
        self = self.toRemote(),
        opponent = when (val opponentView = opponent) {
            is OpponentCardView.FaceDown -> RemoteOpponentView.FaceDown
            is OpponentCardView.Contested -> RemoteOpponentView.Contested(
                opponentView.revealed.map { RemoteRevealedMetric(it.metric.id, it.value.raw) },
            )
            is OpponentCardView.Revealed -> RemoteOpponentView.Revealed(opponentView.card.toRemote())
        },
        round = when (val roundState = round) {
            is RoundState.AwaitingChoice -> RemoteRoundState.AwaitingChoice(
                chooser = roundState.chooser.name,
                remainingMetrics = roundState.remainingMetrics.map { it.id },
                revealedMetrics = roundState.revealedMetrics.map { it.id },
            )
            is RoundState.Resolved -> RemoteRoundState.Resolved(
                chooser = roundState.chooser.name,
                winner = roundState.winner?.name,
                decidingMetric = roundState.decidingMetric.id,
                resolution = roundState.resolution.name,
                revealedMetrics = roundState.revealedMetrics.map { it.id },
            )
        },
        // .direction here is the *displayed* win direction, not necessarily the raw stored-value
        // direction — see MetricSpec.displayDirection(). YEARS_SINCE_VALUE is order-reversing, so
        // a client rendering "(higher wins)"/"(lower wins)" next to the shown number needs this,
        // not the engine's raw direction.
        metrics = metrics.map { RemoteMetricSpec(it.key.id, it.label, it.unit, it.displayDirection().name, it.display.name) },
        myScore = myScore,
        opponentScore = opponentScore,
        roundNumber = roundNumber,
        totalRounds = totalRounds,
        myPile = myPile.map { it.toRemote() },
    )
}

private fun RemoteCardFace.toLocal(): CardFace = CardFace(id, name, stats.mapKeys { MetricKey(it.key) }.mapValues { StatValue(it.value) }, imageFile)

/**
 * The one place a [MatchView.Finished] becomes a [MatchSummary] — used identically by
 * [HostMatchSession] and [GuestMatchSession], since both hold a [MatchView] rather than a
 * [PlayerView] by the time a match ends (see [HostMatchSession]'s `_view`). [mySeat] resolves
 * [MatchView.Finished.winner] (an absolute seat, "HOST"/"GUEST") against the seat this session
 * plays.
 */
internal fun MatchView.Finished.toMatchSummary(mySeat: Seat): MatchSummary = MatchSummary(
    result = when (winner) {
        null -> MatchResult.DRAW
        mySeat.name -> MatchResult.WIN
        else -> MatchResult.LOSS
    },
    myScore = myScore,
    opponentScore = opponentScore,
    cardsWon = cardsWonWith.map { it.toLocal() },
)
