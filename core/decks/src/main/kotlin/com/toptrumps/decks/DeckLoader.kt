package com.toptrumps.decks

import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.Direction
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.MetricSpec
import com.toptrumps.rules.StatValue
import kotlinx.serialization.json.Json
import java.io.IOException

public sealed interface DeckValidationResult {
    public data class Valid(val deck: Deck) : DeckValidationResult
    public data class Invalid(val errors: List<String>) : DeckValidationResult
}

/** Parses and validates a deck manifest. Never touches images — those are out of scope until a later slice. */
public object DeckLoader {

    private val json = Json { ignoreUnknownKeys = true }

    public fun load(source: DeckSource, deckId: String): DeckValidationResult {
        val text = try {
            source.open(deckId, "manifest.json").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            return DeckValidationResult.Invalid(listOf("could not read manifest.json for deck '$deckId': ${e.message}"))
        }
        return parse(deckId, text)
    }

    internal fun parse(deckId: String, manifestJson: String): DeckValidationResult {
        val dto = try {
            json.decodeFromString(DeckManifestDto.serializer(), manifestJson)
        } catch (e: Exception) {
            return DeckValidationResult.Invalid(listOf("could not parse manifest.json: ${e.message}"))
        }

        val errors = mutableListOf<String>()
        if (dto.id != deckId) {
            errors += "manifest id '${dto.id}' does not match deck folder '$deckId'"
        }
        if (dto.metrics.isEmpty()) {
            errors += "deck must declare at least one metric"
        }
        val metricKeys = dto.metrics.map { it.key }
        if (metricKeys.size != metricKeys.distinct().size) {
            errors += "metric keys must be unique"
        }
        val directions = dto.metrics.associate { spec ->
            spec.key to spec.direction.toDirectionOrNull().also { direction ->
                if (direction == null) errors += "metric '${spec.key}' has unknown direction '${spec.direction}'"
            }
        }
        if (dto.cards.size < 2) {
            errors += "deck must have at least two cards to deal a hand to each seat"
        }
        val cardIds = dto.cards.map { it.id }
        if (cardIds.size != cardIds.distinct().size) {
            errors += "card ids must be unique"
        }
        for (card in dto.cards) {
            for (key in metricKeys) {
                if (key !in card.stats) {
                    errors += "card '${card.id}' is missing a value for metric '$key'"
                }
            }
        }

        if (errors.isNotEmpty()) {
            return DeckValidationResult.Invalid(errors)
        }

        val deck = Deck(
            id = dto.id,
            metrics = dto.metrics.map { spec ->
                MetricSpec(
                    key = MetricKey(spec.key),
                    label = spec.label,
                    unit = spec.unit,
                    direction = directions.getValue(spec.key)!!,
                )
            },
            cards = dto.cards.map { card ->
                Card(
                    id = card.id,
                    name = card.name,
                    stats = card.stats.mapKeys { (key, _) -> MetricKey(key) }.mapValues { (_, value) -> StatValue(value) },
                )
            },
        )
        return DeckValidationResult.Valid(deck)
    }

    private fun String.toDirectionOrNull(): Direction? = when (this) {
        "HIGH_WINS" -> Direction.HIGH_WINS
        "LOW_WINS" -> Direction.LOW_WINS
        else -> null
    }
}
