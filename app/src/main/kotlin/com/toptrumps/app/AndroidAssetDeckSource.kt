package com.toptrumps.app

import android.content.res.AssetManager
import com.toptrumps.decks.DeckSource
import java.io.IOException
import java.io.InputStream

/**
 * Wraps [AssetManager]. `:app` registers the repo-root `/decks` folder as an asset source
 * directory (`assets.srcDir(rootProject.file("decks"))`), so each deck folder is a top-level
 * asset entry — see the deck-storage ADR. `AssetManager.list()` doesn't distinguish files from
 * directories, so a deck is anything whose `manifest.json` actually opens.
 */
public class AndroidAssetDeckSource(private val assets: AssetManager) : DeckSource {

    override fun listDecks(): List<String> {
        val entries = assets.list("") ?: return emptyList()
        return entries.filter { name ->
            try {
                assets.open("$name/manifest.json").close()
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    override fun open(deckId: String, path: String): InputStream = assets.open("$deckId/$path")
}
