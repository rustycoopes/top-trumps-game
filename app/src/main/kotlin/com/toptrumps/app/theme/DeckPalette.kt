package com.toptrumps.app.theme

import androidx.compose.ui.graphics.Color
import com.toptrumps.rules.ArgbColor

/**
 * [ArgbColor] → Compose [Color] — total, not partial: every [ArgbColor] that reaches `:app` has
 * already passed [com.toptrumps.decks.DeckLoader]'s validation (a malformed manifest hex degrades
 * to [com.toptrumps.rules.DeckTheme.DEFAULT] there, long before this conversion runs), so there is
 * no invalid-state branch to handle here. See the deck-theme-block ADR.
 */
public fun ArgbColor.toComposeColor(): Color = Color(argb)
