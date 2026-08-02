package com.toptrumps.app.card

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure `Dp` maths — no Compose runtime, no Robolectric, per the card-geometry ADR's "third test
 * seam" and the WBS's call for this to land before Slice 3's Robolectric work starts.
 */
class CardGeometryTest {

    @Test
    fun `below 400dp the row floor binds and the card grows past 2 to 3`() {
        // 0.12 * 380 = 45.6dp, below the 48dp hero floor -> floor binds.
        val geometry = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp)

        assertEquals(48.dp, geometry.rowHeight)
        assertEquals(240.dp, geometry.tableHeight) // 5 * 48dp
        assertEquals(57f, geometry.bannerHeight.value, 0.01f) // 0.15 * 380 (Float rounding, not exactly 57.0f)
        assertEquals(285.dp, geometry.imageHeight) // 0.75 * 380, nominal, unabsorbed
        assertEquals(582.dp, geometry.height) // 0.9*380 + 5*48 = 342 + 240
        assertTrue("grown card must exceed the nominal 2:3 height of ${380 * 1.5}dp", geometry.height.value > 380f * 1.5f)
    }

    @Test
    fun `above 400dp the nominal row wins and the card lands at exactly 2 to 3`() {
        // 0.12 * 500 = 60dp, at or above the 48dp hero floor -> floor does not bind.
        val geometry = cardGeometry(width = 500.dp, variant = CardVariant.HERO, minRowHeight = 48.dp)

        assertEquals(60.dp, geometry.rowHeight)
        assertEquals(750.dp, geometry.height) // 1.5 * 500, exactly 2:3
        assertEquals(375.dp, geometry.imageHeight) // 0.75 * 500
    }

    @Test
    fun `at the crossover width both branches agree`() {
        // minRowHeight / 0.12 = 48 / 0.12 = 400dp exactly.
        val geometry = cardGeometry(width = 400.dp, variant = CardVariant.HERO, minRowHeight = 48.dp)

        assertEquals(48.dp, geometry.rowHeight)
        assertEquals(600.dp, geometry.height) // 0.9*400 + 5*48 = 1.5*400, both formulas agree
    }

    @Test
    fun `an explicit maxHeight ceiling makes the image window absorb instead of growing`() {
        // Unconstrained this would be 582dp tall (see the floor-binding case above); cap it lower.
        val geometry = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp, maxHeight = 550.dp)

        assertEquals(550.dp, geometry.height)
        assertEquals(48.dp, geometry.rowHeight) // the row floor is never sacrificed
        assertEquals(253.dp, geometry.imageHeight) // 550 - 57(banner) - 240(table), below the nominal 285dp
    }

    @Test
    fun `two calls with identical inputs are equal, giving front and back pixel identity`() {
        val front = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp)
        val back = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp)

        assertEquals(front, back)
    }

    @Test
    fun `imageZoneHeight reallocates tableHeight into the image window only for the mini variant`() {
        // Slice 3's TrumpCard renders no stat table for MINI, giving that space to the image
        // window instead — AssetTrumpCard's Coil decode-size request must agree, or mini cards
        // silently decode at less than their rendered resolution (found and fixed during slice 3).
        val mini = cardGeometry(width = 96.dp, variant = CardVariant.MINI)
        assertEquals(mini.imageHeight + mini.tableHeight, mini.imageZoneHeight)

        val hero = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp)
        assertEquals(hero.imageHeight, hero.imageZoneHeight)
    }

    @Test
    fun `solveCardWidth is width-bound on a phone-sized budget`() {
        // A tall, narrow budget: width is the binding constraint, not height.
        val width = solveCardWidth(maxWidth = 300.dp, maxHeight = 1000.dp, minRowHeight = 48.dp)

        assertEquals(300.dp, width)
    }

    @Test
    fun `solveCardWidth is height-bound on a large screen and lands at exactly 2 to 3`() {
        // A generous width budget with a moderate height cap: height binds, and since the
        // resulting width is above the 400dp crossover, the floor doesn't re-bind either.
        val width = solveCardWidth(maxWidth = 2000.dp, maxHeight = 900.dp, minRowHeight = 48.dp)

        assertEquals(600.dp, width) // 900 / 1.5
        val geometry = cardGeometry(width = width, variant = CardVariant.HERO, minRowHeight = 48.dp)
        assertEquals(900.dp, geometry.height) // exactly 2:3 at 600x900
    }

    @Test
    fun `solveCardWidth accounts for the row floor when height alone would bind`() {
        // A tight height budget below the crossover height (600dp at a 48dp floor) forces the
        // floor-bound inverse, not the naive height / 1.5 one.
        val width = solveCardWidth(maxWidth = 1000.dp, maxHeight = 500.dp, minRowHeight = 48.dp)

        // (500 - 5*48) / 0.9 = 260 / 0.9
        assertEquals((260.0 / 0.9).toFloat(), width.value, 0.01f)
    }
}
