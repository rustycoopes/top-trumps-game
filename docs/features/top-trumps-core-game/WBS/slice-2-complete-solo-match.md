# Slice 2 — Complete solo match

> Part of the `top-trumps-core-game` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** A full fifteen-round game against the app, with tiebreaks, win piles and a winner.

## What to build

Thicken every layer the skeleton established, until the game is actually the game.

The round loop runs fifteen times with strict turn alternation. Ties are resolved interactively — the same player chooses again from the remaining metrics, with the tied metric disabled — and this recurses until a metric separates the cards. Won cards move to the winner's pile; a live score and round counter are always visible; tapping your own pile opens a browsable grid of what you have won, and returning does not lose your place in the round. The match ends with a victory or defeat screen showing the final score, and offers a rematch.

The reveal model comes into force here: the contested metric only while the round is live, then the opponent's full card once it is decided. The player controls the pace of advancing.

The AI becomes plausible rather than trivial — it picks the metric on which its current card ranks highest within the deck — and needs no special case for tiebreaks, since the remaining metrics are already in the view it consumes.

## Design notes

Round state machine and the termination proof: [TDD §3](../TDD.md#3-round-state-machine-and-tiebreak-termination). Only `AwaitingChoice` and `Resolved` are authoritative; `Committed`, `Comparing`, `Revealed` and `Advance` are client-side presentation states that never touch the wire. Every tie removes a metric from a strictly decreasing set, so a round resolves in at most five selections.

**The all-metrics-tie fallback is `CHOOSER_WINS`, not the PRD's original assumption** — see [TDD §4](../TDD.md#4-the-all-metrics-tie-fallback--resolving-the-prds-open-question) and the [ADR](../../../adr/top-trumps-core-game-all-metrics-tie.md). It is `MatchConfig` data with all three variants implemented, and it rides the wire because the guest never runs the engine. This preserves the no-draw invariant: every round awards both cards to one player, so scores stay even and sum to 30.

The reveal ordering is forced by the interactive tiebreak — revealing the full card at first compare would let the chooser pick a winning tiebreak with perfect information. See [structural redaction ADR](../../../adr/top-trumps-core-game-structural-redaction.md); the types already prevent it, this slice just exercises the `Revealed` variant for the first time.

The win-pile browser is a **state within the match**, not a navigation destination — story 50 requires returning without losing your place. See [TDD §10](../TDD.md#10-ui).

Consider gating the choice UI on local reveal-dismissal ([TDD Open Question 5](../TDD.md#open-questions)) — cheap, client-only, and it stops a fast player committing their next choice before the opponent has seen the last result.

## Blocked by

- [Slice 1](slice-1-walking-skeleton.md)

## Acceptance criteria

- [ ] A complete fifteen-round solo match plays from deal to result
- [ ] Turn choice alternates strictly, giving each player seven or eight selections
- [ ] A tied metric prompts the **same** player to choose again, with the tied metric disabled
- [ ] A tiebreak that ties again prompts once more; the round always resolves
- [ ] The UI states which metric finally settled a tied round
- [ ] `CHOOSER_WINS` fires correctly when all five metrics tie
- [ ] Both piles always sum to 30 and both scores are even — no draw is reachable
- [ ] The opponent's unplayed stats stay hidden through an entire tiebreak chain
- [ ] The opponent's full card is revealed once the round is decided
- [ ] The win pile is browsable mid-match and returning restores the live round
- [ ] The match ends with a result screen and a working rematch

## Testing

All at the seam established in slice 1, with no new doubles.

Prefer many small scenarios over one god-test: a single fifteen-round test asserting everything is brittle in exactly the way the PRD's own "good test" guidance warns against. Share a small `MatchDriver` helper that sequences moves — a test utility containing no behaviour, not a double.

One full-match test asserts the invariants: a winner is determined, `piles.sum() == 30`, and turn alternation held across all fifteen rounds. Everything else is focused: tiebreak recursion, metric exclusion, each of the three `TieFallback` variants, and the leak assertion extended to cover a **multi-step tiebreak** — after two ties, exactly two stats are visible on the opponent's card and no more.

Design each `suspend fun` so that state is settled when it returns, letting tests read `.value` synchronously. `StateFlow` conflates and drops intermediate values, so tests asserting a *sequence* of transitions are inherently flaky, and values it never emitted cannot be recovered by any library. Reach for Turbine only where a genuine transition sequence matters.
