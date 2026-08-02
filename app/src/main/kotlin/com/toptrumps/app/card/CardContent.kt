package com.toptrumps.app.card

import androidx.compose.runtime.Immutable
import com.toptrumps.rules.yearsSince
import com.toptrumps.session.RemoteCardFace
import com.toptrumps.session.RemoteMetricSpec
import kotlinx.datetime.Clock

/** Which displayed value wins a row — [RemoteMetricSpec.direction] is already the *displayed* direction (`toMatchView` applies `displayDirection()` before it reaches the wire), so this is a plain mapping, not a flip. */
internal enum class RowDirection { HIGHER_WINS, LOWER_WINS }

/** The reveal outcome for the one row that decided a round; every other row carries `null`. */
internal enum class RowOutcome { WIN, LOSE, TIE }

/**
 * One stat row, pre-formatted for direct display. [CardContent] holds a `List` of these, so both
 * are marked [Immutable] — they live in `com.toptrumps.app` (Compose-compiled), so the annotation
 * is the right lever rather than another `compose-stability-config.conf` entry.
 */
@Immutable
internal data class StatRow(
    val metricKey: String,
    val label: String,
    val unit: String,
    val value: String,
    val direction: RowDirection,
    val enabled: Boolean = true,
    /** The opponent's formatted value at this same metric — non-null only at the reveal. */
    val comparisonValue: String? = null,
    /** Non-null only for the one row that decided the round. */
    val outcome: RowOutcome? = null,
)

@Immutable
internal data class CardContent(val title: String, val rows: List<StatRow>)

/**
 * The one place [RemoteMetricSpec.display]'s `YEARS_SINCE_VALUE` branch is evaluated against a
 * clock, so [TrumpCard] itself never calls `Clock.System` — otherwise both `@Preview`s and the
 * Robolectric test would be date-dependent (card-visual-identity TDD decision 1).
 *
 * [availableMetrics] is `null` for every read-only context (reveal, pile, gallery) — every row
 * renders enabled. A hero card's caller passes `round.remainingMetrics` so a tied-and-excluded row
 * disables itself. [comparison]/[decidingMetric]/[decidedOutcome] are only non-null at the reveal.
 */
internal fun cardContentOf(
    card: RemoteCardFace,
    metrics: List<RemoteMetricSpec>,
    now: Clock = Clock.System,
    availableMetrics: Set<String>? = null,
    comparison: Map<String, Double>? = null,
    decidingMetric: String? = null,
    decidedOutcome: RowOutcome? = null,
): CardContent {
    val rows = metrics.map { spec ->
        val raw = card.stats.getValue(spec.key)
        StatRow(
            metricKey = spec.key,
            label = spec.label,
            unit = spec.unit,
            value = formatStat(spec, raw, now),
            direction = if (spec.direction == "HIGH_WINS") RowDirection.HIGHER_WINS else RowDirection.LOWER_WINS,
            enabled = availableMetrics == null || spec.key in availableMetrics,
            comparisonValue = comparison?.get(spec.key)?.let { formatStat(spec, it, now) },
            outcome = if (spec.key == decidingMetric) decidedOutcome else null,
        )
    }
    return CardContent(title = card.name, rows = rows)
}

/** Mirrors `MatchScreen.kt`'s private `formatStat`, parameterised on [now] instead of reaching for `Clock.System` directly. */
private fun formatStat(spec: RemoteMetricSpec, raw: Double, now: Clock): String = when (spec.display) {
    "YEARS_SINCE_VALUE" -> yearsSince(raw.toInt(), now).toString()
    else -> if (raw == raw.toInt().toDouble()) raw.toInt().toString() else raw.toString()
}
