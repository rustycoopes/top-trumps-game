package com.toptrumps.app.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.toptrumps.app.theme.CardPalette
import com.toptrumps.app.theme.DisplayFontFamily

/**
 * The pure card front — no Coil, no assets, no `MaterialTheme.colorScheme` (every colour comes
 * from [palette], per card-visual-identity TDD decision 1 / story 20's dark-mode invariance). The
 * photo is a slot rather than a `Painter` so this composable is both `@Preview`-able (an asset URL
 * can't resolve in Studio) and Robolectric-testable with zero Coil on the test classpath — see the
 * card-image-slot ADR. `null` [onChooseStat] means read-only, which is what makes "the mini variant
 * has no clickable rows" true by construction rather than a separate check.
 *
 * [geometry] drives everything, including which layout renders: [CardVariant.MINI] shows no stat
 * table at all, reallocating its share of the height to the image window (Open Question 4's
 * reading) — [geometry]'s own height formula already lands mini at an exact 2:3 box regardless of
 * width, since a mini card is passed `minRowHeight = 0.dp` and the row floor therefore never binds.
 */
@Composable
internal fun TrumpCard(
    content: CardContent,
    palette: CardPalette,
    geometry: CardGeometry,
    modifier: Modifier = Modifier,
    withShadow: Boolean = false,
    onChooseStat: ((metricKey: String) -> Unit)? = null,
    image: @Composable (Modifier) -> Unit,
) {
    CompositionLocalProvider(LocalDensity provides rememberCappedDensity()) {
        CardChrome(palette = palette, geometry = geometry, modifier = modifier, withShadow = withShadow) {
            Column(Modifier.fillMaxSize()) {
                CardTitleBanner(title = content.title, palette = palette, height = geometry.bannerHeight)

                val isMini = geometry.variant == CardVariant.MINI
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(geometry.imageZoneHeight)
                        .padding(CardImageBorderWidth)
                        .clip(RoundedCornerShape(CardCornerRadius / 2))
                        .border(CardImageBorderWidth, CardImageBorderColor, RoundedCornerShape(CardCornerRadius / 2)),
                ) {
                    image(Modifier.fillMaxSize())
                }

                if (!isMini) {
                    Column(Modifier.fillMaxWidth().height(geometry.tableHeight).background(palette.accent.copy(alpha = CardTextScrimAlpha))) {
                        content.rows.forEach { row ->
                            StatRowView(row = row, height = geometry.rowHeight, palette = palette, onChooseStat = onChooseStat)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardTitleBanner(title: String, palette: CardPalette, height: Dp) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height).background(palette.accent.copy(alpha = CardTextScrimAlpha)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = palette.onAccent,
            style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How much a tied-and-excluded (or pending-choice) row dims — enough to read as "not choosable" without losing the value underneath it. */
private const val DisabledRowAlpha = 0.4f

private val QuietTint = Color.Black.copy(alpha = 0.06f)
private fun outcomeTint(outcome: RowOutcome): Color = when (outcome) {
    RowOutcome.WIN -> Color(0xFF2E7D32).copy(alpha = 0.35f)
    RowOutcome.LOSE -> Color(0xFFC62828).copy(alpha = 0.35f)
    RowOutcome.TIE -> Color(0xFF616161).copy(alpha = 0.35f)
}

/**
 * One stat row: `LABEL (UNIT) …… VALUE ▲` read-only or choosable, or — at the reveal, when
 * [StatRow.comparisonValue] is non-null — both players' values with the decided row (
 * [StatRow.outcome] non-null) loud and the other four quiet. Clickable only when [onChooseStat] is
 * non-null, so a read-only context (mini never reaches here at all; reveal/pile pass `null`) never
 * exposes a tap target. `Modifier.clickable(enabled = false)` marks the row [SemanticsProperties.Disabled]
 * for accessibility services, but still leaves an `OnClick` action in the merged semantics tree — a
 * Compose UI test's `performClick()` drives that action directly, bypassing the real touch
 * gesture's own enabled check, so the callback itself re-checks [StatRow.enabled] rather than
 * relying on `clickable` alone to gate it. A dimmed, non-tappable row (tied-and-excluded, or the
 * whole card while a choice is in flight) only ever means "not choosable right now" in an
 * interactive context — read-only contexts (mini, reveal, pile) always report every row `enabled`
 * regardless of this dimming, since [onChooseStat] being `null` there already makes tappability
 * moot (card-visual-identity WBS slice-4 acceptance criteria).
 */
@Composable
private fun StatRowView(
    row: StatRow,
    height: Dp,
    palette: CardPalette,
    onChooseStat: ((String) -> Unit)?,
) {
    var rowModifier = Modifier
        .fillMaxWidth()
        .height(height)
        .testTag("stat-row-${row.metricKey}")

    if (onChooseStat != null) {
        rowModifier = rowModifier
            .alpha(if (row.enabled) 1f else DisabledRowAlpha)
            .clickable(enabled = row.enabled, onClickLabel = row.label) {
                if (row.enabled) onChooseStat(row.metricKey)
            }
    }
    row.outcome?.let { outcome ->
        rowModifier = rowModifier.semantics { stateDescription = outcome.name }.background(outcomeTint(outcome))
    } ?: run {
        if (row.comparisonValue != null) rowModifier = rowModifier.background(QuietTint)
    }
    rowModifier = rowModifier.padding(horizontal = 8.dp)

    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${row.label} (${row.unit})",
            color = palette.onAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.comparisonValue != null) {
            Text(row.value, color = palette.onAccent, fontWeight = if (row.outcome != null) FontWeight.Bold else FontWeight.Normal)
            Text(directionArrow(row.direction), color = palette.onAccent, modifier = Modifier.padding(horizontal = 4.dp))
            Text(row.comparisonValue, color = palette.onAccent)
        } else {
            Text(row.value, color = palette.onAccent)
            Text(directionArrow(row.direction), color = palette.onAccent, modifier = Modifier.padding(start = 4.dp))
        }
        row.outcome?.let { Text(it.name, color = palette.onAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp)) }
    }
}

private fun directionArrow(direction: RowDirection): String = if (direction == RowDirection.HIGHER_WINS) "▲" else "▼"
