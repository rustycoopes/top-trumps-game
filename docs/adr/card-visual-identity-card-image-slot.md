# The card composable takes its photo as a slot, with a thin asset-loading wrapper

**Status:** Proposed
**Date:** 2026-08-01
**Feature:** [`card-visual-identity`](../features/card-visual-identity/TDD.md)

## Context

One card composable is reused at five call sites (hero, side-by-side reveal, win-pile grid,
deck-picker tile, and mid-slide overlay). It has to satisfy three consumers that pull in opposite
directions:

1. **Production** renders a Coil `AsyncImage` from `file:///android_asset/<deckId>/<file>`, and must
   keep an explicit decode target — `WinPileGrid`'s existing comment records that 30 full-resolution
   bitmaps would OOM a mid-range phone.
2. **Robolectric semantics tests** must exercise the card's interaction contract without real image
   I/O or async image state settling before assertions become deterministic. The PRD names this seam
   as the feature's highest-risk new infrastructure.
3. **`@Preview` in Android Studio** cannot resolve `file:///android_asset/...` at all.

## Decision

**Two layers.** A pure `TrumpCard` that knows nothing about Coil or assets and takes the photo as a
slot, plus a thin `AssetTrumpCard` wrapper supplying it:

```kotlin
@Composable
internal fun TrumpCard(
    content: CardContent,
    palette: CardPalette,
    geometry: CardGeometry,
    modifier: Modifier = Modifier,
    onChooseStat: ((metricKey: String) -> Unit)? = null,
    image: @Composable (Modifier) -> Unit,
)
```

`AssetTrumpCard` is the **only** place in the codebase that mentions `file:///android_asset`, and it
owns the decode-size guarantee by deriving the image-window size from the `CardGeometry` and building
an explicit `ImageRequest.size(...)`. All five call sites use the wrapper, not the raw slot.

The slot is `(Modifier) -> Unit` rather than a `Painter`: a `Painter` would mean
`rememberAsyncImagePainter`, which loses Coil's constraint-derived size resolution — exactly the
safeguard the current explicit `size` argument exists to provide.

**The slot lambda must be written inline inside `AssetTrumpCard`**, never returned from a plain
factory function. The Compose compiler memoises composable lambdas only when written inline in a
composable scope; a lambda returned from a normal function is a fresh instance every call, so the
`image` parameter never compares equal and `TrumpCard` never skips.

## Alternatives considered

**`io.coil-kt.coil3:coil-test` with `FakeImageLoaderEngine`.** The strongest alternative, and one of
three design reviews recommended it — correctly noting that `CardImage` *already* takes an injected
`ImageLoader` parameter, so the DI seam the tests need exists in production code today and is already
load-bearing (it exists so the disk cache can be disabled). `coil-test:3.2.0` is an exact version
match for the repo's Coil.

Rejected for two reasons. First, **it does not solve previews** — `@Preview` cannot run test code, so
a fake engine leaves the preview problem entirely unsolved and previews are how this feature is
primarily iterated on. Second, it makes the highest-risk new infrastructure (Robolectric) depend on a
second thing that also has to work under Robolectric (Coil's async image pipeline, with
graphics-mode-dependent `BitmapFactory` shadows). The slot de-risks the named risk by construction
rather than by configuration.

The reviewer's objection to the slot — that it pushes Coil wiring, URL construction and
`ContentScale.Crop` out to all five callers, distorting production code in service of tests, and
duplicating the very centralisation the feature exists to deliver — is real and is **answered by the
wrapper**. Call sites see `AssetTrumpCard`, so nothing is duplicated and nothing is pushed outward;
the split is internal.

**Coil 3's `LocalAsyncImagePreviewHandler`.** Solves previews cleanly, but does nothing for
Robolectric, so it would have to be combined with `coil-test` anyway — two mechanisms where one
suffices.

**One composable with a `Painter`/`ImageBitmap` parameter instead of a slot.** Rejected: loses the
constraint-derived decode sizing (see above), and gives callers no way to supply placeholder or error
states.

## Consequences

**Easier:** the Robolectric seam needs **no Coil on the test classpath at all** — the image stub is a
plain `Box`, and every behaviour the PRD's seam asserts (rows clickable, disabled rows inert, mini
variant has no tap targets, win/lose/tie semantics) lives entirely in `TrumpCard`. Previews work with
the same stub. Asset-path construction and decode sizing stay in exactly one file.

**Harder:** two composables where the PRD implied one, and `AssetTrumpCard` itself is not covered by
the interaction seam. Acceptable because it contains no interaction — a `model` string, a
`ContentScale`, and a size derivation. A broken asset URL becomes untestable at this seam and is
caught instead by the debug gallery on a device, which is where a wrong image would be noticed anyway.

**A latent trap this creates:** each call site could in principle pass a wrong slot. The wrapper
prevents that for all five real call sites, but the raw `TrumpCard` remains callable. Keeping both
`internal` limits the blast radius to this module.

**A performance trap this creates:** the inline-lambda requirement is not obvious and its failure mode
is silent — no error, just a card that never skips, undoing slice 7's stability work during exactly
the animations this feature makes more expensive. It must be a code comment, not tribal knowledge.
