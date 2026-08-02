# Slice 4 — Match Screen Redesign

> Part of the `card-visual-identity` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** Playing a round looks and feels like holding a physical Top Trumps card — a full-screen
hero card you tap directly to choose a stat, and a side-by-side reveal showing every stat compared at
once.

## What to build

Restructure the match screen from today's single scrolling column into three fixed regions: a slim
top bar (scores, round counter, leave-match), a non-scrolling hero card that fills the remaining
space, and a bottom action strip (prompt text, Continue). The five Material `Button`s for choosing a
stat are gone — the hero card's own stat rows are the tap targets.

On reveal, the opponent's card flips in beside yours — both full cards, side by side, stat rows
aligned — instead of replacing it. The stat that decided the round is called out clearly (win, lose,
tie); the other four are visible but quiet.

The existing flip and slide-to-pile animations are reworked to carry a card's actual shape instead of
assuming a square, and a pre-existing per-frame recomposition bug in the flip is fixed as part of that
rework — it was cheap to ignore when the animating leaf was a bare image, and stops being cheap once
it's a five-row table.

## Design notes

**Layout** — TDD decision 5. `Column` with a fixed-height top bar and action strip, and the card
region taking `Modifier.weight(1f)` with a single `BoxWithConstraints` inside it — never inside the
card composable itself, which would mean a subcomposition per grid cell elsewhere. Rects captured via
`onGloballyPositioned`/`boundsInRoot()` are unaffected by moving the score labels out of a scrolling
column into the top bar — `positions.myPileLabel`/`opponentLabel` move, but the overlay's coordinate
maths doesn't change.

**Side-by-side alignment is free if you don't fight for it.** `cardGeometry` (Slice 2) is deterministic
in `(width, variant, minRowHeight)`, so two cards built from the *same* `CardGeometry` instance already
have identical row heights — don't reach for `IntrinsicSize.Min`, a shared `SubcomposeLayout`, or
alignment lines; all three add a measure pass and are fragile once one card is inside a `graphicsLayer`
mid-flip. The reveal uses its own smaller `minRowHeight` (28dp, not the hero's 48dp) — at ~190dp per
card a strict 48dp floor doesn't fit two values plus an outcome glyph.

**Animation rework** — TDD decision 4. `FlippableOpponentCard` takes two face slots
(`back`/`front: @Composable (Modifier) -> Unit`) instead of `RemoteCardFace`/`deckId`/`ImageLoader`/
`size: Dp` — `CardAnimations.kt` stops knowing what a card is. **Fix the existing bug while doing
this**: `CardAnimations.kt:131` reads `rotation.value` in the composable body to branch back vs.
front, which was cheap when the leaf was one `AsyncImage` and is not cheap once the leaf is a full
stat table (~25 recompositions per flip). Compose both faces once and drive visibility from inside
`graphicsLayer` lambdas instead of an `if` in the body — the 180° counter-rotation stays exactly as it
is. `SlideOverlay` renders the **source card's own geometry**, not the mini variant the PRD originally
asked for — mini at the same width is a different height than the hero, so swapping to it would be a
visible shape jump at t=0, contradicting the PRD's own "never changes shape mid-flight" in the same
breath. Continuity beats legibility during a 70%-faded, moving card.

**The hero-to-reveal size transition isn't addressed by the PRD.** Your own card jumps from ~379dp
wide (hero) to ~186dp (reveal) the instant a round resolves — invisible today only because the card is
a fixed 220dp square that never changes. `animateDpAsState` is off the table (reads animated state in
a composable body). **Hard-cut it for this slice** and look at it on a device; only reach for
`LookaheadScope` + `Modifier.animateBounds` if it reads badly, and note that API is experimental at
this BOM version and may need a bump.

**Row state composition is unchanged in substance** — `spec.key in round.remainingMetrics` and
`session.hasPendingIntent` fold into `StatRow.enabled`, the same logic as today's
`enabled = available && !pending`, just expressed per-row instead of per-`Button`. The `SELECT` sound
cue moves to the `MatchScreen` call site's `onChooseStat` handler — the card itself takes no
`SoundEffects` dependency.

## Blocked by

- [Slice 3](slice-3-card-composable.md) — the card composable this slice wires in.

## Acceptance criteria

- [ ] Match screen no longer scrolls; top bar, hero card, and action strip are always in the same
      positions
- [ ] Tapping a stat row on the hero card submits `ChooseMetric` for that row, identical to today's
      button behaviour
- [ ] A tied/unavailable row is visibly disabled and not tappable
- [ ] On reveal, both cards render side by side with stat rows at matching heights
- [ ] The decided stat is clearly called out (win/lose/tie); the other four rows show both values
      without competing visually with the result
- [ ] Card flip has correct perspective, no mirrored back face, and no visible shape discontinuity
      between back and front — **needs a physical device to confirm**
- [ ] Both cards visibly travel to the winner's pile together, at their correct (non-mini) shape —
      **needs a physical device to confirm**
- [ ] Recomposition count during a flip is bounded (verify via Compose compiler metrics comparison,
      before/after, per the existing `app/build/compose_reports` convention) — **runtime count needs a
      physical device**, per the project's standing convention (WBS slice-7-polish)
- [ ] No dropped frames on the oldest available family phone during a reveal — **needs that device**
- [ ] Score bar and round counter still clear the status/nav bars in the new top-bar position —
      **needs a gesture-nav device**
- [ ] The hero-to-reveal size change doesn't read as a visual glitch — **judgement call, on-device**
- [ ] `AnimationGate`'s hard-cut behaviour (reconnect/resync) still works with the new card shapes —
      exercised by the existing `ConnectionResilienceTest` seam, confirmed visually on two devices

## Testing

No new automated seam in this slice — it's entirely a rewire of the composable built in Slice 3 into
`MatchScreen.kt`/`CardAnimations.kt`. Per this project's standing convention (`CLAUDE.md`, and
WBS slice-7-polish before it), animation perspective, timing, frame budget, and inset handling are
manual and on-device by design — the seam-level property that already has automated coverage
(`MatchSession.lastResync` / `ConnectionResilienceTest`) is unaffected by this slice's changes and
needs no new test.

## Delivered

Issue: [#29](https://github.com/rustycoopes/top-trumps-game/issues/29) · Branch:
`slice-4-match-screen-redesign` · Date: 2026-08-02

Built as designed. Note first: Slice 3's PR (#36) was still open against `master` when this slice
started despite issue #28 being closed — merged first (squash, `26e53de`) so this slice could build
on the actual shipped card composable rather than a stale branch.

`MatchScreen.kt`'s `InProgressScreen` is now the three-region `Column` from TDD decision 5 —
`MatchTopBar` (scores, round counter, `Leave`), a `Modifier.weight(1f)` `BoxWithConstraints` holding
either `HeroCard` or `RevealPair`, and `MatchActionStrip` (prompt text, `Continue`) — with no
`verticalScroll` anywhere. `AwaitingChoiceContent`'s five `Button`s are gone; `HeroCard` builds a
`CardContent` via Slice 3's `cardContentOf`, folding `round.remainingMetrics` and
`session.hasPendingIntent` into one `availableMetrics` set so a tied-and-excluded row and a
choice-in-flight both land on the same `StatRow.enabled = false` path, and wires `onChooseStat`
straight to `PlayerIntent.ChooseMetric`. `RevealPair` hands both the player's and opponent's card the
*same* `CardGeometry` instance (28dp row floor) so side-by-side row alignment falls out for free, per
the design notes — no `IntrinsicSize.Min`, no `SubcomposeLayout`. The hero-to-reveal size change
(~379dp → ~186dp) hard-cuts as specified; `animateDpAsState` was never attempted.

`CardAnimations.kt`'s `FlippableOpponentCard` now takes `back`/`front: @Composable (Modifier) ->
Unit` slots instead of `RemoteCardFace`/`deckId`/`ImageLoader`/`size: Dp`, and the documented
recomposition bug (`rotation.value` read in the composable body to branch back/front) is fixed
exactly as TDD decision 4 specifies: both faces compose once into the same `Box`, visibility driven
from `alpha` inside `graphicsLayer {}` lambdas. Confirmed via a fresh `compose_reports` run —
`FlippableOpponentCard`, `SlideOverlay`, and every new `MatchScreen.kt` composable report
`restartable skippable` with no body-level animated-state reads. `SlidingCard`/`SlideOverlay` collapse
`deckId`/`imageFile`/`name`/`imageLoader` into one content lambda per TDD decision 4, and the
top-left-lerp → centre-lerp fix (subtracting the *scaled* half-size inside `offset {}`, transform
origin left at `(0f, 0f)`) replaced the old lerp so a ~186dp reveal card doesn't visibly drift toward
its own top-left while shrinking, the way a 220dp square never showed.

Two call-site changes fell out of `MatchScreen` needing a `CardPalette` it didn't have before:
`CardPalette` itself moved from `internal` to `public` (a `public` function can't expose an internal
parameter type), and `TwoDeviceMatchScreen` gained a `deckTheme: (String) -> DeckTheme?` parameter
(`AppGraph.deckTheme` passed as a function reference) since it resolves the palette itself once
`MatchPhase.InMatch.deckId` is known — the guest side never loads a local `Deck` (TDD decision 6), so
`TrumpCardBack`'s deck-name label falls back to the raw `deckId` on this screen; a prettier label is
a follow-up, not a blocker.

Code review (`code-review-master` and `code-quality-guardian`, run in parallel) found no functional
bugs and confirmed the recomposition fix, the centre-lerp math, and the TDD/WBS fidelity by hand.
Four things were fixed before shipping: (1) medium — `FlippableOpponentCard`'s always-composed faces
left the settled-away back face permanently in the merged semantics tree (a TalkBack user would get
both faces' content after the flip finished, not just during it); fixed by clearing the back face's
semantics once `revealed` is true, using the already-tracked one-shot `revealed` boolean rather than
the animating `rotation` value, so it costs one recomposition at reveal, not one per frame. (2) low —
the hero card gave no visual cue it was inert during the opponent's turn (per-row dimming only runs
when `onChooseStat` is non-null, and it's null then); fixed with a whole-card `alpha` dim gated on
`isMyTurn`. (3) a parameter-order inconsistency between `HeroCard` and its sibling composables. (4)
mixed named/positional arguments across `RevealPair`'s several `AssetTrumpCard` calls, normalized to
named throughout. All four were fixed directly; nothing rose to a follow-up-issue bar.

`./gradlew test` (all modules, no regressions), `:app:lintDebug` (clean), and `:app:compileDebugKotlin`
all pass. The acceptance criteria explicitly marked "needs a physical device" in this file — flip
perspective/no mirrored back face, the slide-to-pile's correct shape, on-device recomposition counts,
dropped frames, status/nav-bar clearance on a gesture-nav device, and whether the hero-to-reveal cut
reads as a glitch — were **not** verified on a physical device this session, none was available,
consistent with Slice 3's precedent and this project's standing convention for anything visual.
