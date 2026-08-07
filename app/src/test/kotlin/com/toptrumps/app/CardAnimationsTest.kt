package com.toptrumps.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [FlippableCard]'s tap-gated start (issue #47) — the opponent's own auto-start use (`started`
 * left at its default) is exercised implicitly by every existing on-device reveal and isn't
 * re-proven here; this covers the new contract the hero card depends on: the flip never begins,
 * and [FlippableCard.onRevealed] never fires, until the caller flips `started` to `true`.
 */
@RunWith(RobolectricTestRunner::class)
class CardAnimationsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val stubFace: @Composable (Modifier) -> Unit = { modifier -> Box(modifier.size(100.dp).background(Color.Gray)) }

    @Test
    fun `card stays unrevealed while started is false`() {
        var revealedCount = 0
        composeTestRule.setContent {
            FlippableCard(skipAnimation = false, started = false, onRevealed = { revealedCount++ }, back = stubFace, front = stubFace)
        }

        composeTestRule.waitForIdle()
        assertEquals(0, revealedCount)
    }

    @Test
    fun `card flips and reveals once started turns true`() {
        var revealedCount = 0
        val started = mutableStateOf(false)
        composeTestRule.setContent {
            FlippableCard(skipAnimation = false, started = started.value, onRevealed = { revealedCount++ }, back = stubFace, front = stubFace)
        }
        composeTestRule.waitForIdle()
        assertEquals(0, revealedCount)

        composeTestRule.runOnIdle { started.value = true }
        composeTestRule.waitForIdle()

        assertEquals(1, revealedCount)
    }

    @Test
    fun `skipAnimation still waits for started before revealing`() {
        var revealedCount = 0
        val started = mutableStateOf(false)
        composeTestRule.setContent {
            FlippableCard(skipAnimation = true, started = started.value, onRevealed = { revealedCount++ }, back = stubFace, front = stubFace)
        }
        composeTestRule.waitForIdle()
        assertEquals(0, revealedCount)

        composeTestRule.runOnIdle { started.value = true }
        composeTestRule.waitForIdle()

        assertEquals(1, revealedCount)
    }
}
