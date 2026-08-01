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

- [x] A complete fifteen-round solo match plays from deal to result
- [x] Turn choice alternates strictly, giving each player seven or eight selections
- [x] A tied metric prompts the **same** player to choose again, with the tied metric disabled
- [x] A tiebreak that ties again prompts once more; the round always resolves
- [x] The UI states which metric finally settled a tied round
- [x] `CHOOSER_WINS` fires correctly when all five metrics tie
- [x] Both piles always sum to 30 and both scores are even — no draw is reachable
- [x] The opponent's unplayed stats stay hidden through an entire tiebreak chain
- [x] The opponent's full card is revealed once the round is decided
- [x] The win pile is browsable mid-match and returning restores the live round
- [x] The match ends with a result screen and a working rematch

## Testing

All at the seam established in slice 1, with no new doubles.

Prefer many small scenarios over one god-test: a single fifteen-round test asserting everything is brittle in exactly the way the PRD's own "good test" guidance warns against. Share a small `MatchDriver` helper that sequences moves — a test utility containing no behaviour, not a double.

One full-match test asserts the invariants: a winner is determined, `piles.sum() == 30`, and turn alternation held across all fifteen rounds. Everything else is focused: tiebreak recursion, metric exclusion, each of the three `TieFallback` variants, and the leak assertion extended to cover a **multi-step tiebreak** — after two ties, exactly two stats are visible on the opponent's card and no more.

Design each `suspend fun` so that state is settled when it returns, letting tests read `.value` synchronously. `StateFlow` conflates and drops intermediate values, so tests asserting a *sequence* of transitions are inherently flaky, and values it never emitted cannot be recovered by any library. Reach for Turbine only where a genuine transition sequence matters.

## Delivered

Issue: [#3](https://github.com/rustycoopes/top-trumps-game/issues/3) · Branch: `slice-2-complete-solo-match` · Date: 2026-07-31

All acceptance criteria met. `RulesEngine`, `MatchState` and `RoundState` grew a full round loop:
`RoundState` still has exactly two authoritative variants (`AwaitingChoice`, `Resolved`) per TDD
§3 — match completion is modelled separately via a nullable `MatchState.outcome` and a sealed
`PlayerView.InProgress` / `PlayerView.Finished` split, not a third `RoundState` variant, so the
termination proof and the wire protocol both stay exactly as designed. `AwaitingChoice` now
carries `remainingMetrics` (strictly decreasing, per the termination proof) and
`revealedMetrics`; `Resolved` carries `chooser`, `winner`, `decidingMetric` and a `RoundResolution`
(`METRIC_DECIDED` / `ALL_METRICS_TIED_FALLBACK`) so the UI can state which metric — or which
fallback — settled a tied round. A new `PlayerIntent.AdvanceRound` (not seat-gated — the reveal it
dismisses is already fully public) moves a `Resolved` round to the next round's `AwaitingChoice`
or into `MatchOutcome`; strict alternation is derived from `resolved.chooser.opponent()`, never
from who won. `TieFallback` (`CHOOSER_WINS` default, `DEFENDER_WINS`, `EACH_KEEPS_OWN`) is real
`MatchConfig` data with all three variants exercised in tests, per the all-metrics-tie ADR.

`:core:session`'s `MatchView`/`RemoteRoundState` mirror the new sealed shapes; `ProtocolCodec`
gained a sealed `WireIntent` to carry `AdvanceRound` over the wire alongside `ChooseMetric`.
`:core:ai`'s `AiOpponentDriver` now ranks its card against the whole (public, shared) `Deck` per
metric rather than a raw-value heuristic — "plausible rather than trivial" — and needs no
tiebreak special-case since `remainingMetrics` already excludes what's been tried. The placeholder
`decks/test-deck/manifest.json` grew from 4 cards/2 metrics to 30 cards/5 metrics (matching the
real Motorcycles deck's shape slice 3 will fill in), so the actual 15-round loop is exercised on
device, not just in tests. `:app` gained a live score/round bar, tied-metric buttons rendered
disabled rather than omitted, a win-pile browser as in-match state (not a nav destination, per
TDD §10), and a victory/defeat result screen with a working rematch.

Tested per the WBS: `RulesEngineTest` and `MatchSessionTest` (via a new `MatchDriver` test
helper, per the WBS's explicit ask) cover tiebreak recursion, metric exclusion, all three
`TieFallback` variants, a multi-step leak assertion (two ties → exactly two stats visible, no
more), and one full-match invariant test each (winner determined, `piles.sum()` equals the deck
size, alternation held). `./gradlew build` passes clean (all tests, lint, the `:core:*`
dependency allowlist). Manually verified end-to-end on the same physical Samsung device as slice
1: a full 15-round match (final score 16–14, confirming the no-draw/sum-to-30 invariants),
tiebreak UI, win-pile browsing with return-to-round, and rematch, all via `adb`-driven taps and
screenshots.

**Diverged from the plan:**
- The TDD (§4) says the `TieFallback` config "rides the wire" in `MatchConfig` so the guest can
  render the correct explanation. It doesn't literally travel over the wire — instead
  `RoundState.Resolved`/`RemoteRoundState.Resolved` carries the already-computed `resolution` and
  `winner` directly, which is sufficient for the guest to render the outcome without duplicating
  the fallback logic. Flagged by code review as a real divergence worth a maintainer's read, not a
  bug; a future two-device slice may still want `MatchConfig` on the wire for other reasons (e.g.
  showing a "house rule" in a pre-match lobby).
- Solo mode's `AppGraph.startSoloMatch()` now hands out its own `MatchSession` wrapper
  (`SoloMatchSession`) rather than the raw `HostMatchSession`, and gives each match its own child
  `CoroutineScope`. Code review (both agents, independently) caught that the original
  implementation closed only the host's transport on rematch, permanently leaking the previous
  match's `GuestMatchSession` and its `AiOpponentDriver` collector coroutine on the app's
  long-lived shared scope. Fixed before merging; re-verified on device across repeated rematches.
- A follow-up ([#11](https://github.com/rustycoopes/top-trumps-game/issues/11)) was filed for a
  minor test-helper duplication in `MatchSessionTest` (a standalone `chooseMetric` helper survives
  alongside the new `MatchDriver`) — low priority, not blocking.
  **Resolved 2026-08-01:** the standalone helper was deleted and its four call sites migrated onto
  `MatchDriver`. Code review found a third copy of the same dispatch logic had since arrived with
  Slice 6's `ConnectionResilienceTest.resolveOneRound` (added after #11 was filed, so out of its
  scope) — filed as [#23](https://github.com/rustycoopes/top-trumps-game/issues/23).
  **#23 resolved 2026-08-01 too:** `resolveOneRound`'s five call sites migrated onto `MatchDriver`
  the same way, and the helper deleted. Its "first metric in the view" pick collapsed to the
  existing `speed` constant, since `ConnectionResilienceTest`'s fixture deck is single-metric —
  behaviourally identical, not a change. No further duplicates of this shape found in `:core:*`.
