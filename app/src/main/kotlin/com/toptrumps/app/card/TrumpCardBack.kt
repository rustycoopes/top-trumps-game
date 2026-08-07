package com.toptrumps.app.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.toptrumps.app.theme.CardPalette
import com.toptrumps.app.theme.DisplayFontFamily

/**
 * The card back — same [CardChrome] as [TrumpCard], so handing both faces the same [geometry]
 * instance (never recomputing one per face) makes front/back pixel identity structural rather than
 * conventional, which is what the flip animation depends on. Drawn programmatically from the
 * deck's own accent colour rather than a bundled image, per the PRD (replaces the old hardcoded
 * navy/gold `CardBack`).
 *
 * [caption] is `null` for the opponent's face-down card (nothing to prompt there — it flips on its
 * own) and a "Tap to reveal" hint for the local hero card (issue #47), which only flips once tapped.
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
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = deckName,
                        color = palette.onAccent,
                        style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 22.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (caption != null) {
                        Text(
                            text = caption,
                            color = palette.onAccent,
                            style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
