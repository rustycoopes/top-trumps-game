package com.toptrumps.decks

import com.toptrumps.rules.ArgbColor
import com.toptrumps.rules.DeckTheme
import com.toptrumps.rules.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        val source = writeDeckFixture(tempDir, deckId, missingImageIndices = setOf(1))

        val result = DeckLoader.load(source, deckId)

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

    @Test
    fun `a manifest with a valid theme block loads with that theme`(@TempDir tempDir: File) {
        val source = writeDeckFixture(tempDir, "themed", """{ "accent": "#B3121B", "onAccent": "#FFFFFF", "heroCardId": "card-3" }""")

        val result = DeckLoader.load(source, "themed")

        assertTrue(result is DeckValidationResult.Valid, "expected Valid, got $result")
        val theme = (result as DeckValidationResult.Valid).deck.theme
        assertEquals(ArgbColor(0xFFB3121B.toInt()), theme.accent)
        assertEquals(ArgbColor(0xFFFFFFFF.toInt()), theme.onAccent)
        assertEquals("card-3", theme.heroCardId)
    }

    @Test
    fun `a manifest with no theme block loads with the default theme`(@TempDir tempDir: File) {
        val source = writeDeckFixture(tempDir, "untheme", themeJson = null)

        val result = DeckLoader.load(source, "untheme")

        assertTrue(result is DeckValidationResult.Valid, "expected Valid, got $result")
        assertEquals(DeckTheme.DEFAULT, (result as DeckValidationResult.Valid).deck.theme)
    }

    @Test
    fun `a malformed accent hex degrades to the default and the deck stays valid`(@TempDir tempDir: File) {
        val source = writeDeckFixture(tempDir, "bad-accent", """{ "accent": "not-a-colour", "onAccent": "#FFFFFF" }""")

        val result = DeckLoader.load(source, "bad-accent")

        assertTrue(result is DeckValidationResult.Valid, "expected Valid, got $result")
        val theme = (result as DeckValidationResult.Valid).deck.theme
        assertEquals(DeckTheme.DEFAULT.accent, theme.accent)
        assertEquals(ArgbColor(0xFFFFFFFF.toInt()), theme.onAccent)
    }

    @Test
    fun `a malformed onAccent hex degrades to the default and the deck stays valid`(@TempDir tempDir: File) {
        val source = writeDeckFixture(tempDir, "bad-on-accent", """{ "accent": "#B3121B", "onAccent": "orange" }""")

        val result = DeckLoader.load(source, "bad-on-accent")

        assertTrue(result is DeckValidationResult.Valid, "expected Valid, got $result")
        val theme = (result as DeckValidationResult.Valid).deck.theme
        assertEquals(ArgbColor(0xFFB3121B.toInt()), theme.accent)
        assertEquals(DeckTheme.DEFAULT.onAccent, theme.onAccent)
    }

    @Test
    fun `an unmatched heroCardId degrades to null and the deck stays valid`(@TempDir tempDir: File) {
        val source = writeDeckFixture(tempDir, "bad-hero", """{ "heroCardId": "does-not-exist" }""")

        val result = DeckLoader.load(source, "bad-hero")

        assertTrue(result is DeckValidationResult.Valid, "expected Valid, got $result")
        assertNull((result as DeckValidationResult.Valid).deck.theme.heroCardId)
    }

    @Test
    fun `a backgroundImage that resolves loads with that theme`(@TempDir tempDir: File) {
        val source = writeDeckFixture(tempDir, "themed-bg", """{ "backgroundImage": "background.webp" }""")
        File(File(tempDir, "themed-bg"), "background.webp").writeBytes(ByteArray(0))

        val result = DeckLoader.load(source, "themed-bg")

        assertTrue(result is DeckValidationResult.Valid, "expected Valid, got $result")
        assertEquals("background.webp", (result as DeckValidationResult.Valid).deck.theme.backgroundImage)
    }

    @Test
    fun `an unresolvable backgroundImage degrades to null and the deck stays valid`(@TempDir tempDir: File) {
        val source = writeDeckFixture(tempDir, "bad-bg", """{ "backgroundImage": "does-not-exist.webp" }""")

        val result = DeckLoader.load(source, "bad-bg")

        assertTrue(result is DeckValidationResult.Valid, "expected Valid, got $result")
        assertNull((result as DeckValidationResult.Valid).deck.theme.backgroundImage)
    }

    /**
     * A full 30-card, 5-metric manifest under [deckId], in a throwaway temp dir rather than the
     * shared `/decks` root — that root is bundled wholesale into the app's assets (deck-storage
     * ADR), so a deliberately-broken fixture deck must never live there. Every card gets a
     * zero-byte placeholder image except [missingImageIndices] (1-based), whose image file is
     * referenced in the manifest but never written to disk — for exercising unresolved-image
     * handling. [themeJson] is the raw `theme` block; `null` omits it entirely.
     */
    private fun writeDeckFixture(
        tempDir: File,
        deckId: String,
        themeJson: String? = null,
        missingImageIndices: Set<Int> = emptySet(),
    ): FileDeckSource {
        val deckDir = File(tempDir, deckId).apply { mkdirs() }
        File(deckDir, "images").mkdirs()
        val cards = (1..30).joinToString(",\n") { i ->
            val imageFile = if (i in missingImageIndices) "images/missing-$i.webp" else "images/card-$i.webp"
            if (i !in missingImageIndices) File(deckDir, imageFile).writeBytes(ByteArray(0))
            """{ "id": "card-$i", "name": "Card $i", "stats": { "topSpeed": $i, "year": $i, "engineCapacity": $i, "weight": $i, "length": $i }, "image": { "file": "$imageFile", "licence": "Public Domain", "author": "Fixture", "sourceUrl": "https://example.invalid" } }"""
        }
        val themeField = if (themeJson != null) """, "theme": $themeJson""" else ""
        File(deckDir, "manifest.json").writeText(
            """
            {
              "id": "$deckId",
              "name": "Fixture Deck",
              "metrics": [
                { "key": "topSpeed", "label": "Top Speed", "unit": "mph", "direction": "HIGH_WINS" },
                { "key": "year", "label": "Year", "unit": "yr", "direction": "LOW_WINS" },
                { "key": "engineCapacity", "label": "Engine Capacity", "unit": "cc", "direction": "HIGH_WINS" },
                { "key": "weight", "label": "Weight", "unit": "kg", "direction": "LOW_WINS" },
                { "key": "length", "label": "Length", "unit": "mm", "direction": "LOW_WINS" }
              ],
              "cards": [$cards]$themeField
            }
            """.trimIndent(),
        )
        return FileDeckSource(tempDir)
    }
}
