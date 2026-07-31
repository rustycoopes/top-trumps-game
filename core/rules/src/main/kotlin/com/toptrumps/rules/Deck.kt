package com.toptrumps.rules

public data class Card(
    val id: String,
    val name: String,
    val stats: Map<MetricKey, StatValue>,
)

public data class Deck(
    val id: String,
    val metrics: List<MetricSpec>,
    val cards: List<Card>,
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
