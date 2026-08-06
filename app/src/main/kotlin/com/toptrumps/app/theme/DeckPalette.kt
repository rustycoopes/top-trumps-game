package com.toptrumps.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.toptrumps.rules.ArgbColor
import com.toptrumps.rules.DeckTheme

/**
 * [ArgbColor] → Compose [Color] — total, not partial: every [ArgbColor] that reaches `:app` has
 * already passed [com.toptrumps.decks.DeckLoader]'s validation (a malformed manifest hex degrades
 * to [com.toptrumps.rules.DeckTheme.DEFAULT] there, long before this conversion runs), so there is
 * no invalid-state branch to handle here. See the deck-theme-block ADR.
 */
public fun ArgbColor.toComposeColor(): Color = Color(argb)

/**
 * The card composable's only source of colour and background image — an explicit parameter, never
 * a `CompositionLocal`, so `TrumpCard`/`TrumpCardBack` can never reach for `MaterialTheme.colorScheme`
 * by accident (card-visual-identity TDD decision 1: story 20's dark-mode invariance depends on
 * this staying structurally impossible to break). `public`, not `internal`: [MatchScreen]'s own
 * `public` signature takes one directly (its call sites resolve the deck's theme before this
 * screen is reached, per card-visual-identity TDD decision 6), and a public function can't expose
 * an internal parameter type. [backgroundImage] rides along here rather than as a separate
 * parameter threaded through every screen — see the themed-card-backgrounds ADR — so it's `null`
 * (no background) at every call site by construction unless the deck's manifest set one.
 */
@Immutable
public data class CardPalette(val accent: Color, val onAccent: Color, val backgroundImage: String? = null)

internal fun DeckTheme.toCardPalette(): CardPalette =
    CardPalette(accent.toComposeColor(), onAccent.toComposeColor(), backgroundImage)
