# Slice 3 — The Card Composable

> Part of the `card-visual-identity` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** A complete, reusable Top Trumps card — title banner, framed photo, five-row stat table
— that looks right in every state and is provably interaction-correct, before it's wired into any
live screen.

## What to build

The card composable itself: a title banner, a centre-cropped photo window, and a five-row stat table
reading `LABEL (UNIT) …… VALUE ▲`, in a full variant (with the table) and a mini variant (frame,
photo, title only). A card back drawn from the deck's accent colour, geometrically identical to the
front. Stat rows are tap targets only in the full variant when interaction is enabled; every other
context is read-only.

Alongside the composable: `@Preview`s covering every state (chosen/unavailable row, win/lose/tie
reveal marking, mini, back, at a couple of representative widths), and a debug-build-only gallery
screen — reachable from Settings — that renders every card in a real deck at real size from real
assets, for the things a preview can't show: actual photos, actual crops, long names.

This slice deliberately touches no screen a player currently sees. It's verified by the gallery and by
tests, not by playing a match.

## Design notes

**Composable shape** — [ADR: card image slot](../../adr/card-visual-identity-card-image-slot.md).
Two layers: a pure `TrumpCard`/`TrumpCardBack` that takes its photo as an
`image: @Composable (Modifier) -> Unit` slot and knows nothing about Coil or assets, plus a thin
`AssetTrumpCard` wrapper that is the **only** place in the codebase mentioning
`file:///android_asset/...` and owns the explicit Coil decode-size guarantee (today's
`WinPileGrid` comment about 30 full-resolution bitmaps OOMing a mid-range phone still applies — don't
lose that guarantee in the rewrite). `CardImage` in `MatchScreen.kt` is deleted once
`AssetTrumpCard` replaces every call site. The slot is what makes the card both `@Preview`-able
(`file:///android_asset` can't resolve in Studio) and Robolectric-testable with zero Coil on the test
classpath — a stub `Box` is enough.

**The stability trap in the wrapper.** The image slot lambda must be written *inline* inside
`AssetTrumpCard`, never returned from a plain factory function — a lambda returned from a
non-composable function is a fresh instance every call, so `TrumpCard` never skips. `CardContent` and
`StatRow` hold a `List` and need `@Immutable`; they live in `com.toptrumps.app` (Compose-compiled), so
that's the right lever rather than touching `compose-stability-config.conf`.

**Null `onChooseStat` means read-only.** This makes "the mini variant exposes no clickable rows"
true by construction, not by a separate check, and avoids a parameter type that would need
`remember`-ing at every call site to avoid defeating skipping.

**Values arrive pre-formatted.** `cardContentOf(card, metrics, now: Clock, …)` is a pure mapping
function, separate from the card itself — `formatStat`'s `YEARS_SINCE_VALUE` branch calls
`Clock.System` today, and if that ran inside the card, both `@Preview`s and the Robolectric tests
would be date-dependent. An injectable `Clock` also closes a testing gap that exists today.

**The card must never read `MaterialTheme.colorScheme`.** Every colour comes from the `CardPalette`
parameter — PRD story 20 requires the card keep its printed colours in dark mode while chrome adapts,
and sourcing colour any other way makes that silently breakable.

**Font-scale cap** — TDD decision 3. Clamp `fontScale` only, preserving `density`, inside
`TrumpCard`/`TrumpCardBack` so no call site can forget it:
```kotlin
Density(density = current.density, fontScale = min(current.fontScale, 1.3f))
```
Reconstructing density (or `Density(1f, scale)`) silently changes `dp` too — don't. Note API 34+
applies font scaling non-linearly, so this cap is deliberately linear and won't be pixel-identical to
the system's own 1.3×; that's acceptable, just don't let it get chased as a bug later. This BOM
(1.7.x) has no `BasicText` auto-shrink, so overflow is handled by layout — the label ellipsises, the
value never does.

**Geometry comes from Slice 2** — pass a `CardGeometry` in, don't compute one here. Both faces of the
card back/front pairing must be handed the *same* `CardGeometry` instance.

## Blocked by

- [Slice 1](slice-1-test-infrastructure-spike.md) — the Robolectric seam this slice's tests run on.
- [Slice 2](slice-2-theme-deck-data-foundation.md) — `CardPalette`, `cardGeometry`, the shared
  `ImageLoader`, and deck theme resolution all come from here.

## Acceptance criteria

- [ ] `TrumpCard` renders correctly at hero (~380dp), reveal (~190dp), and mini (~96dp) widths in
      `@Preview`, including a long name ("Kawasaki Ninja H2") not clipping or wrapping badly
- [ ] `TrumpCardBack` is geometrically identical to `TrumpCard` given the same `CardGeometry` —
      **needs a physical device or the gallery to confirm no visible seam if front/back were swapped**
- [ ] Mini variant renders frame + image + title with no stat table and no clickable rows
- [ ] A stat row's direction arrow, unit, and value are all visible and correctly aligned at 1.0× and
      1.3×-capped font scale — **needs a physical device with font scale changed in system settings**
- [ ] The reveal marking (chosen row loud with WIN/LOSE/TIE, other four quiet with both values) renders
      correctly for all three outcomes
- [ ] Debug gallery screen, reachable from Settings in debug builds only, renders every card of a real
      deck at real size from real assets — **needs a physical device to judge actual photo crops**
- [ ] Robolectric test: every enabled stat row is exposed as clickable and invokes the choose-stat
      callback with the correct metric key on tap
- [ ] Robolectric test: a disabled row is exposed as disabled and tapping it does not invoke the
      callback
- [ ] Robolectric test: the mini variant exposes zero clickable rows
- [ ] Robolectric test: after a reveal, the decided row carries a win/lose/tie `stateDescription` and
      the other four do not
- [ ] `./gradlew :app:test` runs all of the above green under the infrastructure proven in Slice 1

## Testing

**Robolectric + Compose semantics, `:app`** (Seam C from the TDD) — the interaction contract only:
which rows are clickable, correct callback and metric key, disabled rows are inert, mini has no tap
targets, win/lose/tie lands as a semantics label on the right row. `Modifier.clickable(enabled = false)`
emits `SemanticsProperties.Disabled` and no `OnClick` action, so two of these assertions are close to
free. Tests use the stub image slot — no Coil on the classpath. Prior art: none in this repo; this is
the first Compose test, built on Slice 1's proven configuration.

**Not covered by automated tests, by design:** colour, crop quality, spacing, font rendering, contrast.
These are `@Preview`, the debug gallery, and a manual on-device pass — matching this project's
standing convention for anything visual.
