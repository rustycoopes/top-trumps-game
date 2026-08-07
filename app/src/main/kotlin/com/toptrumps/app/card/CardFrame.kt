package com.toptrumps.app.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.toptrumps.app.theme.CardPalette

/** Rounded corners on every card, front or back — physicality per the PRD, not per-deck. */
internal val CardCornerRadius = 16.dp

/**
 * A permanent light hairline, not a colour that varies with [CardPalette] — TDD §7's dark-mode
 * fix: the printed edge must read against both a light and a dark surround, so it can't be left to
 * vary the way the invariance claim would otherwise suggest.
 */
private val CardHairlineColor = Color.White.copy(alpha = 0.6f)
private val CardHairlineWidth = 1.dp

/** The white inner border framing the image window — physicality per the PRD. */
internal val CardImageBorderColor = Color.White
internal val CardImageBorderWidth = 2.dp

/** Static elevation for a card shown at rest — never applied mid-flip/mid-slide, see the themed-card-backgrounds ADR. */
private val CardElevation = 4.dp

/**
 * Fixed, not per-deck-configurable — a consistent faded look everywhere, per the themed-card-backgrounds
 * ADR. Raised from the original 0.25 (card-visual-identity #46 follow-up): the banner/stat-table are
 * the only zones a background is ever visible through at all (the image zone's own opaque per-card
 * photo covers the rest), and those zones additionally sit under [CardTextScrimAlpha]'s 0.85 scrim —
 * so the *actually rendered* image contribution there is `(1 - CardTextScrimAlpha) * BackgroundImageAlpha`,
 * not this value directly. At the original 0.25 that worked out to under 4% of the pixel colour,
 * effectively invisible on a real screen. [CardTextScrimAlpha] is left untouched since it's what holds
 * the 4.5:1 text-contrast bar regardless of the image underneath.
 */
private const val BackgroundImageAlpha = 0.7f

/**
 * Alpha for the scrim [TrumpCard] lays over the background image behind the title banner and stat
 * table, to hold the existing 4.5:1 contrast bar — `palette.accent.copy(alpha = CardTextScrimAlpha)`.
 * Compositing this over the *same* [CardPalette.accent] the box is already filled with is a visual
 * no-op when there's no background image, so [TrumpCard] applies it unconditionally rather than
 * threading a "has a background" flag down to the banner/table.
 */
internal const val CardTextScrimAlpha = 0.85f

/**
 * The outer card box every front/back shares: fixed to [geometry]'s size, [palette]'s accent fill,
 * rounded corners, hairline edge, and an optional faded [background] image (themed-card-backgrounds
 * ADR). Two calls with the same [geometry] instance produce the same size, which is what makes
 * front/back pixel identity structural (see [CardGeometry]'s doc). [withShadow] is only ever true
 * for a card shown at rest — never mid-flip/mid-slide, where a per-frame `Modifier.shadow` would
 * reintroduce the recompute cost the original PRD banned.
 */
@Composable
internal fun CardChrome(
    palette: CardPalette,
    geometry: CardGeometry,
    modifier: Modifier = Modifier,
    withShadow: Boolean = false,
    background: (@Composable (Modifier) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CardCornerRadius)
    Box(
        modifier = modifier
            .width(geometry.width)
            .height(geometry.height)
            .let { if (withShadow) it.shadow(CardElevation, shape) else it }
            .clip(shape)
            .background(palette.accent)
            .border(CardHairlineWidth, CardHairlineColor, shape),
    ) {
        background?.invoke(Modifier.fillMaxSize().alpha(BackgroundImageAlpha))
        content()
    }
}

/**
 * Clamps `fontScale` only, preserving `density` — card-visual-identity TDD decision 3. Reconstructing
 * density (or `Density(1f, scale)`) would silently change `dp` too. Returns the ambient [Density]
 * unchanged when already under the cap so an unrelated recomposition doesn't spuriously invalidate
 * the subtree. Note: API 34+ applies font scaling non-linearly via `FontScaleConverter`, so this
 * linear 1.3× cap won't be pixel-identical to the system set to 1.3× — acceptable, not a bug to chase.
 */
@Composable
internal fun rememberCappedDensity(): Density {
    val current = LocalDensity.current
    return remember(current) {
        if (current.fontScale > 1.3f) Density(density = current.density, fontScale = 1.3f) else current
    }
}
