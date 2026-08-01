package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Player One's deck choice — story 18/19. Lists whatever [decks] [AppGraph.listDecks] found under
 * `/decks` at launch; adding a folder later needs no change here, since the list is never
 * hardcoded.
 */
@Composable
public fun DeckPickerScreen(decks: List<DeckSummary>, onPick: (DeckSummary) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose a deck")
        if (decks.isEmpty()) {
            Text("No decks found — check the app's assets.")
            return@Column
        }
        decks.forEach { deck ->
            Button(onClick = { onPick(deck) }) {
                Text(deck.name)
            }
        }
    }
}
