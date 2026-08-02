package com.toptrumps.app.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.toptrumps.app.theme.CardPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [TrumpCard]'s interaction contract — PRD "Seam 2" / TDD "Seam C" — the first Compose test of any
 * kind in this repo (Slice 1 proved the toolchain with a throwaway `SmokeTest`, now deleted). The
 * image slot is a stub `Box`, per the card-image-slot ADR: no Coil on the test classpath.
 */
@RunWith(RobolectricTestRunner::class)
class TrumpCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val palette = CardPalette(accent = Color(0xFFFFC400), onAccent = Color.Black)
    private val stubImage: @Composable (Modifier) -> Unit = { modifier -> Box(modifier.background(Color.Gray)) }

    private fun statRow(key: String, enabled: Boolean = true, outcome: RowOutcome? = null) = StatRow(
        metricKey = key,
        label = key.uppercase(),
        unit = "u",
        value = "1",
        direction = RowDirection.HIGHER_WINS,
        enabled = enabled,
        outcome = outcome,
    )

    @Test
    fun `enabled row is clickable and invokes the callback with its metric key`() {
        val content = CardContent(title = "Card", rows = listOf(statRow("topSpeed")))
        var chosen: String? = null
        composeTestRule.setContent {
            TrumpCard(
                content = content,
                palette = palette,
                geometry = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp),
                onChooseStat = { chosen = it },
                image = stubImage,
            )
        }

        composeTestRule.onNodeWithTag("stat-row-topSpeed").assertHasClickAction().performClick()
        assertEquals("topSpeed", chosen)
    }

    @Test
    fun `disabled row is exposed as disabled and tapping it does not invoke the callback`() {
        val content = CardContent(title = "Card", rows = listOf(statRow("topSpeed", enabled = false)))
        var invoked = false
        composeTestRule.setContent {
            TrumpCard(
                content = content,
                palette = palette,
                geometry = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp),
                onChooseStat = { invoked = true },
                image = stubImage,
            )
        }

        // Compose UI tests drive the semantics OnClick action directly, bypassing the real touch
        // gesture's own enabled gate — assertIsNotEnabled() proves the a11y-visible disabled state;
        // performClick() proves the callback itself won't fire even via that direct route (see
        // StatRowView's doc).
        val node = composeTestRule.onNodeWithTag("stat-row-topSpeed")
        node.assertIsNotEnabled()
        node.performClick()
        assertFalse(invoked)
    }

    @Test
    fun `mini variant exposes zero clickable rows`() {
        val content = CardContent(title = "Card", rows = listOf(statRow("topSpeed"), statRow("power")))
        composeTestRule.setContent {
            TrumpCard(
                content = content,
                palette = palette,
                geometry = cardGeometry(width = 96.dp, variant = CardVariant.MINI),
                onChooseStat = { error("mini must never invoke onChooseStat") },
                image = stubImage,
            )
        }

        composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun `a read-only full variant exposes zero clickable rows`() {
        // Same shape as the mini test above, but at hero geometry with onChooseStat omitted — the
        // pile/reveal/gallery case. "null onChooseStat means read-only" is a named invariant on
        // TrumpCard itself; this is the full-variant half of proving it holds.
        val content = CardContent(title = "Card", rows = listOf(statRow("topSpeed"), statRow("power")))
        composeTestRule.setContent {
            TrumpCard(
                content = content,
                palette = palette,
                geometry = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp),
                image = stubImage,
            )
        }

        composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun `after a reveal only the decided row carries a win lose or tie stateDescription`() {
        val content = CardContent(
            title = "Card",
            rows = listOf(
                statRow("topSpeed", outcome = RowOutcome.WIN),
                statRow("power"),
                statRow("weight"),
                statRow("year"),
                statRow("price"),
            ),
        )
        composeTestRule.setContent {
            TrumpCard(
                content = content,
                palette = palette,
                geometry = cardGeometry(width = 190.dp, variant = CardVariant.REVEAL, minRowHeight = 28.dp),
                // Read-only at the reveal — no onChooseStat.
                image = stubImage,
            )
        }

        composeTestRule.onNodeWithTag("stat-row-topSpeed")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "WIN"))
        listOf("power", "weight", "year", "price").forEach { key ->
            composeTestRule.onNodeWithTag("stat-row-$key")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        }
    }
}
