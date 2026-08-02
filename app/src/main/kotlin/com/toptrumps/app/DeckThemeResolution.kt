package com.toptrumps.app

import com.toptrumps.decks.DeckLoader
import com.toptrumps.rules.Deck
import com.toptrumps.rules.DeckTheme

/**
 * [deck]'s theme with [DeckTheme.heroCardId] resolved to an id guaranteed to name a card in
 * [Deck.cards] — `null` (the manifest omitted it, or named a card that doesn't exist; [DeckLoader]
 * has already discarded an unmatched id by the time [Deck.theme] reaches here) falls back to the
 * deck's first card. See the deck-theme-block ADR and TDD decision 6: this resolution needs
 * [Deck.cards], which only a loaded [Deck] carries — not [DeckSummary] — so [AppGraph] does it at
 * the same point it already loads each deck, rather than the picker doing it later.
 */
internal fun resolveDeckTheme(deck: Deck): DeckTheme =
    deck.theme.copy(heroCardId = deck.theme.heroCardId ?: deck.cards.first().id)
