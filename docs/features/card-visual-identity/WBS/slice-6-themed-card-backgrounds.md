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

_Not yet delivered._
