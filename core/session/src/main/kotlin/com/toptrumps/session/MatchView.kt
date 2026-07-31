package com.toptrumps.session

import com.toptrumps.rules.OpponentCardView
import com.toptrumps.rules.PlayerView
import com.toptrumps.rules.RoundState
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
public data class RemoteMetricSpec(val key: String, val label: String, val unit: String, val direction: String)

@Serializable
public data class RemoteCardFace(val id: String, val name: String, val stats: Map<String, Double>)

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
    public data class AwaitingChoice(val chooser: String) : RemoteRoundState

    @Serializable
    @SerialName("resolved")
    public data class Resolved(val decidingMetric: String, val winner: String?) : RemoteRoundState
}

@Serializable
public data class MatchView(
    val revision: Long,
    val self: RemoteCardFace,
    val opponent: RemoteOpponentView,
    val round: RemoteRoundState,
    val metrics: List<RemoteMetricSpec>,
)

public fun PlayerView.toMatchView(): MatchView = MatchView(
    revision = revision,
    self = RemoteCardFace(self.id, self.name, self.stats.mapKeys { it.key.id }.mapValues { it.value.raw }),
    opponent = when (val opponentView = opponent) {
        is OpponentCardView.FaceDown -> RemoteOpponentView.FaceDown
        is OpponentCardView.Contested -> RemoteOpponentView.Contested(
            opponentView.revealed.map { RemoteRevealedMetric(it.metric.id, it.value.raw) },
        )
        is OpponentCardView.Revealed -> RemoteOpponentView.Revealed(
            RemoteCardFace(
                opponentView.card.id,
                opponentView.card.name,
                opponentView.card.stats.mapKeys { it.key.id }.mapValues { it.value.raw },
            ),
        )
    },
    round = when (val roundState = round) {
        is RoundState.AwaitingChoice -> RemoteRoundState.AwaitingChoice(roundState.chooser.name)
        is RoundState.Resolved -> RemoteRoundState.Resolved(roundState.decidingMetric.id, roundState.winner?.name)
    },
    metrics = metrics.map { RemoteMetricSpec(it.key.id, it.label, it.unit, it.direction.name) },
)
