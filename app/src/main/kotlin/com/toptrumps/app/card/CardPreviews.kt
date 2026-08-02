package com.toptrumps.app.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.toptrumps.app.theme.CardPalette
import com.toptrumps.session.RemoteCardFace
import com.toptrumps.session.RemoteMetricSpec
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Every state this slice's acceptance criteria call out for `@Preview`: hero/reveal/mini widths, a
 * long name that must ellipsise rather than wrap or clip, all three reveal outcomes, the mini
 * variant, and the back. Colour/crop/spacing aren't asserted by any test (PRD/TDD's standing
 * convention) — these previews plus the debug gallery are how that gets judged instead.
 */
private val PreviewPalette = CardPalette(accent = Color(0xFFFFC400), onAccent = Color(0xFF1A1A1A))

private val PreviewMetrics = listOf(
    RemoteMetricSpec("topSpeed", "TOP SPEED", "MPH", "HIGH_WINS"),
    RemoteMetricSpec("power", "POWER", "BHP", "HIGH_WINS"),
    RemoteMetricSpec("weight", "WEIGHT", "KG", "LOW_WINS"),
    RemoteMetricSpec("year", "AGE", "YEARS", "LOW_WINS", "YEARS_SINCE_VALUE"),
    RemoteMetricSpec("price", "PRICE", "USD", "LOW_WINS"),
)

private val PreviewCard = RemoteCardFace(
    id = "cb500",
    name = "Honda CB500",
    stats = mapOf("topSpeed" to 122.0, "power" to 47.0, "weight" to 194.0, "year" to 2019.0, "price" to 6500.0),
)

private val PreviewLongNameCard = PreviewCard.copy(name = "Kawasaki Ninja H2")

private val PreviewOpponentStats = mapOf("topSpeed" to 299.0, "power" to 200.0, "weight" to 216.0, "year" to 2015.0, "price" to 55000.0)

/** Fixed, not `Clock.System` — a preview shouldn't drift year to year just because the YEARS_SINCE_VALUE row is on screen. Midyear, so `yearsSince`'s local-timezone conversion can't land on a different calendar year depending on Studio's host timezone. */
private val PreviewClock = object : Clock {
    override fun now() = Instant.parse("2026-06-15T12:00:00Z")
}

private val PreviewImageSlot: @Composable (Modifier) -> Unit = { modifier -> Box(modifier.background(Color(0x33000000))) }

@Preview(name = "Hero — 380dp, choosable, one row disabled")
@Composable
private fun TrumpCardHeroPreview() {
    val geometry = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp)
    val content = cardContentOf(
        card = PreviewCard,
        metrics = PreviewMetrics,
        now = PreviewClock,
        availableMetrics = setOf("topSpeed", "power", "weight", "price"), // "year" tied and excluded
    )
    TrumpCard(content = content, palette = PreviewPalette, geometry = geometry, onChooseStat = {}, image = PreviewImageSlot)
}

@Preview(name = "Hero — 380dp, long name must ellipsise not wrap")
@Composable
private fun TrumpCardHeroLongNamePreview() {
    val geometry = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp)
    val content = cardContentOf(card = PreviewLongNameCard, metrics = PreviewMetrics, now = PreviewClock)
    TrumpCard(content = content, palette = PreviewPalette, geometry = geometry, onChooseStat = {}, image = PreviewImageSlot)
}

@Preview(name = "Mini — 96dp, no table, no clickable rows")
@Composable
private fun TrumpCardMiniPreview() {
    val geometry = cardGeometry(width = 96.dp, variant = CardVariant.MINI)
    val content = cardContentOf(card = PreviewCard, metrics = PreviewMetrics, now = PreviewClock)
    TrumpCard(content = content, palette = PreviewPalette, geometry = geometry, image = PreviewImageSlot)
}

@Preview(name = "Reveal — 190dp, decided row WIN")
@Composable
private fun TrumpCardRevealWinPreview() = RevealPreview(RowOutcome.WIN)

@Preview(name = "Reveal — 190dp, decided row LOSE")
@Composable
private fun TrumpCardRevealLosePreview() = RevealPreview(RowOutcome.LOSE)

@Preview(name = "Reveal — 190dp, decided row TIE")
@Composable
private fun TrumpCardRevealTiePreview() = RevealPreview(RowOutcome.TIE)

@Composable
private fun RevealPreview(outcome: RowOutcome) {
    val geometry = cardGeometry(width = 190.dp, variant = CardVariant.REVEAL, minRowHeight = 28.dp)
    val content = cardContentOf(
        card = PreviewCard,
        metrics = PreviewMetrics,
        now = PreviewClock,
        comparison = PreviewOpponentStats,
        decidingMetric = "topSpeed",
        decidedOutcome = outcome,
    )
    TrumpCard(content = content, palette = PreviewPalette, geometry = geometry, image = PreviewImageSlot)
}

@Preview(name = "Back — 380dp, geometrically identical to the hero front")
@Composable
private fun TrumpCardBackPreview() {
    val geometry = cardGeometry(width = 380.dp, variant = CardVariant.HERO, minRowHeight = 48.dp)
    TrumpCardBack(deckName = "Motorcycles", palette = PreviewPalette, geometry = geometry)
}
