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

- [x] Every card — front and back, all three variants (hero/mini/reveal) — renders a clearly visible
      white border around its outer edge, in addition to the existing hairline
- [x] The card back renders a diagonal-stripe pattern in the deck's accent colour, in place of today's
      flat colour fill
- [x] The pattern is drawn programmatically (`Canvas`/`Path`), not a bundled image asset
- [x] The deck name and "Tap to reveal" caption still render on the back, inside a clearly bordered
      medallion/badge, at a visibly larger size than today's 22sp/14sp
- [x] The back's front/back geometry identity (same `CardGeometry` instance, same `CardChrome` frame)
      is unchanged — the flip animation still has no shape jump
- [x] No regression to the existing flip animation frame rate, verified manually on-device
- [x] Every existing card/screen test (`TrumpCardTest`, `CardAnimationsTest`, etc.) still passes

## Testing

Pattern drawing and border rendering are pure Compose visual output — no new JVM-testable logic
beyond what a Robolectric/Compose semantics test can already cover (e.g. the medallion badge still
carries the "Tap to reveal" click affordance, same as `CardAnimationsTest`'s existing coverage
pattern). Visual appearance (pattern legibility, border weight, medallion contrast) is a manual,
on-device concern — same boundary this feature has held throughout.

## Delivered

Issue [#50](https://github.com/rustycoopes/top-trumps-game/issues/50), branch
`slice-7-playing-card-border`, 2026-08-07.

`CardChrome` (`CardFrame.kt`) now draws a 6dp white `CardOuterBorderWidth` margin *around*
`CardGeometry`'s existing size rather than inset into it — the outer `Box` grows to
`geometry.width/height + 2 * CardOuterBorderWidth`, while the inner accent-filled content `Box`
(and everything inside it: banner, image window, stat table, hairline) stays pinned to the original
`geometry` size unchanged. That was the deliberate choice over insetting the border into the
existing geometry: the banner/image/table zone heights are fixed `Dp` values that sum to exactly
`geometry.height` by construction (`cardGeometry`'s own arithmetic), so shrinking the box they render
into would have overflowed and clipped the bottom stat row — tightest on the reveal variant's 28dp
row floor. Growing the footprint instead means every existing zone-height calculation is completely
untouched.

That growth has to come from somewhere, though: the three `MatchScreen.kt` call sites that use
`solveCardWidth` to solve a geometry that exactly fills an available `BoxWithConstraints`
(`HeroCard`, `RevealPair`, `WinPileGrid`'s opened-card view) now subtract `CardOuterBorderWidth * 2`
from the available space before solving, so the grown card still fits on-screen. A code-review pass
(`code-review-master`) caught that the same reasoning applies to every *fixed*-width mini-card grid
too, not just the solved ones — `WinPileGrid`'s pile grid (a live gameplay screen, `GridCells.Fixed(3)`
with no adaptive slack) would have squeezed mini cards narrower than their baked-in zone heights
expect on phones under ~380dp wide, and `CardGalleryScreen`'s debug gallery grid was tight
independent of screen width. Fixed by subtracting `CardOuterBorderWidth * 2` from the *requested*
width at all three fixed-width mini-geometry call sites (`WinPileGrid`, `CardGalleryScreen`,
`DeckPickerScreen`) instead, restoring each grid's pre-slice-7 rendered footprint exactly.

`TrumpCardBack.kt`'s back face replaces the flat `Box`/`Text` content with a `Canvas`-drawn diagonal
stripe pattern (opaque `Color.Black.copy(alpha = 0.12f)` bands tiled across a generously-overshot
rectangle, then rotated 45°) directly over the existing `palette.accent` fill, plus an opaque
bordered "medallion" `Column` (white 2dp border, rounded shape) holding the deck name (22sp → 30sp)
and optional caption (14sp → 18sp) — the medallion draws after the stripes so it fully covers
whatever pattern falls behind it rather than being clipped around it.

Verified on a connected physical device (Galaxy Tab S7 FE, debug build) via the debug card gallery
(mini grid, side-by-side front/back) and a live solo match (hero card back/flip/front, reveal pair
side by side) — border, stripe pattern, medallion, and the mini-grid fix all rendered correctly with
no clipping. Frame-rate regression and behaviour on screens narrower than the test tablet couldn't be
directly measured in this session; reasoned through algebraically instead (see the review notes above)
and left as a standard on-device follow-up if anything looks off on a real phone. All existing
JVM/Robolectric tests (`TrumpCardTest`, `CardAnimationsTest`, `CardGeometryTest`, full multi-module
suite) pass unchanged — no test needed updating since the pure `cardGeometry`/`solveCardWidth`
functions themselves were never touched, only their callers' inputs.
