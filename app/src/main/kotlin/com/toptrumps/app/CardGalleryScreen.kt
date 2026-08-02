package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.toptrumps.app.card.AssetTrumpCard
import com.toptrumps.app.card.CardVariant
import com.toptrumps.app.card.TrumpCardBack
import com.toptrumps.app.card.cardContentOf
import com.toptrumps.app.card.cardGeometry
import com.toptrumps.app.theme.toCardPalette
import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.DeckTheme
import com.toptrumps.rules.displayDirection
import com.toptrumps.session.RemoteCardFace
import com.toptrumps.session.RemoteMetricSpec

/**
 * Debug-build-only, reachable from Settings (gated at the `MainActivity` call site, per the WBS —
 * this composable itself doesn't know or care about build type). Renders every card of a real deck
 * at real size from real assets: the acceptance criteria this slice can't otherwise satisfy without
 * a physical device — actual photo crops, actual long names, and (via the opened-card view) that a
 * card's back is visually seamless against its front at the same [com.toptrumps.app.card.CardGeometry].
 *
 * `internal`, not `public` like its sibling top-level screens (`SettingsScreen`, `MatchScreen`) —
 * its only caller is `MainActivity`, in this same module, and it has no reason to ever be public.
 */
@Composable
internal fun CardGalleryScreen(appGraph: AppGraph, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var deckId by remember { mutableStateOf<String?>(null) }
    var openedCard by remember { mutableStateOf<RemoteCardFace?>(null) }

    val chosenDeckId = deckId
    if (chosenDeckId == null) {
        val decks = remember { appGraph.listDecks() }
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("Back") }
            Text("Card gallery — pick a deck")
            decks.forEach { summary -> Button(onClick = { deckId = summary.id }) { Text(summary.name) } }
        }
        return
    }

    val deck = remember(chosenDeckId) { appGraph.loadDeck(chosenDeckId) }
    val theme = remember(chosenDeckId) { appGraph.deckTheme(chosenDeckId) }
    if (deck == null || theme == null) {
        Column(modifier.fillMaxSize().padding(16.dp)) {
            Button(onClick = { deckId = null }) { Text("Back to decks") }
            Text("Deck failed to load")
        }
        return
    }

    val opened = openedCard
    if (opened != null) {
        OpenedCard(
            deckId = chosenDeckId,
            deckName = deck.name,
            card = opened,
            metrics = remember(deck) { deck.remoteMetrics() },
            theme = theme,
            imageLoader = appGraph.imageLoader,
            onBack = { openedCard = null },
            modifier = modifier,
        )
        return
    }

    val palette = remember(theme) { theme.toCardPalette() }
    val remoteMetrics = remember(deck) { deck.remoteMetrics() }
    // Every mini tile in the grid shares one geometry — hoisted above the grid, not recomputed per
    // item, the same "one instance" discipline that gives front/back their identity elsewhere.
    val miniGeometry = remember { cardGeometry(width = 96.dp, variant = CardVariant.MINI) }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { deckId = null }) { Text("Back to decks") }
        Text("${deck.name} — ${deck.cards.size} cards")
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 110.dp), modifier = Modifier.fillMaxSize()) {
            items(deck.cards) { card ->
                val remoteCard = remember(card) { card.toRemoteCardFace() }
                val content = remember(remoteCard, remoteMetrics) { cardContentOf(remoteCard, remoteMetrics) }
                Column(
                    modifier = Modifier.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AssetTrumpCard(
                        deckId = chosenDeckId,
                        imageFile = card.image.file,
                        content = content,
                        palette = palette,
                        geometry = miniGeometry,
                        imageLoader = appGraph.imageLoader,
                        modifier = Modifier.padding(4.dp),
                        onChooseStat = null,
                    )
                    Button(onClick = { openedCard = remoteCard }) { Text("Open") }
                }
            }
        }
    }
}

/** Full-size front and back side by side, at the same [com.toptrumps.app.card.CardGeometry] instance — the on-device check for "geometrically identical". */
@Composable
private fun OpenedCard(
    deckId: String,
    deckName: String,
    card: RemoteCardFace,
    metrics: List<RemoteMetricSpec>,
    theme: DeckTheme,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = remember(theme) { theme.toCardPalette() }
    val geometry = remember { cardGeometry(width = 160.dp, variant = CardVariant.HERO, minRowHeight = 0.dp) }
    val content = remember(card, metrics) { cardContentOf(card, metrics) }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onBack) { Text("Back to gallery") }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box { AssetTrumpCard(deckId, card.imageFile, content, palette, geometry, imageLoader, onChooseStat = null) }
            Box { TrumpCardBack(deckName = deckName, palette = palette, geometry = geometry) }
        }
    }
}

private fun Deck.remoteMetrics(): List<RemoteMetricSpec> =
    metrics.map { RemoteMetricSpec(it.key.id, it.label, it.unit, it.displayDirection().name, it.display.name) }

private fun Card.toRemoteCardFace(): RemoteCardFace =
    RemoteCardFace(id, name, stats.entries.associate { (key, value) -> key.id to value.raw }, image.file)
