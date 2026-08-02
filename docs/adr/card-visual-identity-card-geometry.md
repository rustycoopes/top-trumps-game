# Card size is computed by a pure geometry function, and the 48dp row floor grows the card rather than being absolute

**Status:** Proposed
**Date:** 2026-08-01
**Feature:** [`card-visual-identity`](../features/card-visual-identity/TDD.md)

## Context

The card is nominally 2:3 portrait, split banner 10% / image window 50% / stat table 40%, with five
stat rows. In the hero card those rows are tap targets, so they want a 48dp minimum height.

Working the arithmetic through: with `h = 1.5w`, the nominal row height is `0.40 · 1.5w / 5 = 0.12w`,
which equals 48dp exactly at **`w = 400dp`**. On a 411dp-wide phone the usable card width is about
379dp, giving 45.6dp rows. **The floor binds on essentially every phone — this is the normal case, not
an edge case.**

Three further constraints interact:

1. The card must **fit** between a fixed top bar and a fixed action strip without scrolling.
2. The **same geometry must produce both faces of the flip**, or the card visibly jumps at the 90°
   crossing when `CardBack` swaps for the front.
3. The reveal shows **two cards side by side** at ~48% width each, where rows must line up across
   both cards so the eye reads horizontally.

The PRD's stated rule — *"stat rows have a 48dp floor and the image window absorbs the difference, so
the card may run slightly taller than 2:3"* — describes two mutually exclusive outcomes. If the window
absorbs the difference, total height is unchanged and the card stays exactly 2:3. If the card runs
taller, nothing was absorbed.

## Decision

**Card size is a pure function, not a layout negotiation.**

```kotlin
internal fun cardGeometry(
    width: Dp,
    variant: CardVariant,
    minRowHeight: Dp = 0.dp,
    maxHeight: Dp = Dp.Unspecified,
): CardGeometry

internal fun solveCardWidth(maxWidth: Dp, maxHeight: Dp, minRowHeight: Dp): Dp
```

with

```
row    = max(0.12w, minRow)
height = 0.9w + 5·minRow    (floor binding)   |   1.5w   (otherwise)
```

The function is continuous at the crossover (`0.9w + 0.6w = 1.5w`), monotonic in `w`, and therefore
invertible — `solveCardWidth` picks the largest card fitting the available box.

**Grow by default; absorb only under an explicit ceiling.** The default path lets the card run taller
than 2:3 and keeps the image window at its nominal `0.75w`. When `maxHeight` is specified and the
grown height would exceed it, the window absorbs instead. One parameter expresses both behaviours with
no second code path and no branch at call sites that don't care.

**`minRowHeight` is the single knob**, with three values: **48dp** hero (tap target), **28dp** reveal
(two values plus an outcome glyph must be legible at ~186dp card width), **0dp** opened pile card.

**Zone heights are set with exact `Modifier.size`/`height`, never `Modifier.weight`.** Weights make
each zone depend on the parent's incoming constraint, which is precisely what would let the two faces
of the flip diverge.

**`BoxWithConstraints` is used exactly once, at the hero slot — never inside the card.** The hero
budget falls out of the `Column` for free: top bar and action strip measure at intrinsic height, the
card's `Box` takes `Modifier.weight(1f)`, so `maxHeight` inside `BoxWithConstraints` *is* the budget.

**Side-by-side alignment is achieved by passing both cards the same `CardGeometry` instance**, not by
measuring them against each other.

**The 48dp floor is "wherever the viewport allows", not absolute.** Below roughly a 500dp hero budget
the constraint set has no acceptable solution, and the hero region is permitted to scroll — preserving
the touch target and the card shape rather than shipping a 91dp card.

## Alternatives considered

**`Modifier.weight` for the three zones.** Simplest to write, and the obvious first reach. Rejected
because zone heights would then depend on the incoming height constraint, so the flip's two faces
could be measured differently and the mini variant could land at a different shape — the exact class
of bug this feature must not ship.

**A custom `Layout`.** Rejected: a custom `Layout` measures *after* composing, so it cannot choose the
variant or text styles, both of which depend on available size.

**`BoxWithConstraints` inside `TrumpCard`.** The ergonomic choice, and it removes the `width`
parameter. Rejected on cost: `BoxWithConstraints` is a `SubcomposeLayout`, so this would mean **30
subcompositions in the win-pile grid**, plus re-subcomposition inside the animation overlay whenever
constraints wobble. Taking geometry explicitly also makes the Robolectric test a one-liner — pass
`380.dp`, no constraints to fake.

**`IntrinsicSize.Min`, a shared `SubcomposeLayout`, or alignment lines for the side-by-side row
alignment.** All three were considered and rejected together: each measures both cards to reconcile
them, which is unnecessary (geometry is already deterministic in width), adds a measure pass, and is
fragile once one card sits inside a `graphicsLayer` mid-flip.

**Holding a strict 2:3 and accepting 45.6dp rows.** Rejected as the default, but worth recording that
the degradation is milder than the number suggests: the 48dp guidance targets *isolated* controls
surrounded by dead space, whereas these rows tile the table contiguously with no gaps, so a slightly
errant tap hits an adjacent stat rather than nothing. It is a usability cost, not a correctness
failure — which is what makes the small-screen scroll fallback acceptable rather than mandatory.

**Shrinking the card to fit on small screens.** Rejected: at a 322dp budget the 48dp floor consumes
240dp — 75% — leaving 82dp for banner and photo and yielding a **91dp-wide card**. This is
over-determination, not a tuning problem.

## Consequences

**Easier:** front/back pixel identity is structural — one `CardGeometry` instance, two faces, nothing
to keep in sync. Side-by-side row alignment is free. And because the functions are pure `Dp` maths
with no Android or Compose-runtime dependency, **the hardest arithmetic in the feature is testable in
a plain JVM test with no Robolectric and no compose rule** — a third test seam at essentially zero
infrastructure cost, which can land before any Robolectric risk is taken on.

A satisfying property falls out unforced: **phones are width-bound and run ~2% taller than 2:3; large
screens are height-bound and land at exactly 2:3.** Nothing special-cases either.

**Harder:** callers must compute geometry before composing the card, so `width`/`geometry` is a
required parameter at five call sites rather than something the card figures out. The `maxHeight`
absorption path is a second behaviour to hold in mind even though most call sites ignore it.

**Foreclosed:** the card can no longer adapt to constraints it wasn't told about — dropping it into an
arbitrary container without computing geometry will not "just work". That is deliberate.

**Known sharp edges:**
- A **360×740dp phone is on the knife edge** (needs ~535dp of a ~540dp budget); a two-line prompt in
  the action strip tips it into the scroll fallback. This is not a hypothetical-device concern.
- Because chrome scales uncapped while card text is capped at 1.3×, a large system font setting grows
  the action strip and shrinks the card automatically. That is the correct failure mode — chrome stays
  readable — but it should be stated rather than discovered.
- The reveal's own floor of 28dp means reveal cards sit at roughly a 1.65 ratio, not 2:3. Front/back
  identity is unaffected, since both faces take the same geometry object regardless of which
  `minRowHeight` produced it.
