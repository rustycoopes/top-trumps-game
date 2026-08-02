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
