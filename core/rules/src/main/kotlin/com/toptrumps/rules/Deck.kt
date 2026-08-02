package com.toptrumps.rules

public data class Card(
    val id: String,
    val name: String,
    val stats: Map<MetricKey, StatValue>,
    /**
     * Test-convenience default only — `:core:decks`'s `DeckLoader` always supplies a real
     * [CardImage] from the manifest on every production path. A `Card` built with this default
     * would render a blank image rather than fail loudly, so don't rely on it outside tests.
     */
    val image: CardImage = CardImage(file = "", licence = "", author = "", sourceUrl = ""),
)

public data class Deck(
    val id: String,
    val metrics: List<MetricSpec>,
    val cards: List<Card>,
    /** The picker's display label (story 19) — defaults to [id] for fixtures that don't set it. */
    val name: String = id,
    /** This deck's visual identity — defaults to the classic yellow-on-black look for a manifest with no `theme` block. See the deck-theme-block ADR. */
    val theme: DeckTheme = DeckTheme.DEFAULT,
)

/**
 * What happens when every metric ties in the same round. Data, not a hardcoded branch — see the
 * all-metrics-tie ADR. `CHOOSER_WINS` is the default; both it and `DEFENDER_WINS` always award
 * the round to one seat, so either preserves the no-draw invariant when paired with an odd round
 * count. `EACH_KEEPS_OWN` intentionally allows a drawn match and exists for
 * completeness/testability, not because the shipped config uses it.
 */
public enum class TieFallback { CHOOSER_WINS, DEFENDER_WINS, EACH_KEEPS_OWN }

public data class MatchConfig(
    val deckId: String,
    val allMetricsTieFallback: TieFallback = TieFallback.CHOOSER_WINS,
)
