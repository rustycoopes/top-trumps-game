package com.toptrumps.session

import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.PlayerIntent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class WireIntent(val metricKey: String)

/** `WireMessage <-> ByteArray`. The one thing every byte crossing a [Transport] passes through. */
public object ProtocolCodec {
    private val json = Json { ignoreUnknownKeys = true }

    public fun encodeIntent(intent: PlayerIntent.ChooseMetric): ByteArray =
        json.encodeToString(WireIntent.serializer(), WireIntent(intent.metric.id)).encodeToByteArray()

    public fun decodeIntent(bytes: ByteArray): PlayerIntent.ChooseMetric {
        val wire = json.decodeFromString(WireIntent.serializer(), bytes.decodeToString())
        return PlayerIntent.ChooseMetric(MetricKey(wire.metricKey))
    }

    public fun encodeView(view: MatchView): ByteArray =
        json.encodeToString(MatchView.serializer(), view).encodeToByteArray()

    public fun decodeView(bytes: ByteArray): MatchView =
        json.decodeFromString(MatchView.serializer(), bytes.decodeToString())
}
