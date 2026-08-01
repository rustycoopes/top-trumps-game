package com.toptrumps.decks

import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.Direction
import com.toptrumps.rules.MetricKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Validates the real `/decks/motorcycles` content — not a fixture, per the deck-storage ADR — so
 * nothing here can drift from what actually ships.
 */
class MotorcyclesDeckTest {

    private val source = FileDeckSource(File(System.getProperty("decksRootDir")))
    private val deck: Deck = (DeckLoader.load(source, "motorcycles") as DeckValidationResult.Valid).deck

    @Test
    fun `the motorcycles deck validates with thirty cards and five metrics`() {
        val result = DeckLoader.load(source, "motorcycles")
        assertTrue(result is DeckValidationResult.Valid, "expected Valid, got $result")
        assertEquals(30, deck.cards.size)
        assertEquals(5, deck.metrics.size)
    }

    @Test
    fun `the roster spans 1923 to 2018`() {
        val years = deck.cards.map { it.stats.getValue(MetricKey("year")).raw.toInt() }
        assertEquals(1923, years.min())
        assertEquals(2018, years.max())
    }

    @Test
    fun `every card can win on at least one metric against the rest of the deck`() {
        // A card that ties-or-loses to literally every other card on every metric is dead weight
        // in a hand — story 73. "Can win" means: for at least one metric, this card strictly
        // beats at least one other card in the deck under that metric's win direction.
        val deadWeight = deck.cards.filter { card -> !card.canWinSomeMetricAgainst(deck) }
        assertTrue(deadWeight.isEmpty(), "dead-weight cards (never win any metric against anything): ${deadWeight.map { it.name }}")
    }

    private fun Card.canWinSomeMetricAgainst(deck: Deck): Boolean =
        deck.metrics.any { spec ->
            val mine = stats.getValue(spec.key).raw
            deck.cards.any { other ->
                other.id != id &&
                    run {
                        val theirs = other.stats.getValue(spec.key).raw
                        if (spec.direction == Direction.HIGH_WINS) mine > theirs else mine < theirs
                    }
            }
        }
}
