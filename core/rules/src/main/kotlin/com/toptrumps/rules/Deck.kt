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

public data class MatchConfig(
    val deckId: String,
)
