package com.toptrumps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

public class MainActivity : ComponentActivity() {

    private lateinit var appGraph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appGraph = AppGraph(assets)

        setContent {
            SoloMatchHost(appGraph)
        }
    }

    override fun onDestroy() {
        appGraph.close()
        super.onDestroy()
    }
}

/**
 * Player One picks a deck (story 18), then owns the current solo
 * [com.toptrumps.session.MatchSession] for it and swaps it in for a rematch. Rematch stays on the
 * same deck rather than re-prompting — the picker is a match-setup step, not something to repeat
 * every round.
 */
@Composable
private fun SoloMatchHost(appGraph: AppGraph) {
    var chosenDeck by remember { mutableStateOf<DeckSummary?>(null) }

    val deck = chosenDeck
    if (deck == null) {
        // remember, not a direct call: listDecks() validates every deck folder's manifest and
        // every card's image reference, which is real file IO — recomputing it on every
        // recomposition of the picker would redo that walk on the main thread for nothing.
        val decks = remember { appGraph.listDecks() }
        DeckPickerScreen(decks = decks, onPick = { chosenDeck = it })
        return
    }

    var session by remember(deck.id) { mutableStateOf(appGraph.startSoloMatch(deck.id)) }

    MatchScreen(
        session = session,
        deckId = deck.id,
        onRematch = {
            session.close()
            session = appGraph.startSoloMatch(deck.id)
        },
    )
}
