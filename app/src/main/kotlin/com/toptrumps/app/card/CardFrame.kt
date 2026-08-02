package com.toptrumps.app.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * The outer card box every front/back shares: fixed to [geometry]'s size, [palette]'s accent fill,
 * rounded corners, hairline edge. Two calls with the same [geometry] instance produce the same
 * size, which is what makes front/back pixel identity structural (see [CardGeometry]'s doc).
 */
@Composable
internal fun CardChrome(
    palette: CardPalette,
    geometry: CardGeometry,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CardCornerRadius)
    Box(
        modifier = modifier
            .width(geometry.width)
            .height(geometry.height)
            .clip(shape)
            .background(palette.accent)
            .border(CardHairlineWidth, CardHairlineColor, shape),
        content = content,
    )
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
