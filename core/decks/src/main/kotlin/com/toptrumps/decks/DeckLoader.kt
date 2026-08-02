package com.toptrumps.decks

import com.toptrumps.rules.ArgbColor
import com.toptrumps.rules.Card
import com.toptrumps.rules.CardImage
import com.toptrumps.rules.Deck
import com.toptrumps.rules.DeckTheme
import com.toptrumps.rules.Direction
import com.toptrumps.rules.MetricDisplay
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.MetricSpec
import com.toptrumps.rules.StatValue
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest

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

    /**
     * [load], but for the common call site where [deckId] came from [DeckSource.listDecks] and
     * therefore failing validation would mean the deck folder rotted out from under a running app
     * rather than anything a caller can meaningfully recover from.
     */
    public fun loadOrThrow(source: DeckSource, deckId: String): Deck =
        when (val result = load(source, deckId)) {
            is DeckValidationResult.Valid -> result.deck
            is DeckValidationResult.Invalid -> error("deck '$deckId' failed validation: ${result.errors}")
        }

    /**
     * Hex SHA-256 of `manifest.json`'s raw bytes — covers the manifest only, not the (much
     * larger) images, since this guards against version skew between two copies of our own app
     * rather than tampering (TDD §7). `null` if the manifest can't be read at all, which the
     * two-device handshake treats the same as a mismatch.
     */
    public fun manifestHash(source: DeckSource, deckId: String): String? {
        val bytes = try {
            source.open(deckId, "manifest.json").use { it.readBytes() }
        } catch (_: IOException) {
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
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
            theme = dto.theme.toDeckTheme(cardIds),
        )
        return DeckValidationResult.Valid(deck)
    }

    /**
     * The `theme` block is cosmetic, so unlike everything else [parse] validates, a malformed
     * field degrades to [DeckTheme.DEFAULT]'s value rather than failing the whole deck — see the
     * deck-theme-block ADR. `null` (block absent, or a field within it absent) degrades the same
     * way as a value that fails to parse. An unmatched `heroCardId` degrades to `null` here — not
     * to the deck's first card, which needs [Deck.cards] and is `AppGraph`'s job (TDD decision 6).
     */
    private fun DeckThemeDto?.toDeckTheme(knownCardIds: List<String>): DeckTheme {
        if (this == null) return DeckTheme.DEFAULT
        return DeckTheme(
            accent = accent?.let(::parseArgbHex) ?: DeckTheme.DEFAULT.accent,
            onAccent = onAccent?.let(::parseArgbHex) ?: DeckTheme.DEFAULT.onAccent,
            heroCardId = heroCardId?.takeIf { it in knownCardIds },
        )
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

private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")

/**
 * Parses `#RRGGBB` into an opaque, fully-alpha [ArgbColor] — see the deck-theme-block ADR. `null`
 * if [hex] isn't exactly that shape, which callers treat as "degrade to the default colour" at
 * runtime and "fail the build" in the all-decks CI test (same function, two verdicts).
 */
internal fun parseArgbHex(hex: String): ArgbColor? {
    if (!HEX_COLOR.matches(hex)) return null
    val rgb = hex.substring(1).toInt(16)
    return ArgbColor((0xFF shl 24) or rgb)
}
