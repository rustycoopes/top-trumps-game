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

## Delivered

Issue [#30](https://github.com/rustycoopes/top-trumps-game/issues/30), branch
`slice-5-remaining-surfaces`, 2026-08-02.

The win-pile grid (`MatchScreen.kt`'s `WinPileGrid`) now renders mini `AssetTrumpCard`s
(`CardVariant.MINI`) instead of a bare `AsyncImage` thumbnail — the old `CardImage` composable is
deleted. Tapping a pile card opens it full-size and read-only (`CardVariant.HERO`,
`onChooseStat = null`, sized via the same `solveCardWidth`/`BoxWithConstraints` pattern `HeroCard`
already uses), closing PRD story 49. This is a new `openedCard` state local to `WinPileGrid` itself,
one level below `InProgressScreen`'s existing `showingPile` flag — opening/closing a card never
touches `showingPile`, so the live round underneath is untouched either way, matching the existing
"a state within the match, not a navigation destination" comment this slice extends rather than
replaces.

The deck picker (`DeckPickerScreen.kt`) is rewritten from a plain per-deck `Button` list into a
`LazyVerticalGrid` of themed mini cards — each deck's resolved `DeckTheme` (`AppGraph.deckTheme`,
falling back to `DeckTheme.DEFAULT` for a deck that doesn't resolve) drives the tile's accent colour,
and a new `DeckSummary.heroImageFile` field (resolved once in `AppGraph.listDecks()`, from the same
`Deck` load `listDecks()` already does to populate `deckThemeCache`) supplies the representative
image. Both existing call sites (`MainActivity.kt`'s `SoloMatchHost`, `TwoDeviceMatchScreen.kt`) were
updated to pass `deckTheme`/`imageLoader` through — both already had `AppGraph`/`imageLoader` in
scope, so no new plumbing was needed beyond the two call sites themselves. No second `ImageLoader`
instance is introduced anywhere in this slice — verified by code review.

The result screen (`ResultScreen`) gained explicit `MaterialTheme.typography.headlineLarge`/
`titleLarge` and `MaterialTheme.colorScheme.onBackground` on its two `Text` calls — it was already
inside `TopTrumpsTheme`'s `MaterialTheme` wrap and inherited Material defaults implicitly, but named
neither typography nor colour explicitly before this. No card showcase was added, per the PRD's
explicit scope boundary for this screen.

`decks/lucys-youtubers/manifest.json`'s `theme.accent` moved from `#FF0000` (≈4.0:1 contrast against
white stat text — fails the PRD's ≥4.5:1 bar) to `#C2185B`, a hot-pink/magenta accent computed at
≈5.87:1 (WCAG relative-luminance formula, no in-repo contrast helper exists so this was hand-computed
and double-checked independently by both review agents). Motorcycles' existing `#C8102E` accent
(≈5.88:1, set in Slice 2) already passed and was left unchanged.

Code review (`code-review-master` and `code-quality-guardian`, run in parallel) found no functional
bugs, no state-loss/race conditions, no second `ImageLoader`, and confirmed the `heroImageFile`
lookup's `.first { }` in `listDecks()` can't throw (traced the full guarantee chain from
`DeckLoader`'s validation through `resolveDeckTheme`'s fallback). One real issue was found and fixed:
placing `.clickable()` on the outer modifier passed into `AssetTrumpCard` put it *outside* the
card's own internal `clip` in the composed modifier chain, so the tap ripple would have bled past
the card's rounded corners into the square layout box behind it. Fixed by clipping the caller's own
modifier (`Modifier.clip(RoundedCornerShape(CardCornerRadius))`) before `clickable`, in both
`WinPileGrid` and `DeckPickerScreen`. Minor nits also addressed: a stale `AppGraph.loadDeck` doc
comment, a missing `Modifier.fillMaxSize()` on the picker's grid (harmless but inconsistent with its
two sibling grids), and a `remember` key on the picker's `CardContent` that used `deck.name` instead
of `deck.id` like every other `remember` in the same file. Nothing rose to a follow-up-issue bar.

`./gradlew test` (all modules, no regressions) and `:app:lintDebug` both pass. The acceptance
criterion explicitly marked "needs two physical devices to confirm" in this file — that a
`theme`-bearing deck manifest doesn't disturb the two-device handshake — was **not** verified on
physical hardware this session, none was available, consistent with every other slice in this
feature's precedent. Every other acceptance criterion was verified by reading the shipped code and
its test coverage directly.
