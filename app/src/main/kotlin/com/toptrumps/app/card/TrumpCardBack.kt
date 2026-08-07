package com.toptrumps.app.card

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.toptrumps.app.theme.CardPalette
import com.toptrumps.app.theme.DisplayFontFamily

/** Diagonal-stripe pitch (stripe + gap) and stripe tint over [CardPalette.accent] — one shared motif for every deck, per the WBS's explicit non-goal of per-deck motifs. */
private val StripeWidth = 14.dp
private val StripeGap = 14.dp
private val StripeColor = Color.Black.copy(alpha = 0.12f)

/** The medallion's own edge, contrasting against the stripe pattern behind it — physicality per the PRD, same idiom as [CardImageBorderColor]. */
private val MedallionBorderColor = Color.White
private val MedallionBorderWidth = 2.dp
private val MedallionShape = RoundedCornerShape(20.dp)

/**
 * The card back — same [CardChrome] as [TrumpCard], so handing both faces the same [geometry]
 * instance (never recomputing one per face) makes front/back pixel identity structural rather than
 * conventional, which is what the flip animation depends on. Drawn programmatically from the
 * deck's own accent colour rather than a bundled image, per the PRD (replaces the old hardcoded
 * navy/gold `CardBack`).
 *
 * [caption] is `null` for the opponent's face-down card (nothing to prompt there — it flips on its
 * own) and a "Tap to reveal" hint for the local hero card (issue #47), which only flips once tapped.
 *
 * The diagonal-stripe pattern (card-visual-identity slice 7) is drawn straight onto [CardPalette.accent]
 * rather than replacing it, so it needs no extra state of its own — a tonal darkening reads as a
 * pattern without fighting [CardPalette.onAccent] text contrast elsewhere on the card. The medallion
 * is opaque and drawn after the stripes, so it fully covers whatever pattern falls behind it — that's
 * what keeps its text legible (WBS design notes) — rather than the stripes being clipped around it.
 */
@Composable
internal fun TrumpCardBack(
    deckName: String,
    palette: CardPalette,
    geometry: CardGeometry,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    CompositionLocalProvider(LocalDensity provides rememberCappedDensity()) {
        CardChrome(palette = palette, geometry = geometry, modifier = modifier) {
            Canvas(Modifier.fillMaxSize()) { drawDiagonalStripes() }
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .clip(MedallionShape)
                        .background(palette.accent, MedallionShape)
                        .border(MedallionBorderWidth, MedallionBorderColor, MedallionShape)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = deckName,
                        color = palette.onAccent,
                        style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 30.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (caption != null) {
                        Text(
                            text = caption,
                            color = palette.onAccent,
                            style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tiles opaque stripes across a rectangle well past its own bounds and rotates the whole thing
 * 45°, rather than computing each stripe's true diagonal geometry — [rotate] pivots on the
 * `DrawScope`'s own centre, so `size.width + size.height` of coverage on every side is generous
 * enough that no corner of the (unrotated) canvas is ever left unpainted after the rotation.
 */
private fun DrawScope.drawDiagonalStripes() {
    val pitch = (StripeWidth + StripeGap).toPx()
    val stripePx = StripeWidth.toPx()
    val reach = size.width + size.height
    rotate(degrees = 45f) {
        var x = -reach
        while (x < reach) {
            drawRect(color = StripeColor, topLeft = Offset(x, -reach), size = Size(stripePx, reach * 2))
            x += pitch
        }
    }
}
