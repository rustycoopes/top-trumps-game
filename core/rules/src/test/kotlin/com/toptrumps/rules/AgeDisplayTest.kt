package com.toptrumps.rules

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

class AgeDisplayTest {

    @Test
    fun `years-since is a plain calendar-year subtraction`() {
        val clock = FixedClock(Instant.parse("2030-06-15T00:00:00Z"))
        assertEquals(30, yearsSince(2000, clock))
    }

    @Test
    fun `which of two model years is older is independent of the current date`() {
        val older = 1956
        val newer = 1994

        val clockA = FixedClock(Instant.parse("2026-01-01T00:00:00Z"))
        val clockB = FixedClock(Instant.parse("2099-01-01T00:00:00Z"))

        for (clock in listOf(clockA, clockB)) {
            assertTrue(
                yearsSince(older, clock) > yearsSince(newer, clock),
                "the older model year must always report a larger derived age, regardless of 'now'",
            )
        }
    }

    @Test
    fun `LOW_WINS on the stored year agrees with HIGH_WINS on the displayed age`() {
        val spec = MetricSpec(MetricKey("year"), "Age", "years", Direction.LOW_WINS, MetricDisplay.YEARS_SINCE_VALUE)
        assertEquals(Direction.HIGH_WINS, spec.displayDirection())
    }

    @Test
    fun `a RAW display keeps the same direction as stored`() {
        val spec = MetricSpec(MetricKey("topSpeed"), "Top Speed", "mph", Direction.HIGH_WINS)
        assertEquals(Direction.HIGH_WINS, spec.displayDirection())
    }
}
