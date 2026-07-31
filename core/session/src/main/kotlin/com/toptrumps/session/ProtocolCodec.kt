package com.toptrumps.session

import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.PlayerIntent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal sealed interface WireIntent {
    @Serializable
    @SerialName("chooseMetric")
    data class ChooseMetric(val metricKey: String) : WireIntent

    @Serializable
    @SerialName("advanceRound")
    data object AdvanceRound : WireIntent
}

/** `WireMessage <-> ByteArray`. The one thing every byte crossing a [Transport] passes through. */
public object ProtocolCodec {
    private val json = Json { ignoreUnknownKeys = true }

    public fun encodeIntent(intent: PlayerIntent): ByteArray {
        val wire: WireIntent = when (intent) {
            is PlayerIntent.ChooseMetric -> WireIntent.ChooseMetric(intent.metric.id)
            is PlayerIntent.AdvanceRound -> WireIntent.AdvanceRound
        }
        return json.encodeToString(WireIntent.serializer(), wire).encodeToByteArray()
    }

    public fun decodeIntent(bytes: ByteArray): PlayerIntent =
        when (val wire = json.decodeFromString(WireIntent.serializer(), bytes.decodeToString())) {
            is WireIntent.ChooseMetric -> PlayerIntent.ChooseMetric(MetricKey(wire.metricKey))
            is WireIntent.AdvanceRound -> PlayerIntent.AdvanceRound
        }

    public fun encodeView(view: MatchView): ByteArray =
        json.encodeToString(MatchView.serializer(), view).encodeToByteArray()

    public fun decodeView(bytes: ByteArray): MatchView =
        json.decodeFromString(MatchView.serializer(), bytes.decodeToString())
}
