package com.toptrumps.app.card

import com.toptrumps.session.RemoteCardFace
import com.toptrumps.session.RemoteMetricSpec
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [cardContentOf] is plain `Dp`-free arithmetic over data classes — no Compose runtime needed, so
 * this runs as an ordinary JVM test rather than through Robolectric. Closes the gap the TDD calls
 * out: `formatStat`'s `YEARS_SINCE_VALUE` branch previously had no test independent of a live clock.
 */
class CardContentTest {

    // Midyear, not new year's — keeps `yearsSince`'s local-timezone conversion from landing on a
    // different calendar year depending on the JVM's default timezone.
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-06-15T12:00:00Z")
    }

    private val metrics = listOf(
        RemoteMetricSpec("topSpeed", "TOP SPEED", "MPH", "HIGH_WINS"),
        RemoteMetricSpec("weight", "WEIGHT", "KG", "LOW_WINS"),
        RemoteMetricSpec("year", "AGE", "YEARS", "LOW_WINS", "YEARS_SINCE_VALUE"),
    )

    private val card = RemoteCardFace(
        id = "x",
        name = "Test Card",
        stats = mapOf("topSpeed" to 186.0, "weight" to 200.0, "year" to 2016.0),
    )

    @Test
    fun `maps a wire card and its metrics into pre formatted rows`() {
        val content = cardContentOf(card, metrics, now = fixedClock)

        assertEquals("Test Card", content.title)
        assertEquals(3, content.rows.size)
        val topSpeed = content.rows.first { it.metricKey == "topSpeed" }
        assertEquals("186", topSpeed.value)
        assertEquals(RowDirection.HIGHER_WINS, topSpeed.direction)
        val weight = content.rows.first { it.metricKey == "weight" }
        assertEquals(RowDirection.LOWER_WINS, weight.direction)
    }

    @Test
    fun `years since is derived from the injected clock, not Clock System`() {
        val content = cardContentOf(card, metrics, now = fixedClock)

        val age = content.rows.first { it.metricKey == "year" }.value
        assertEquals("10", age) // 2026 - 2016, per fixedClock — never drifts with the real calendar
    }

    @Test
    fun `every row is enabled when availableMetrics is null`() {
        val content = cardContentOf(card, metrics, now = fixedClock)

        assertEquals(3, content.rows.count { it.enabled })
    }

    @Test
    fun `a metric outside availableMetrics disables its row`() {
        val content = cardContentOf(card, metrics, now = fixedClock, availableMetrics = setOf("topSpeed"))

        assertEquals(true, content.rows.first { it.metricKey == "topSpeed" }.enabled)
        assertEquals(false, content.rows.first { it.metricKey == "weight" }.enabled)
    }

    @Test
    fun `only the deciding metric carries an outcome and a comparison value`() {
        val content = cardContentOf(
            card = card,
            metrics = metrics,
            now = fixedClock,
            comparison = mapOf("topSpeed" to 299.0, "weight" to 216.0, "year" to 2015.0),
            decidingMetric = "topSpeed",
            decidedOutcome = RowOutcome.LOSE,
        )

        val topSpeed = content.rows.first { it.metricKey == "topSpeed" }
        assertEquals(RowOutcome.LOSE, topSpeed.outcome)
        assertEquals("299", topSpeed.comparisonValue)

        val weight = content.rows.first { it.metricKey == "weight" }
        assertNull(weight.outcome)
        assertEquals("216", weight.comparisonValue)
    }
}
