package com.toptrumps.decks

import kotlinx.serialization.Serializable

@Serializable
internal data class MetricSpecDto(
    val key: String,
    val label: String,
    val unit: String,
    val direction: String,
)

@Serializable
internal data class CardDto(
    val id: String,
    val name: String,
    val stats: Map<String, Double>,
)

@Serializable
internal data class DeckManifestDto(
    val id: String,
    val name: String,
    val metrics: List<MetricSpecDto>,
    val cards: List<CardDto>,
)
