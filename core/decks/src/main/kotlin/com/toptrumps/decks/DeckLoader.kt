package com.toptrumps.decks

import com.toptrumps.rules.Card
import com.toptrumps.rules.CardImage
import com.toptrumps.rules.Deck
import com.toptrumps.rules.Direction
import com.toptrumps.rules.MetricDisplay
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.MetricSpec
import com.toptrumps.rules.StatValue
import kotlinx.serialization.json.Json
import java.io.IOException

public sealed interface DeckValidationResult {
    public data class Valid(val deck: Deck) : DeckValidationResult
    public data class Invalid(val errors: List<String>) : DeckValidationResult
}

/** The fixed shape every deck must have — PRD: "Deck of 30 cards... whole deck in play". */
private const val REQUIRED_CARD_COUNT = 30
private const val REQUIRED_METRIC_COUNT = 5

/** Parses and validates a deck manifest, including that every card's image reference resolves. */
public object DeckLoader {

    private val json = Json { ignoreUnknownKeys = true }

    public fun load(source: DeckSource, deckId: String): DeckValidationResult {
        val text = try {
            source.open(deckId, "manifest.json").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            return DeckValidationResult.Invalid(listOf("could not read manifest.json for deck '$deckId': ${e.message}"))
        }
        val result = parse(deckId, text)
        if (result is DeckValidationResult.Invalid) return result
        val deck = (result as DeckValidationResult.Valid).deck

        val imageErrors = deck.cards.mapNotNull { card ->
            try {
                source.open(deckId, card.image.file).close()
                null
            } catch (e: IOException) {
                "card '${card.id}' image reference '${card.image.file}' does not resolve"
            }
        }
        return if (imageErrors.isEmpty()) result else DeckValidationResult.Invalid(imageErrors)
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
        if (dto.metrics.size != REQUIRED_METRIC_COUNT) {
            errors += "deck must declare exactly $REQUIRED_METRIC_COUNT metrics, has ${dto.metrics.size}"
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
        val displays = dto.metrics.associate { spec ->
            spec.key to spec.display.toDisplayOrNull().also { display ->
                if (display == null) errors += "metric '${spec.key}' has unknown display '${spec.display}'"
            }
        }
        for (spec in dto.metrics) {
            if (spec.unit.isBlank()) {
                errors += "metric '${spec.key}' has no unit"
            }
        }
        if (dto.cards.size != REQUIRED_CARD_COUNT) {
            errors += "deck must have exactly $REQUIRED_CARD_COUNT cards, has ${dto.cards.size}"
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
            name = dto.name,
            metrics = dto.metrics.map { spec ->
                MetricSpec(
                    key = MetricKey(spec.key),
                    label = spec.label,
                    unit = spec.unit,
                    direction = directions.getValue(spec.key)!!,
                    display = displays.getValue(spec.key)!!,
                )
            },
            cards = dto.cards.map { card ->
                Card(
                    id = card.id,
                    name = card.name,
                    stats = card.stats.mapKeys { (key, _) -> MetricKey(key) }.mapValues { (_, value) -> StatValue(value) },
                    image = CardImage(
                        file = card.image.file,
                        licence = card.image.licence,
                        author = card.image.author,
                        sourceUrl = card.image.sourceUrl,
                    ),
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

    private fun String.toDisplayOrNull(): MetricDisplay? = when (this) {
        "RAW" -> MetricDisplay.RAW
        "YEARS_SINCE_VALUE" -> MetricDisplay.YEARS_SINCE_VALUE
        else -> null
    }
}
