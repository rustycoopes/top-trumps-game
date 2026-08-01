package com.toptrumps.decks

import kotlinx.serialization.Serializable

@Serializable
internal data class MetricSpecDto(
    val key: String,
    val label: String,
    val unit: String,
    val direction: String,
    val display: String = "RAW",
)

@Serializable
internal data class CardImageDto(
    val file: String,
    val licence: String,
    val author: String,
    val sourceUrl: String,
)

@Serializable
internal data class CardDto(
    val id: String,
    val name: String,
    val stats: Map<String, Double>,
    val image: CardImageDto,
)

@Serializable
internal data class DeckManifestDto(
    val id: String,
    val name: String,
    val metrics: List<MetricSpecDto>,
    val cards: List<CardDto>,
    /**
     * Free-text conventions nominated for metrics where published figures genuinely disagree
     * between sources (e.g. dry vs kerb weight, claimed vs tested top speed) — see the PRD's
     * "Deck format and content" section. Documentation only; not consumed by [DeckLoader].
     */
    val conventions: Map<String, String> = emptyMap(),
)
