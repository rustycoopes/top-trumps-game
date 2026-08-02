package com.toptrumps.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
)

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
)

/**
 * The app's one [MaterialTheme] wrap, applied once around the whole nav graph in `MainActivity`.
 * Follows the system dark-mode setting; no in-app override exists yet. A card's own colours never
 * come from here — see [com.toptrumps.rules.DeckTheme] and `DeckPalette.kt` — this is chrome only:
 * app bars, buttons, screen backgrounds.
 */
@Composable
public fun TopTrumpsTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = TopTrumpsTypography, content = content)
}
