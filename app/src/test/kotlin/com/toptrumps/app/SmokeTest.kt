package com.toptrumps.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Throwaway spike for card-visual-identity slice 1 (issue #26) — proves JUnit 4 + Robolectric +
 * Compose UI testing work under this repo's toolchain before any card code depends on it.
 * Delete once slice 3's real card tests land.
 */
@RunWith(RobolectricTestRunner::class)
public class SmokeTest {

    @get:Rule
    public val composeTestRule = createComposeRule()

    @Test
    public fun textRenders() {
        composeTestRule.setContent {
            Text("Hello, Top Trumps")
        }

        composeTestRule.onNodeWithText("Hello, Top Trumps").assertExists()
    }
}
