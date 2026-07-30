# Hidden stats are unrepresentable, not filtered

**Status:** Proposed
**Date:** 2026-07-30
**Feature:** [`top-trumps-core-game`](../features/top-trumps-core-game/TDD.md)

## Context

The PRD's interactive tiebreak only works if a player cannot see the opponent's unplayed stats mid-round. If they could, the chooser would pick a tiebreak metric with perfect information and win every tie automatically — the mechanic would be decorative.

This is therefore not a UI concern or a networking concern. It is a correctness property of the game, and the PRD asserts a test on it.

The naive implementation — one card type with a `revealed: Boolean` flag, filtered before send — always has a field capable of holding hidden data, and someone eventually forgets to check the flag. The failure is silent: the game still plays, ties just become trivially winnable.

## Decision

Redaction is enforced by the type system at three levels.

**1. The authoritative state has no serializer.** `MatchState` lives in `:core:rules` and carries no `@Serializable`. Every wire message references `PlayerView` and never `MatchState` or `Card`. There is no code path — buggy, lazy, or otherwise — that can put the opponent's hand on the wire, because the encoder does not exist.

**2. The opponent's card is a sealed type with nowhere to put hidden stats.**

```kotlin
data class RevealedMetric(val metric: MetricKey, val value: StatValue)

sealed interface OpponentCardView {
    data object FaceDown : OpponentCardView
    data class Contested internal constructor(val revealed: List<RevealedMetric>) : OpponentCardView
    data class Revealed  internal constructor(val card: CardFace) : OpponentCardView
}
```

A tiebreak chain accumulates entries in `revealed`, so after two ties two stats are legitimately visible — correct behaviour expressed by construction rather than by a filter. Same treatment for piles: the viewer gets `List<CardFace>`, the opponent gets `pileCount: Int`. You cannot browse what was never sent.

**3. `project(state, viewer)` is the sole constructor of views.** Every type in the `PlayerView` graph uses `internal constructor` **and** `@ConsistentCopyVisibility`. Without that annotation a data class with an internal constructor still exposes a public `copy()` — precisely the hole through which a ViewModel or adapter would widen a view.

**The host consumes its own game through `project()` as well**, never handing `MatchState` to its own UI.

`PlayerView` carries a monotonic `revision: Long`, because `StateFlow` conflates on `equals()` and a transition producing a structurally identical view would otherwise fail to emit at all.

## Alternatives considered

**A single card type with a `revealed` flag, filtered at send.** Far less type machinery and immediately obvious to any reader. Rejected because it makes a correctness property depend on remembering to filter, and the failure mode is silent — no crash, no test failure unless someone wrote precisely the right assertion, just a tiebreak that quietly stops being a gamble.

**Redact in the UI layer.** Simplest of all, and the data is already on the device. Rejected outright: the opponent's stats would have crossed the wire, so any logging, any future debug view, or a modified client would expose them. Redaction must happen where the data is produced.

**Runtime assertions in `project()`.** Cheap and catches mistakes in test runs. Rejected as a *primary* mechanism — it catches at runtime what the type system can prevent at compile time — but the serialise-and-grep test is retained as a secondary net.

## Consequences

**Easier:** the leak test is two cheap assertions; the AI opponent inherits the guarantee for free, because it consumes `PlayerView` like anyone else and structurally cannot cheat; the host has no bespoke self-rendering path that can drift from the guest's.

**Harder:** more types than an equivalent naive model, and `@ConsistentCopyVisibility` is obscure enough that a future contributor may remove it without understanding what it was doing. Worth a comment at the declaration site.

**Forecloses:** any future feature that legitimately wants to show more of the opponent's card mid-round (a "peek" power-up, a spectator mode) requires a new variant on the sealed type rather than flipping a flag. That is a feature, not a limitation — it forces the decision to be explicit.
