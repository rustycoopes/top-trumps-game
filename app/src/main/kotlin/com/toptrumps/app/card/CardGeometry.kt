package com.toptrumps.app.card

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * Which slot a card is being sized for. Carried through into [CardGeometry] purely so a caller
 * building the card composable from a stored geometry still knows which variant produced it —
 * [minRowHeight] is the single knob that actually drives the arithmetic across hero (48dp), reveal
 * (28dp) and pile (0dp); see the card-geometry ADR.
 */
internal enum class CardVariant { HERO, REVEAL, MINI }

/**
 * Every zone height for one card, at one width — see the card-geometry ADR. A `data class`
 * deliberately: two calls with identical arguments produce `==`-equal instances, which is what
 * makes front/back pixel identity structural rather than conventional — hand both faces of a flip
 * the same [CardGeometry] instance instead of recomputing it per face.
 */
internal data class CardGeometry(
    val width: Dp,
    val variant: CardVariant,
    val bannerHeight: Dp,
    val imageHeight: Dp,
    val rowHeight: Dp,
    val tableHeight: Dp,
    val height: Dp,
)

/** `row = max(0.12w, minRow)` — the nominal row height at the ADR's `h = 1.5w`, banner 10% / image 50% / table 40% split. */
private const val ROW_WIDTH_COEFFICIENT = 0.12f

/** Banner's share of width at the nominal `h = 1.5w` (`0.10 * 1.5`) — fixed regardless of whether the floor binds. */
private const val BANNER_WIDTH_COEFFICIENT = 0.15f

/** Image window's nominal share of width at `h = 1.5w` (`0.50 * 1.5`) — held here unless [cardGeometry]'s `maxHeight` forces the window to absorb instead. */
private const val NOMINAL_IMAGE_WIDTH_COEFFICIENT = 0.75f

/** Banner + nominal image combined (`0.15 + 0.75`) — the ADR's `0.9w` term in the floor-binding height formula. */
private const val GROWN_WIDTH_COEFFICIENT = 0.9f

/** `h = 1.5w` — the nominal 2:3 portrait aspect when the row floor doesn't bind. */
private const val NOMINAL_HEIGHT_COEFFICIENT = 1.5f

private const val ROW_COUNT = 5

/**
 * Computes a card's zone heights for [width] and [variant], per the card-geometry ADR:
 * `row = max(0.12w, minRowHeight)`, and the card grows past its nominal 2:3 shape (holding the
 * image window at its nominal `0.75w`) rather than shrinking — unless [maxHeight] is specified and
 * the grown height would exceed it, in which case the image window absorbs the difference instead.
 * Pure `Dp` arithmetic: no Compose runtime, no measuring, safe to call outside composition.
 */
internal fun cardGeometry(
    width: Dp,
    variant: CardVariant,
    minRowHeight: Dp = 0.dp,
    maxHeight: Dp = Dp.Unspecified,
): CardGeometry {
    val row = (width * ROW_WIDTH_COEFFICIENT).coerceAtLeast(minRowHeight)
    val banner = width * BANNER_WIDTH_COEFFICIENT
    val nominalImage = width * NOMINAL_IMAGE_WIDTH_COEFFICIENT
    val table = row * ROW_COUNT.toFloat()
    val grownHeight = banner + nominalImage + table

    val image: Dp
    val height: Dp
    if (maxHeight.isSpecified && grownHeight > maxHeight) {
        image = (maxHeight - banner - table).coerceAtLeast(0.dp)
        height = maxHeight
    } else {
        image = nominalImage
        height = grownHeight
    }

    return CardGeometry(
        width = width,
        variant = variant,
        bannerHeight = banner,
        imageHeight = image,
        rowHeight = row,
        tableHeight = table,
        height = height,
    )
}

/**
 * The inverse of [cardGeometry]: the largest card width that fits within [maxWidth] × [maxHeight]
 * at [minRowHeight]. [cardGeometry]'s height is continuous and monotonic in width (the ADR's whole
 * point), so the height-bound width is a direct algebraic inverse, not a search.
 */
internal fun solveCardWidth(maxWidth: Dp, maxHeight: Dp, minRowHeight: Dp): Dp {
    // The width at which the row floor and the nominal 0.12w row exactly coincide, and the height
    // at that width — both formulas agree there, so it's the split point between the two branches.
    val crossoverWidth = minRowHeight / ROW_WIDTH_COEFFICIENT
    val crossoverHeight = crossoverWidth * NOMINAL_HEIGHT_COEFFICIENT

    val heightBoundWidth = if (maxHeight <= crossoverHeight) {
        // Floor-bound branch: height = 0.9w + 5*minRow -> w = (height - 5*minRow) / 0.9
        (maxHeight - minRowHeight * ROW_COUNT.toFloat()) / GROWN_WIDTH_COEFFICIENT
    } else {
        // Non-binding branch: height = 1.5w -> w = height / 1.5
        maxHeight / NOMINAL_HEIGHT_COEFFICIENT
    }

    return maxWidth.coerceAtMost(heightBoundWidth).coerceAtLeast(0.dp)
}
