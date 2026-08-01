package com.toptrumps.decks

import com.toptrumps.rules.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Reads the real `/decks` content — not a fixture — via [FileDeckSource], taking the root from
 * an injected system property rather than a relative path (see the deck-storage ADR).
 */
class DeckLoaderTest {

    private val source = FileDeckSource(File(System.getProperty("decksRootDir")))

    private val fixtureImage = """
        { "file": "images/fixture.webp", "licence": "Public Domain", "author": "Test Fixture", "sourceUrl": "https://example.invalid" }
    """.trimIndent()

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
                    { "id": "a", "name": "A", "stats": { "topSpeed": 1 }, "image": $fixtureImage },
                    { "id": "b", "name": "B", "stats": {}, "image": $fixtureImage }
                  ]
                }
            """.trimIndent(),
        )

        assertTrue(result is DeckValidationResult.Invalid)
        assertTrue((result as DeckValidationResult.Invalid).errors.any { it.contains("card 'b'") })
    }

    @Test
    fun `a manifest with the wrong card count is invalid`() {
        val result = DeckLoader.parse(
            deckId = "broken",
            manifestJson = """
                {
                  "id": "broken",
                  "name": "Broken",
                  "metrics": [{ "key": "topSpeed", "label": "Top Speed", "unit": "mph", "direction": "HIGH_WINS" }],
                  "cards": [
                    { "id": "a", "name": "A", "stats": { "topSpeed": 1 }, "image": $fixtureImage }
                  ]
                }
            """.trimIndent(),
        )

        assertTrue(result is DeckValidationResult.Invalid)
        assertTrue((result as DeckValidationResult.Invalid).errors.any { it.contains("exactly 30 cards") })
    }

    @Test
    fun `a metric with a blank unit is invalid`() {
        val result = DeckLoader.parse(
            deckId = "broken",
            manifestJson = """
                {
                  "id": "broken",
                  "name": "Broken",
                  "metrics": [{ "key": "topSpeed", "label": "Top Speed", "unit": "", "direction": "HIGH_WINS" }],
                  "cards": [
                    { "id": "a", "name": "A", "stats": { "topSpeed": 1 }, "image": $fixtureImage },
                    { "id": "b", "name": "B", "stats": { "topSpeed": 2 }, "image": $fixtureImage }
                  ]
                }
            """.trimIndent(),
        )

        assertTrue(result is DeckValidationResult.Invalid)
        assertTrue((result as DeckValidationResult.Invalid).errors.any { it.contains("no unit") })
    }

    @Test
    fun `an image reference that does not resolve is invalid`(@TempDir tempDir: File) {
        // Built in a throwaway temp dir, not under the shared /decks root — that root is bundled
        // wholesale into the app's assets (deck-storage ADR), so a deliberately-broken fixture
        // deck must never live there or it would ship in the real deck picker.
        val deckId = "unresolvable-image"
        val deckDir = File(tempDir, deckId).apply { mkdirs() }
        File(deckDir, "images").mkdirs()

        val cards = (1..30).joinToString(",\n") { i ->
            val imageFile = if (i == 1) "images/missing.webp" else "images/card-$i.webp"
            if (i != 1) File(deckDir, imageFile).writeBytes(ByteArray(0))
            """{ "id": "card-$i", "name": "Card $i", "stats": { "topSpeed": $i, "year": $i, "engineCapacity": $i, "weight": $i, "length": $i }, "image": { "file": "$imageFile", "licence": "Public Domain", "author": "Fixture", "sourceUrl": "https://example.invalid" } }"""
        }
        File(deckDir, "manifest.json").writeText(
            """
            {
              "id": "$deckId",
              "name": "Unresolvable Image Fixture",
              "metrics": [
                { "key": "topSpeed", "label": "Top Speed", "unit": "mph", "direction": "HIGH_WINS" },
                { "key": "year", "label": "Year", "unit": "yr", "direction": "LOW_WINS" },
                { "key": "engineCapacity", "label": "Engine Capacity", "unit": "cc", "direction": "HIGH_WINS" },
                { "key": "weight", "label": "Weight", "unit": "kg", "direction": "LOW_WINS" },
                { "key": "length", "label": "Length", "unit": "mm", "direction": "LOW_WINS" }
              ],
              "cards": [$cards]
            }
            """.trimIndent(),
        )

        val result = DeckLoader.load(FileDeckSource(tempDir), deckId)

        assertTrue(result is DeckValidationResult.Invalid, "expected Invalid, got $result")
        assertTrue(
            (result as DeckValidationResult.Invalid).errors.any { it.contains("does not resolve") },
            "expected an unresolvable-image error, got ${result.errors}",
        )
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
                    { "id": "a", "name": "A", "stats": { "topSpeed": 1 }, "image": $fixtureImage },
                    { "id": "b", "name": "B", "stats": { "topSpeed": 2 }, "image": $fixtureImage }
                  ]
                }
            """.trimIndent(),
        )

        assertTrue(result is DeckValidationResult.Invalid)
        assertTrue((result as DeckValidationResult.Invalid).errors.any { it.contains("direction") })
    }

    @Test
    fun `manifestHash is stable for the same deck and differs between decks`() {
        val testDeckHash = DeckLoader.manifestHash(source, "test-deck")
        val testDeckHashAgain = DeckLoader.manifestHash(source, "test-deck")
        val motorcyclesHash = DeckLoader.manifestHash(source, "motorcycles")

        assertTrue(testDeckHash != null && testDeckHash.isNotBlank())
        assertEquals(testDeckHash, testDeckHashAgain, "hashing the same manifest twice must agree")
        assertTrue(testDeckHash != motorcyclesHash, "two different manifests must not collide")
    }

    @Test
    fun `manifestHash is null for a deck that does not exist`() {
        assertEquals(null, DeckLoader.manifestHash(source, "does-not-exist"))
    }
}
