# Slice 2 — Theme, Deck Data & Graph Plumbing Foundation

> Part of the `card-visual-identity` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** Every non-visual building block the card composable and match screen will need — deck
theme data, the app's first real theme, and the plumbing to reach both — with nothing in the running
game changed yet except a cold-start dark-mode fix.

## What to build

**Deck theme data.** An optional `theme` block in `manifest.json` (accent colour, on-accent colour,
optional `heroCardId`), parsed into a `DeckTheme` on `Deck` in `:core:rules`. A deck with no `theme`
block gets a classic yellow-on-white default and continues to load exactly as it does today.

**Theme foundation.** The app's first `MaterialTheme` — `Color.kt`/`Type.kt`/`Theme.kt` under a new
`theme/` package, a bundled display font, and `MainActivity` wrapping `AppRoot` in it.

**Graph plumbing.** `AppGraph` gains a memoised way to resolve a deck's theme by id, resolves
`heroCardId` into a representative image at the same point `listDecks()` already loads each deck, and
owns a single shared `ImageLoader` instead of `MatchScreen` building its own.

**Card geometry maths.** The pure functions that compute a card's zone heights from its width and a
minimum row height — no Compose runtime involved, just `Dp` arithmetic.

## Design notes

**Theme data shape and validation** — [ADR: deck theme block](../../adr/card-visual-identity-deck-theme-block.md).
Colours are an `ArgbColor` value class (`@JvmInline value class ArgbColor(val argb: Int)`), not hex
strings — matching the `MetricKey`/`StatValue` idiom already in `:core:rules` and making the `:app`
conversion total rather than needing an unreachable fallback branch. `DeckLoader.parse` accepts
`#RRGGBB` only and forces alpha to `0xFF`. The theme **never crosses the wire** — both devices resolve
it from their own local manifest; `RemoteCardFace`/`RemoteMetricSpec` are untouched.

**Validation severity — this is the load-bearing decision in this slice.** `AppGraph.listDecks()` is
`mapNotNull { Invalid -> null }`: a deck that fails validation vanishes from the picker with no message
anywhere. That's correct for everything validated today (30 cards, 5 metrics, every image resolving —
all functional), but the `theme` block is cosmetic, so the same treatment would let one typo'd hex
digit silently remove an entire 30-card deck. Therefore:
- **At runtime**, a malformed accent/`onAccent` hex or an unmatched `heroCardId` degrades to
  `DeckTheme.DEFAULT` (or the deck's first card) — the deck stays `Valid` and playable.
- **In CI**, a new test enumerates every folder under `/decks` and asserts each theme block parses
  *without* degrading, so the same typo fails the build. This is also the first test coverage
  `decks/lucys-youtubers/` gets at all — only `test-deck` and `motorcycles` have deck tests today.

**Graph plumbing** — TDD decision 6. `MatchScreen` and the guest path only ever have `deckId: String`,
never a loaded `Deck` — the guest in particular never loads one locally, it only exchanges a hash — so
`AppGraph.deckTheme(deckId)` must be a cache read, not a fresh `DeckLoader.load` per composition
(that's 30 image-stream opens per deck). `heroCardId` resolution belongs in `listDecks()`, not the
picker, because only `listDecks()` has the full `Deck.cards` available — `DeckSummary` doesn't carry
it. The `ImageLoader` hoist is a **correctness fix, not a nicety**: once the deck picker shows images
in Slice 5, either it builds a second loader (duplicate memory cache) or shares `MatchScreen`'s —
which `MatchScreen`'s existing `DisposableEffect` would then shut down out from under it. Move
ownership to `AppGraph` now, alongside `soundEffects`, released in `close()`.

**Card geometry** — full derivation in [ADR: card geometry](../../adr/card-visual-identity-card-geometry.md).
With banner 10% / image 50% / table 40% of a nominal `h = 1.5w`, nominal row height is `0.12w`, which
equals 48dp exactly at `w = 400dp` — **below 400dp-wide the floor always binds; that's the normal case
on a phone, not an edge case.** `cardGeometry(width, variant, minRowHeight, maxHeight)` grows the card
past 2:3 by default and only lets the image window absorb the difference under an explicit height
ceiling — this **supersedes** the PRD's self-contradictory "48dp floor, window absorbs, card runs
taller" (those two outcomes can't both be true). `solveCardWidth(maxWidth, maxHeight, minRowHeight)`
is the inverse, picking the largest card that fits. Zone heights are set with exact `Modifier.size`,
never `Modifier.weight` — weights would let two composables computing the "same" geometry diverge,
which is exactly what must not happen between a card's front and back.

**Font and cold-start fix.** `res/font/` filenames must be lowercase/underscore-only (aapt2 rejects
`Anton-Regular.ttf`). Anton (SIL OFL 1.1) is the lead candidate; commit `OFL.txt` alongside it. Once a
real `MaterialTheme`/dark theme exists, the manifest's
`@android:style/Theme.Material.Light.NoActionBar` will produce a white flash on cold start in dark
mode — fix with `res/values/themes.xml` + `res/values-night/themes.xml` in the same pass, since it's
the natural place to catch it.

## Blocked by

None — can start immediately, in parallel with [Slice 1](slice-1-test-infrastructure-spike.md).

## Acceptance criteria

- [ ] A deck manifest with a `theme` block loads with that theme; one with no `theme` block loads with
      the default; both remain `DeckValidationResult.Valid`
- [ ] A malformed accent hex, malformed `onAccent`, or unmatched `heroCardId` degrades to the default
      at runtime and the deck stays `Valid` — verified by a unit test, not just inspection
- [ ] A new all-decks test loads every folder under `/decks` and fails if any theme block degrades,
      catching the same typo in CI that runtime tolerates
- [ ] `decks/motorcycles/manifest.json` and `decks/lucys-youtubers/manifest.json` both validate,
      whether or not they carry a `theme` block at the time this lands
- [ ] `MainActivity` wraps `AppRoot` in `MaterialTheme` via a new `TopTrumpsTheme`; app no longer
      renders on Material3 defaults
- [ ] Cold start in dark mode no longer flashes a light background before Compose's first frame
- [ ] `AppGraph.deckTheme(deckId)` returns the correct theme without re-invoking `DeckLoader.load` on
      every call (verify via a call-count assertion or code inspection of the memoisation)
- [ ] Exactly one `ImageLoader` exists in the app, owned by `AppGraph`, released in `close()`;
      `MatchScreen`'s per-screen construction and `shutdown()` are deleted
- [ ] `cardGeometry`/`solveCardWidth` pure functions exist with unit tests covering: the floor-binding
      case (width-bound), the non-binding case (height-bound), the crossover point, and that two calls
      with identical inputs are `==`-equal (front/back identity)
- [ ] Bundled font renders correctly on-device (a filename or resource-linking mistake would only show
      as a build or runtime failure, not a compile error)

## Testing

**JVM, `:core:decks`** — theme parsing and the lenient/strict distinction, extending `DeckLoaderTest`
and `MotorcyclesDeckTest`. This is Seam B from the TDD.

**JVM, new/existing module** — `cardGeometry`/`solveCardWidth` as plain functions over `Dp` (a
`compose-ui-unit` value class with no Android dependency), needing no Robolectric and no Compose
runtime. This is Seam A from the TDD — the cheapest and highest-value coverage in the whole feature,
and it should exist before Slice 3's Robolectric work starts.

No UI test coverage in this slice — nothing here is yet wired into a screen a player sees, beyond the
theme wrap and the flash fix, which are verified on-device.
