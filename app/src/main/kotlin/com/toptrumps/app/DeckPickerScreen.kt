package com.toptrumps.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.toptrumps.app.card.AssetTrumpCard
import com.toptrumps.app.card.CardContent
import com.toptrumps.app.card.CardCornerRadius
import com.toptrumps.app.card.CardVariant
import com.toptrumps.app.card.cardGeometry
import com.toptrumps.app.theme.toCardPalette
import com.toptrumps.rules.DeckTheme

/** Deck tile width — a mini card plus a little breathing room, per [CardVariant.MINI]'s geometry. */
private val DeckTileWidth = 120.dp

/**
 * Player One's deck choice — story 18/19. Lists whatever [decks] [AppGraph.listDecks] found under
 * `/decks` at launch; adding a folder later needs no change here, since the list is never
 * hardcoded. Each deck renders as a mini card ([CardVariant.MINI]) in its own [DeckTheme] —
 * [deckTheme] resolving to `null` (a deck [AppGraph.listDecks] itself couldn't validate) falls
 * back to [DeckTheme.DEFAULT] rather than skipping the tile. [imageLoader] is the app's single
 * shared instance ([AppGraph.imageLoader]) — this screen never builds its own (card-visual-identity
 * WBS slice 5's "no second loader instance" acceptance criterion).
 */
@Composable
public fun DeckPickerScreen(
    decks: List<DeckSummary>,
    deckTheme: (deckId: String) -> DeckTheme?,
    imageLoader: ImageLoader,
    onPick: (DeckSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose a deck")
        if (decks.isEmpty()) {
            Text("No decks found — check the app's assets.")
            return@Column
        }
        // One instance shared by every tile, not recomputed per cell — same discipline as the
        // card gallery's own mini grid (CardGalleryScreen.kt).
        val miniGeometry = remember { cardGeometry(width = DeckTileWidth, variant = CardVariant.MINI) }
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = DeckTileWidth + 20.dp), modifier = Modifier.fillMaxSize()) {
            items(decks, key = { it.id }) { deck ->
                val palette = remember(deck.id) { (deckTheme(deck.id) ?: DeckTheme.DEFAULT).toCardPalette() }
                val content = remember(deck.id) { CardContent(title = deck.name, rows = emptyList()) }
                AssetTrumpCard(
                    deckId = deck.id,
                    imageFile = deck.heroImageFile,
                    content = content,
                    palette = palette,
                    geometry = miniGeometry,
                    imageLoader = imageLoader,
                    // Clipped *before* `clickable` (outer, not inner, in the composed chain) so the
                    // tap ripple is bounded by the card's own rounded corners instead of bleeding
                    // into the square layout box behind it — CardChrome's own `clip` only reaches
                    // content drawn inside it, not this caller-supplied modifier.
                    modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(CardCornerRadius)).clickable(onClickLabel = deck.name) { onPick(deck) },
                    onChooseStat = null,
                )
            }
        }
    }
}
