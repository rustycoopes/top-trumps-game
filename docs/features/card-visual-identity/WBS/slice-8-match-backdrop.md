# Slice 8 — Full-Screen Deck Backdrop

> Part of the `card-visual-identity` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)
>
> Scoped via a grilling conversation rather than a full `/to-prd`/`/to-design` pass — design notes
> below stand in for a TDD decision section.

**Delivers:** The per-deck `background.webp` art (added in slice 6/#41, re-generated in #45) moves
from a faint wash inside each card face to a dominant full-screen backdrop behind the whole match
screen — the actual scope the artwork was made for.

## What to build

Slice 6 built a per-card background wash (`CardFrame.kt`'s `BackgroundImageAlpha`, currently 25% —
raised to 70% in an uncommitted local experiment that should be **discarded**, not built on, once
this slice lands, since it doesn't fix the real problem). On-device testing during this slice's
scoping conversation found that wash effectively invisible, for a structural reason unrelated to
alpha: `CardVariant.MINI` (deck picker, win pile) absorbs the stat-table's height into the image zone,
leaving only a thin title-banner sliver where the wash is even visible at all — and even on the hero
card, a scene as compositionally busy as `motorcycles/background.webp` (a sunset road) reads as
nothing at 25-40% squeezed behind stat-row text.

The actual want, confirmed with the user: the deck's background art should be a **backdrop behind the
card**, not a decoration on it — visible across the whole match screen (top bar, card region, action
strip), not just inside `CardChrome`.

Scope: `MatchScreen.kt`'s `InProgressScreen` only (the hero card, the reveal pair, the win-pile grid,
and an opened full-size pile card are all internal states of this one composable, sharing one
`deckId` — one backdrop placed at this level covers "gameplay and looking at any of that deck's
cards" without new plumbing). The deck picker is explicitly **out of scope** — it shows every deck at
once, so there's no single deck to background there. `:feature:history` renders no cards at all and
isn't a candidate either.

Target opacity: **60%, "dominant," adjustable down from there** if it turns out to fight legibility
on-device — this is a starting point for implementation, not a final locked number.

## Design notes

- The existing per-card `BackgroundImageAlpha` wash (`CardFrame.kt`) should be **removed**, not
  layered underneath the new backdrop — the user was explicit that the background isn't wanted "for
  the card itself," only behind it. Leaving both in place would double-render the same image at two
  different treatments for no reason. `CardTextScrimAlpha` (the stat-table legibility scrim) stays;
  it's unrelated to this change.
- At 60% opacity behind the *whole screen*, `MatchTopBar` (round counter, scores) and
  `MatchActionStrip` (prompt text) — both currently plain `Text` directly on a plain Material
  surface, no scrim — will need their own legibility treatment (a scrim panel behind each, or a
  solid-ish backing) to hold contrast against a busy image. The card itself needs no such treatment;
  it's already opaque (`palette.accent` fill) regardless of what's behind it.
- Reuse the existing asset resolution (`file:///android_asset/$deckId/background.webp`,
  `CardPalette.backgroundImage`) — the image and its manifest wiring from slice 6 don't change, only
  where and how it's composited.
- A deck with no `backgroundImage` (e.g. `test-deck`) should fall back the same way cards already do
  today: no image, just whatever flat colour/surface sits underneath — not a regression, not a new
  code path to special-case.

## Blocked by

None — can start immediately, independent of slice 7. Both slices touch `CardFrame.kt`/`MatchScreen.kt`
in different, non-overlapping ways (frame border vs. screen-level composite), so either order is fine.

## Acceptance criteria

- [ ] The deck's `background.webp` renders full-bleed behind the entire in-match screen — top bar,
      card region, and action strip — not just inside the card
- [ ] This applies across every state reachable from `InProgressScreen`: the hero card
      (`AwaitingChoice`), the reveal pair (`Resolved`), the win-pile grid, and an opened full-size
      pile card
- [ ] The deck picker's flat/default look is unchanged — no per-deck backdrop there
- [ ] Opacity starts at 60%, visibly reads as a dominant scene (not a faint tint) — tune down from
      there only if legibility genuinely requires it
- [ ] `MatchTopBar` and `MatchActionStrip` text stays at the existing ≥4.5:1 contrast bar against the
      backdrop, via whatever scrim/backing treatment the implementer finds cleanest
- [ ] The existing per-card `BackgroundImageAlpha` wash in `CardFrame.kt` is removed (including
      discarding the uncommitted 0.25→0.7 experiment, which becomes moot)
- [ ] A deck with no `backgroundImage` shows no backdrop, with no visual regression to the current
      (pre-this-slice) plain screen look
- [ ] No regression to flip/slide animation frame rate on the oldest available test device
- [ ] Every existing card/screen test still passes

## Testing

The composite (image + scrim + opacity) is a manual, on-device visual concern, same boundary this
feature has held throughout. Any asset-resolution logic reused from slice 6 is already covered by
`AllDecksThemeTest`/`DeckLoader` tests — no new JVM-testable surface is introduced by moving where
the same resolved image gets rendered.
