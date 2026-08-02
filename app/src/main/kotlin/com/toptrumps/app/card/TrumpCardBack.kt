package com.toptrumps.app.card

import androidx.compose.foundation.layout.Box
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
 */
@Composable
internal fun TrumpCardBack(
    deckName: String,
    palette: CardPalette,
    geometry: CardGeometry,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalDensity provides rememberCappedDensity()) {
        CardChrome(palette = palette, geometry = geometry, modifier = modifier) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = deckName,
                    color = palette.onAccent,
                    style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 22.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
