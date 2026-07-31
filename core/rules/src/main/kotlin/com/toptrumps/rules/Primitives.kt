package com.toptrumps.rules

/** Identifies a metric (e.g. "topSpeed"). Never switched on by name in [RulesEngine]. */
@JvmInline
public value class MetricKey(public val id: String)

public enum class Direction { HIGH_WINS, LOW_WINS }

/** A single numeric stat value. One representation covers every metric this slice needs. */
@JvmInline
public value class StatValue(public val raw: Double)

/** Manifest-supplied metadata for a metric — label, unit and win direction all come from here. */
public data class MetricSpec(
    val key: MetricKey,
    val label: String,
    val unit: String,
    val direction: Direction,
)

public enum class Seat { HOST, GUEST }

public fun Seat.opponent(): Seat = if (this == Seat.HOST) Seat.GUEST else Seat.HOST
