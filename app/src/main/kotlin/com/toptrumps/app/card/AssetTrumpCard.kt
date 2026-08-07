package com.toptrumps.app.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.toptrumps.app.theme.CardPalette

/**
 * The **only** place in the codebase that mentions `file:///android_asset/...` — see the
 * card-image-slot ADR. Owns the explicit Coil decode-size guarantee, derived from [geometry]: the
 * `WinPileGrid` comment this replaces already recorded that thirty full-resolution bitmaps would
 * OOM a mid-range phone, and that guarantee must not get lost in this rewrite.
 *
 * The slot lambda below is written *inline*, never returned from a plain factory function — a
 * lambda returned from a non-composable function is a fresh instance every call, so [TrumpCard]
 * would never skip and this repo's stability work would be silently undone during exactly the
 * animations this feature makes more expensive.
 */
@Composable
internal fun AssetTrumpCard(
    deckId: String,
    imageFile: String,
    content: CardContent,
    palette: CardPalette,
    geometry: CardGeometry,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    withShadow: Boolean = false,
    onChooseStat: ((metricKey: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // coerceAtLeast(1): CardGeometry.width can legitimately round to 0.dp (solveCardWidth's own
    // floor) during a transient zero-constraint layout pass — Coil's ImageRequest.size(Int, Int)
    // throws IllegalArgumentException synchronously (not just failing the async load) if either
    // dimension is <= 0, which would crash the composition rather than degrade gracefully.
    val imageWidthPx = with(density) { geometry.width.roundToPx() }.coerceAtLeast(1)
    val imageHeightPx = with(density) { geometry.imageZoneHeight.roundToPx() }.coerceAtLeast(1)

    TrumpCard(
        content = content,
        palette = palette,
        geometry = geometry,
        modifier = modifier,
        withShadow = withShadow,
        onChooseStat = onChooseStat,
        image = { imageModifier ->
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data("file:///android_asset/$deckId/$imageFile")
                    .size(imageWidthPx, imageHeightPx)
                    .build(),
                contentDescription = content.title,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        },
    )
}
