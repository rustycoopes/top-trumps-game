# Slice 6 — Themed Card Backgrounds

> Part of the `card-visual-identity` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md#8-themed-card-backgrounds-slice-6)

**Delivers:** A faded, per-deck-themed background image behind every card face, replacing today's
flat white/accent-only backing — on the two decks that already shipped as well as any new deck.

## What to build

Live use of the shipped flat-accent-colour card design surfaced a real complaint: the card still
reads as plain and bland even with its deck's accent colour applied. This slice adds a per-deck
illustrated background image, faded behind the card face, to give each deck real visual identity
beyond a flat colour — a road/engine motif for Motorcycles, a social-feed/play-button motif for
`lucys-youtubers`, and whatever motif fits each deck added after this slice (starting with the
Squishies deck from the `second-round-updates` feature, once that deck's manifest exists).

This directly reopens the original card-visual-identity PRD's "no texture or gloss bitmaps"
decision — see the [themed card backgrounds ADR](../../../adr/card-visual-identity-themed-card-backgrounds.md)
for why a static, per-deck, once-decoded bitmap doesn't reintroduce the per-frame-cost concern that
motivated that original ban.

Add an optional `theme.backgroundImage` field to the manifest schema (a filename, resolved the same
way `Card.image`/`heroCardId` already are), extend `DeckLoader` validation to cover it, generate one
background asset per deck (Motorcycles, `lucys-youtubers`, and any deck that exists by the time this
ships), and render it full-bleed behind the card face at a fixed reduced opacity with a scrim behind
the stat table so text keeps the existing 4.5:1 contrast bar.

Also add a drop shadow so the card visibly lifts off whatever now sits behind it, rather than reading
as flush with its background image or the surrounding screen. The original PRD already established a
**static drop shadow on the non-animating hero card only**; extend that same static shadow to every
other place a card is shown at rest — win-pile mini cards, deck-picker tiles, an opened full-size pile
card — since those are equally non-animating and currently get none. A card mid-flip or mid-slide
keeps the existing no-shadow rule unchanged (still a per-frame `Modifier.shadow` cost the original PRD
was right to avoid).

## Design notes

See [TDD decision 8](../TDD.md#8-themed-card-backgrounds-slice-6) and the ADR linked above for the
full technical reasoning. The load-bearing constraint to not violate: **the background bitmap is
decoded once per deck and shared across all 30 of that deck's cards** — never decoded per card. The
win-pile grid (30 mini cards) and deck picker must reuse the same cached bitmap, not trigger 30
separate decodes.

Background art is generated per deck, not sourced — the "real, cited, never invented" rule that
governs card *subject* photos (Motorcycles' motorcycles, YouTubers' creators) doesn't apply to
decorative chrome. No licence/author/source-citation fields are needed on `backgroundImage`, unlike
`Card.image`.

Follows TDD decision 7's existing validation posture unchanged: a malformed or unresolvable
`backgroundImage` degrades to no background at runtime (the deck still loads and plays), but fails
the existing all-decks CI test that already covers `theme` block validity.

## Blocked by

None — can start immediately. Retrofitting Motorcycles and `lucys-youtubers` needs no other slice.
Applying a background to the Squishies deck specifically depends on that deck's manifest existing
(`second-round-updates` slice 1), but that's a follow-up asset addition, not a blocker on this
slice's core mechanism.

## Acceptance criteria

- [ ] `theme.backgroundImage` is a new optional manifest field, resolved and validated the same way
      `heroCardId` is (cosmetic: degrades at runtime, fails CI)
- [ ] A background image is authored for Motorcycles and for `lucys-youtubers`, each thematically
      fitting its deck
- [ ] The background bitmap is decoded exactly once per deck, verified by whatever mechanism the
      implementer finds clearest (a cache assertion, or a manual check that the win-pile grid doesn't
      re-decode) — not per card
- [ ] The background renders faded/full-bleed behind the whole card face, with the stat table
      remaining at the existing 4.5:1 contrast bar against its text
- [ ] A deck with no `backgroundImage` (or one authored before this slice) continues to render its
      existing flat-accent card exactly as before — this field is additive, not required
- [ ] A malformed `backgroundImage` reference degrades to no background at runtime and is caught by
      the existing all-decks CI validation test
- [ ] No regression to flip/slide animation frame rate on the oldest available test device, verified
      manually the same way slice 4's animation rework was
- [ ] The existing hero-card static drop shadow is extended to win-pile mini cards, deck-picker
      tiles, and an opened full-size pile card — every static (non-animating) presentation gets one
- [ ] A card mid-flip or mid-slide still renders with no shadow, unchanged from today

## Testing

Extends the existing `DeckLoader.parse` seam (JVM, `:core:decks`) the same way `heroCardId`
validation already does: a manifest with a valid `backgroundImage` resolves; one with a missing file
degrades to `null` at runtime but fails the all-decks CI test.

Visual appearance (opacity, motif quality, contrast against real text) and animation frame behaviour
are manual, on-device concerns — same boundary this feature has held throughout (see the PRD's
Testing Decisions section).

## Delivered

Issue [#41](https://github.com/rustycoopes/top-trumps-game/issues/41), branch
`slice-6-themed-card-backgrounds`, 2026-08-06.

`theme.backgroundImage` is a new optional field on `DeckThemeDto`/`DeckTheme` (`:core:decks`,
`:core:rules`), resolved and validated exactly the way `heroCardId` already is: `DeckLoader.load`
degrades an unresolvable reference to `null` at runtime (deck still loads and plays), while
`AllDecksThemeTest` fails the build in CI on the same condition. `DeckLoader`'s per-card image
resolve check and the new background-image resolve check now share one `DeckSource.resolves()`
extension rather than duplicating the try/open/catch.

On the render side, `CardPalette` (`:app`) gained a `backgroundImage: String?` field alongside its
existing colours, so every existing call site that already threads a `CardPalette` through
(`MatchScreen`, `DeckPickerScreen`, `CardGalleryScreen`, the two-device match screen) picks up the
new background automatically via `DeckTheme.toCardPalette()` — no new parameter was threaded through
any screen. `AssetTrumpCard` renders it as a new `background` slot on `TrumpCard`/`CardChrome`,
full-bleed behind the card face at a fixed 25% alpha, with a fixed-alpha scrim (`CardTextScrimAlpha
= 0.85f`) applied unconditionally to the title banner and stat-table backgrounds — compositing that
scrim over the identical opaque accent colour already filling the card is a no-op when there's no
background image, so no deck-with-no-`backgroundImage` regression was possible by construction (also
confirmed by the full existing card/screen test suite passing unchanged). The background request is
sized from the same shared `CardGeometry` instance every grid/tile already hoists once, so Coil's own
memory cache (keyed by request data + size) naturally decodes it once per deck rather than once per
card — no hand-rolled cache was needed.

A new static drop shadow (`Modifier.shadow`, applied before `.clip()`) was wired via a `withShadow`
parameter threaded through `CardChrome`/`TrumpCard`/`AssetTrumpCard`, set `true` at exactly the four
acceptance-criteria call sites — the hero card, the win-pile mini-card grid, an opened full-size pile
card, and deck-picker tiles — and left at its default `false` everywhere else (the reveal pair, the
flip animation, the slide overlay, and the debug-only `CardGalleryScreen`). One correction to the
WBS's framing: the PRD's original "static drop shadow on the non-animating hero card only" was never
actually implemented in any prior slice (verified — no `Modifier.shadow` existed anywhere in the
codebase before this one), so this slice adds the shadow fresh at all four surfaces simultaneously
rather than literally extending a pre-existing hero shadow.

Background art for Motorcycles and `lucys-youtubers` was hand-composed with Python/Pillow (flat
vector shapes — a dashed lane-line and concentric gear rings for Motorcycles, a scattered
play-button/feed-card grid for `lucys-youtubers`) rather than sourced from an AI image generator: no
such service was available/authorized for this session. This is a divergence from the ADR's stated
preference for "illustrated" art over a procedural pattern, though the underlying architecture (a
static bitmap asset, decoded once per deck, committed alongside the manifest) is unaffected either
way — the art itself can be swapped for a better-illustrated asset later with no code change.

A code review (code-review-master + code-quality-guardian) caught and fixed one real bug before
merge: `CardGeometry.width`/`height` can legitimately round to `0.dp` during a transient
zero-constraint layout pass (`solveCardWidth`'s own `coerceAtLeast(0.dp)` floor), and Coil's
`ImageRequest.size(Int, Int)` throws `IllegalArgumentException` synchronously (not just failing the
async load) if either dimension is `<= 0`. This diff's new background request doubled that
pre-existing exposure by adding a second `.size()` call with the same unguarded inputs, so both the
foreground and background decode-size calculations in `AssetTrumpCard` now `coerceAtLeast(1)`.

Not independently verified in this session (both are the same manual/on-device boundary the PRD's
Testing Decisions section already draws): flip/slide frame-rate regression, and whether the
background image reads as visible "atmosphere" at the shipped alpha constants (0.25 image ×
0.85 scrim ≈ 3.75% of the stat-table zone's final pixel colour) rather than effectively invisible —
if it turns out too faint on-device, the fix is tuning `BackgroundImageAlpha`/`CardTextScrimAlpha`
in `CardFrame.kt`, not a code-structure change.
