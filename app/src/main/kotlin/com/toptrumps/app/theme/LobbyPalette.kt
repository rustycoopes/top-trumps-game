package com.toptrumps.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.toptrumps.rules.DeckTheme

/**
 * The lobby's own colour source — "The Pack" direction (second-round-updates slice 2, signed off
 * against three mocked-up directions). A bare object, not an explicit parameter like [CardPalette]:
 * unlike a card's theme, which varies per deck and must be threaded through, the lobby has exactly
 * one visual direction with nothing to inject, so there's no per-call value for a parameter to
 * carry. What it does share with [CardPalette] is the *reason* it's not `MaterialTheme.colorScheme`
 * — so the lobby's warm/parchment identity can't drift the moment someone reaches for the ambient
 * theme instead. [accent]/[onAccent] are literally [DeckTheme.DEFAULT] — the app's own classic
 * yellow-on-ink default deck theme — rather than a new colour invented for this screen, per the
 * WBS's "extend, don't replace" requirement.
 */
@Immutable
public object LobbyPalette {
    public val accent: Color = DeckTheme.DEFAULT.accent.toComposeColor()
    public val onAccent: Color = DeckTheme.DEFAULT.onAccent.toComposeColor()
    public val background: Color = Color(0xFFFBF3DE)
    public val surface: Color = Color(0xFFFFFAF0)
    public val ink: Color = onAccent
    public val inkDim: Color = Color(0xFF8A7C5C)
    public val divider: Color = Color(0xFFE9DCB8)
}
