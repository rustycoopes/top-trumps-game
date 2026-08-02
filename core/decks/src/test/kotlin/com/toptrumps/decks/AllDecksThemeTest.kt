package com.toptrumps.decks

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Enumerates every folder under `/decks` and asserts each `theme` block parses *without*
 * degrading — the load-bearing decision of this slice (deck-theme-block ADR). [DeckLoader] itself
 * is deliberately lenient at runtime (a malformed field falls back to the default rather than
 * dropping the whole deck from the picker), so that leniency can't catch a typo — this test exists
 * specifically to catch it at commit time instead, by re-checking the same fields strictly.
 *
 * This also gives `decks/lucys-youtubers/` its first test coverage of any kind — only `test-deck`
 * and `motorcycles` had deck tests before this slice.
 */
class AllDecksThemeTest {

    private val rootDir = File(System.getProperty("decksRootDir"))
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `every deck's theme block parses without degrading`() {
        val deckIds = FileDeckSource(rootDir).listDecks()
        assertTrue(deckIds.isNotEmpty(), "expected at least one deck under ${rootDir.absolutePath}")

        val failures = deckIds.flatMap(::themeErrorsFor)
        assertTrue(failures.isEmpty(), "theme block(s) failed to parse cleanly:\n${failures.joinToString("\n")}")
    }

    private fun themeErrorsFor(deckId: String): List<String> {
        val manifestFile = File(File(rootDir, deckId), "manifest.json")
        val dto = json.decodeFromString(DeckManifestDto.serializer(), manifestFile.readText())
        val theme = dto.theme ?: return emptyList()
        val cardIds = dto.cards.map { it.id }.toSet()

        val errors = mutableListOf<String>()
        theme.accent?.let { hex -> if (parseArgbHex(hex) == null) errors += "$deckId: theme.accent '$hex' is not a valid #RRGGBB colour" }
        theme.onAccent?.let { hex -> if (parseArgbHex(hex) == null) errors += "$deckId: theme.onAccent '$hex' is not a valid #RRGGBB colour" }
        theme.heroCardId?.let { id -> if (id !in cardIds) errors += "$deckId: theme.heroCardId '$id' does not match any card id" }
        return errors
    }
}
