package com.toptrumps.rules

/** Identifies a metric (e.g. "topSpeed"). Never switched on by name in [RulesEngine]. */
@JvmInline
public value class MetricKey(public val id: String)

public enum class Direction { HIGH_WINS, LOW_WINS }

/** A single numeric stat value. One representation covers every metric this slice needs. */
@JvmInline
public value class StatValue(public val raw: Double)

/**
 * How a metric's stored value is rendered. [RAW] shows the stored value as-is. [YEARS_SINCE_VALUE]
 * treats the stored value as a calendar year and renders "years since" it (see [yearsSince]) —
 * this is how "Age" is derived from a model year without the engine ever seeing a clock.
 */
public enum class MetricDisplay { RAW, YEARS_SINCE_VALUE }

/** Manifest-supplied metadata for a metric — label, unit and win direction all come from here. */
public data class MetricSpec(
    val key: MetricKey,
    val label: String,
    val unit: String,
    val direction: Direction,
    val display: MetricDisplay = MetricDisplay.RAW,
)

/**
 * The win direction as it applies to what's actually *displayed*. [MetricDisplay.YEARS_SINCE_VALUE]
 * is order-reversing (a smaller stored year is a larger displayed age), so [direction] — which the
 * engine applies to the *stored* value — reads backwards to a player looking at the rendered
 * number. A metric stored as `LOW_WINS` year with a `YEARS_SINCE_VALUE` display is exactly
 * "higher age wins", per TDD §7.
 */
public fun MetricSpec.displayDirection(): Direction = when (display) {
    MetricDisplay.RAW -> direction
    MetricDisplay.YEARS_SINCE_VALUE -> when (direction) {
        Direction.HIGH_WINS -> Direction.LOW_WINS
        Direction.LOW_WINS -> Direction.HIGH_WINS
    }
}

/** Per-card image, recorded with its licence attribution from day one — see the PRD's deck format. */
public data class CardImage(
    val file: String,
    val licence: String,
    val author: String,
    val sourceUrl: String,
)

public enum class Seat { HOST, GUEST }

public fun Seat.opponent(): Seat = if (this == Seat.HOST) Seat.GUEST else Seat.HOST
