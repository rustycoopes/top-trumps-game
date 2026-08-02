# Slice 5 — Remaining Surfaces & Deck Accents

> Part of the `card-visual-identity` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** Every other place a card appears — the win pile, the deck picker, the result screen —
matches the new card design, and the two decks in the repo get real colours instead of the default.

## What to build

The win-pile grid renders mini cards instead of bare thumbnails, and tapping one opens it as a full
card at full size — closing PRD story 49 ("open any card in my win pile at full size"), which was
specified in the original `top-trumps-core-game` PRD but never built.

The deck picker shows each deck as a themed card-style tile — accent colour, a representative image
— instead of today's plain per-deck button.

The result screen adopts the app's theme (colours, typography) but stays a text-and-button summary; no
card showcase.

Motorcycles and `lucys-youtubers` get proposed, contrast-checked accent colours in place of the
default.

## Design notes

Win pile and deck picker both use the **mini variant** from Slice 3, and both now draw on the shared
`ImageLoader` and `deckTheme()`/`heroCardId` resolution already built in Slice 2 — this is the slice
those two pieces of plumbing were built for.

**Opening a pile card is small once the full variant exists.** A `mutableStateOf<RemoteCardFace?>`
holding the tapped card, shown as a full `TrumpCard`/`AssetTrumpCard` with `onChooseStat = null` — the
same read-only full-card rendering already used in the reveal, just outside the match flow.

**Accent colours are proposed here, not fixed by the PRD or TDD.** Check each against its stat text
for at least 4.5:1 contrast (PRD accessibility requirement) before committing — Motorcycles toward a
deep racing red, `lucys-youtubers` toward hot pink/purple, per the original grilling session, but treat
those as starting points to be judged against the real photos in the debug gallery (Slice 3), not as
fixed values.

Result screen theming is Material defaults from `TopTrumpsTheme` (Slice 2) — no new work beyond
applying the theme, since it carries no card visuals per the PRD's explicit scope boundary.

## Blocked by

- [Slice 4](slice-4-match-screen-redesign.md) — sequenced last per the PRD's own recommendation, so the
  higher-risk match-screen rework is proven first; this slice's three surfaces are otherwise
  independent of each other and could be split further if useful.

## Acceptance criteria

- [ ] Win-pile grid renders mini cards (frame, image, title) instead of bare image thumbnails
- [ ] Tapping a pile card opens it as a full card with the complete stat table; returning restores the
      live round without losing state (existing behaviour, must not regress)
- [ ] Deck picker renders each available deck as a themed tile with its accent colour and a
      representative image, in place of the current plain button
- [ ] A deck with no `theme` block still appears correctly in the picker using the default palette
- [ ] Result screen text and buttons use the app's theme colours and typography
- [ ] Motorcycles and `lucys-youtubers` have proposed accent colours applied, each checked at ≥4.5:1
      contrast against its stat text
- [ ] Two-device handshake still succeeds for a deck whose manifest now carries a `theme` block, with
      both devices on the same build — **needs two physical devices to confirm**
- [ ] Picker and pile grid share the single `ImageLoader` from `AppGraph` — no second loader instance
      introduced by this slice

## Testing

No new seam. Coverage is the same manual on-device pass this project uses for anything visual: the
gallery and previews from Slice 3 for card appearance, plus a live two-device match to confirm a
`theme`-bearing manifest doesn't disturb the handshake (the theme block sits inside the hashed
`manifest.json` bytes, so this is exercising the existing `manifestHash` check with new content, not a
new code path).
