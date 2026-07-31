package com.toptrumps.decks

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Enumerates and opens deck content. Deliberately Android-free — the Android implementation
 * wraps `AssetManager`; this interface exists so `:core:decks` never needs to know which one it
 * is talking to. See the deck-storage ADR.
 */
public interface DeckSource {
    public fun listDecks(): List<String>
    public fun open(deckId: String, path: String): InputStream
}

/**
 * A plain [java.io.File] implementation, used by JVM tests to read the real `/decks` content —
 * see the deck-storage ADR's rationale for validating against production data rather than a
 * fixture that can drift from it.
 */
public class FileDeckSource(private val rootDir: File) : DeckSource {

    override fun listDecks(): List<String> {
        val entries = rootDir.listFiles() ?: return emptyList()
        return entries
            .filter { it.isDirectory && File(it, "manifest.json").isFile }
            .map { it.name }
            .sorted()
    }

    override fun open(deckId: String, path: String): InputStream {
        val file = File(File(rootDir, deckId), path)
        if (!file.isFile) {
            throw FileNotFoundException("no such deck file: ${file.absolutePath}")
        }
        return file.inputStream()
    }
}
