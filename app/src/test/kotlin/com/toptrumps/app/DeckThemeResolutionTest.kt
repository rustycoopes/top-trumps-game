package com.toptrumps.app

import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.DeckTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JUnit4, no Robolectric — [resolveDeckTheme] is pure [Deck] -> [DeckTheme] arithmetic with
 * no Android dependency, same as [com.toptrumps.app.card.CardGeometryTest]'s seam.
 */
class DeckThemeResolutionTest {

    private val cards = listOf(Card(id = "first", name = "First", stats = emptyMap()), Card(id = "second", name = "Second", stats = emptyMap()))

    @Test
    fun `a theme with an explicit heroCardId is left untouched`() {
        val deck = Deck(id = "d", metrics = emptyList(), cards = cards, theme = DeckTheme.DEFAULT.copy(heroCardId = "second"))

        val resolved = resolveDeckTheme(deck)

        assertEquals("second", resolved.heroCardId)
    }

    @Test
    fun `a theme with no heroCardId falls back to the deck's first card`() {
        val deck = Deck(id = "d", metrics = emptyList(), cards = cards, theme = DeckTheme.DEFAULT.copy(heroCardId = null))

        val resolved = resolveDeckTheme(deck)

        assertEquals("first", resolved.heroCardId)
    }

    @Test
    fun `colours are passed through unchanged`() {
        val deck = Deck(id = "d", metrics = emptyList(), cards = cards, theme = DeckTheme.DEFAULT)

        val resolved = resolveDeckTheme(deck)

        assertEquals(DeckTheme.DEFAULT.accent, resolved.accent)
        assertEquals(DeckTheme.DEFAULT.onAccent, resolved.onAccent)
    }
}
