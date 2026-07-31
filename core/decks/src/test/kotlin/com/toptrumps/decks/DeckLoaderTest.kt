package com.toptrumps.decks

import com.toptrumps.rules.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Reads the real `/decks` content — not a fixture — via [FileDeckSource], taking the root from
 * an injected system property rather than a relative path (see the deck-storage ADR).
 */
class DeckLoaderTest {

    private val source = FileDeckSource(File(System.getProperty("decksRootDir")))

    @Test
    fun `the test deck is discoverable`() {
        assertTrue("test-deck" in source.listDecks())
    }

    @Test
    fun `the test deck loads and validates with thirty cards and both win directions`() {
        val result = DeckLoader.load(source, "test-deck")

        assertTrue(result is DeckValidationResult.Valid, "expected Valid, got $result")
        val deck = (result as DeckValidationResult.Valid).deck

        assertEquals(30, deck.cards.size)
        assertEquals(5, deck.metrics.size)
        assertTrue(deck.metrics.any { it.direction == Direction.HIGH_WINS })
        assertTrue(deck.metrics.any { it.direction == Direction.LOW_WINS })
    }

    @Test
    fun `a manifest missing a stat for a declared metric is invalid`() {
        val result = DeckLoader.parse(
            deckId = "broken",
            manifestJson = """
                {
                  "id": "broken",
                  "name": "Broken",
                  "metrics": [{ "key": "topSpeed", "label": "Top Speed", "unit": "mph", "direction": "HIGH_WINS" }],
                  "cards": [
                    { "id": "a", "name": "A", "stats": { "topSpeed": 1 } },
                    { "id": "b", "name": "B", "stats": {} }
                  ]
                }
            """.trimIndent(),
        )

        assertTrue(result is DeckValidationResult.Invalid)
        assertTrue((result as DeckValidationResult.Invalid).errors.any { it.contains("card 'b'") })
    }

    @Test
    fun `loading a deck that does not exist is invalid rather than throwing`() {
        val result = DeckLoader.load(source, "does-not-exist")

        assertTrue(result is DeckValidationResult.Invalid)
    }

    @Test
    fun `an unknown direction is invalid`() {
        val result = DeckLoader.parse(
            deckId = "broken",
            manifestJson = """
                {
                  "id": "broken",
                  "name": "Broken",
                  "metrics": [{ "key": "topSpeed", "label": "Top Speed", "unit": "mph", "direction": "SIDEWAYS" }],
                  "cards": [
                    { "id": "a", "name": "A", "stats": { "topSpeed": 1 } },
                    { "id": "b", "name": "B", "stats": { "topSpeed": 2 } }
                  ]
                }
            """.trimIndent(),
        )

        assertTrue(result is DeckValidationResult.Invalid)
        assertTrue((result as DeckValidationResult.Invalid).errors.any { it.contains("direction") })
    }
}
