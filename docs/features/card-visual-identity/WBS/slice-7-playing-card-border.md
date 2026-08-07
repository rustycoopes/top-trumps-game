# Slice 7 — Playing-Card Border & Patterned Back

> Part of the `card-visual-identity` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)
>
> Scoped via a grilling conversation rather than a full `/to-prd`/`/to-design` pass — design notes
> below stand in for a TDD decision section.

**Delivers:** A visible white "card-stock" border around every card, front and back, and a
patterned card back (in place of today's flat accent-colour-plus-text back) — closer to how a real
playing card actually looks.

## What to build

Two user-facing reference images set the target look:

- **Front** ("Top Trumps Dragon card" reference): a white border frames the whole card, outside the
  existing accent-coloured content — not just the existing white border around the image window
  (`CardImageBorderWidth`/`CardImageBorderColor` in `CardFrame.kt`), which stays as-is.
- **Back** ("back of playing cards" reference sheet): a white border frames a solid-colour field
  filled with a repeating geometric pattern, with a clear circular/badge area in the centre reserved
  for text (several of the reference designs use exactly this — a plain medallion sitting inside a
  busy pattern).

Both faces already share one `CardChrome` composable (`CardFrame.kt`) specifically so front/back stay
pixel-identical for the flip animation — so this is one border change in `CardChrome`, not two. It
must apply to all three variants (`HERO`, `MINI`, `REVEAL`), front and back alike.

The new back pattern:

- **Diagonal stripes**, drawn programmatically (`Canvas`/`Path`), not a bundled image — consistent
  with `TrumpCardBack`'s existing "no bundled image, back and front stay geometrically identical"
  approach, and avoids generating/storing per-deck pattern art. Coloured from the deck's own
  `CardPalette.accent`, same as the current flat back — no new manifest field.
- **One shared motif for every deck** (only the colour varies), matching how the front card face
  already differentiates decks by accent colour alone, not by layout. Per-deck distinct motifs are an
  explicit non-goal for this slice — a cheap follow-up later if wanted, not something to
  over-engineer now for two real decks.
- **The centre medallion keeps the existing text** — the deck name and the "Tap to reveal" caption
  added in #47 — inside a clear badge/border so it doesn't fight the pattern for legibility. Both
  strings render **larger than today's sizes** (currently 22sp deck name / 14sp caption in
  `TrumpCardBack.kt` — both were called out as "pretty small" and should grow; exact sizes are an
  on-device legibility call, not a fixed spec here) and the medallion itself needs a visible edge
  (e.g. a border or contrasting fill) so it reads clearly against the stripe pattern behind it.

## Design notes

- `CardChrome`'s existing `CardHairlineColor`/`CardHairlineWidth` (a 1dp, 60%-alpha white hairline)
  stays — the new border is an additional, much more visible white margin, not a replacement. Exact
  thickness is a visual/on-device tuning call for the implementer, not specified here.
- The back's pattern replaces `TrumpCardBack`'s current plain `Box`/`Text` content — the outer
  `CardChrome` call (palette, geometry, `caption` parameter added in #47) is unchanged.
- No new manifest fields, no new assets, no new dependencies — this is pure Compose drawing code
  layered onto the existing `CardChrome`/`TrumpCardBack` structure.
- Out of scope: per-deck distinct back motifs, and the full-screen deck backdrop (that's
  [slice 8](slice-8-match-backdrop.md) — a separate concern despite both starting from the same
  background-image complaint).

## Blocked by

None — can start immediately, independent of slice 8.

## Acceptance criteria

- [ ] Every card — front and back, all three variants (hero/mini/reveal) — renders a clearly visible
      white border around its outer edge, in addition to the existing hairline
- [ ] The card back renders a diagonal-stripe pattern in the deck's accent colour, in place of today's
      flat colour fill
- [ ] The pattern is drawn programmatically (`Canvas`/`Path`), not a bundled image asset
- [ ] The deck name and "Tap to reveal" caption still render on the back, inside a clearly bordered
      medallion/badge, at a visibly larger size than today's 22sp/14sp
- [ ] The back's front/back geometry identity (same `CardGeometry` instance, same `CardChrome` frame)
      is unchanged — the flip animation still has no shape jump
- [ ] No regression to the existing flip animation frame rate, verified manually on-device
- [ ] Every existing card/screen test (`TrumpCardTest`, `CardAnimationsTest`, etc.) still passes

## Testing

Pattern drawing and border rendering are pure Compose visual output — no new JVM-testable logic
beyond what a Robolectric/Compose semantics test can already cover (e.g. the medallion badge still
carries the "Tap to reveal" click affordance, same as `CardAnimationsTest`'s existing coverage
pattern). Visual appearance (pattern legibility, border weight, medallion contrast) is a manual,
on-device concern — same boundary this feature has held throughout.
