package com.toptrumps.decks

import com.toptrumps.rules.Deck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Validates the real `/decks/lucys-youtubers` content — not a fixture, per the deck-storage ADR —
 * so nothing here can drift from what actually ships.
 */
class LucysYoutubersDeckTest {

    private val source = FileDeckSource(File(System.getProperty("decksRootDir")))
    private val deck: Deck = (DeckLoader.load(source, "lucys-youtubers") as DeckValidationResult.Valid).deck

    @Test
    fun `the lucys-youtubers deck validates with thirty cards and five metrics`() {
        val result = DeckLoader.load(source, "lucys-youtubers")
        assertTrue(result is DeckValidationResult.Valid, "expected Valid, got $result")
        assertEquals(30, deck.cards.size)
        assertEquals(5, deck.metrics.size)
    }
}
