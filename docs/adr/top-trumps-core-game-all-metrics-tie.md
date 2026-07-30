# The chooser wins when all five metrics tie

**Status:** Proposed
**Date:** 2026-07-30
**Feature:** [`top-trumps-core-game`](../features/top-trumps-core-game/TDD.md)

## Context

The PRD flagged this as its one open question, recording an assumption — "if all five metrics tie, each player keeps their own card" — while noting it can never fire with thirty distinct real motorcycles.

Design review found that the assumption **contradicts another statement in the same PRD**. The rules section asserts:

> No drawn match is possible. Fifteen rounds award two cards each, always to one player, so both scores are even and sum to 30. 15–15 cannot occur. No draw handling is required.

That reasoning depends on every round awarding **both** cards to a single player. Under "each keeps their own card", a round awards one card to each pile. Scores become odd, and **15–15 becomes reachable**.

So the PRD as written specifies an engine state that violates one of its own documented invariants — and the test suite, which is told to assert `piles.sum() == 30` and that no draw occurs, would encode the contradiction.

The question is no longer arbitrary. It is a choice between preserving the invariant and building draw handling.

## Decision

**The chooser wins.** The player who selected the metric takes both cards, on the reasoning that they took the risk.

The behaviour is data, not a hardcoded branch:

```kotlin
enum class TieFallback { CHOOSER_WINS, DEFENDER_WINS, EACH_KEEPS_OWN }

data class MatchConfig(
    /* … */
    val allMetricsTieFallback: TieFallback = TieFallback.CHOOSER_WINS
)
```

All three variants are implemented and exhaustively unit-tested; only the default changes if this is revisited.

The configured value **travels on the wire** in `MatchConfig`, because the guest never runs the rules engine and would otherwise be unable to render the correct explanation if the case ever fired.

## Alternatives considered

**Each player keeps their own card (the PRD's assumption).** The fairest outcome in isolation — two cards with identical stats genuinely have no winner. Rejected because it breaks the no-draw invariant, which in turn means: draw handling on the result screen, "it's a draw" copy, a rematch flow that accounts for it, and a weakened test assertion. That is real work and real UI surface to support a case that cannot occur with the shipped deck.

**Defender wins.** Equally invariant-preserving, and arguably a nicer balance — it slightly penalises choosing a metric that fails to separate the cards. Rejected as marginally less intuitive to explain: the player did something (chose), and having that produce a loss reads as arbitrary. This is close to a coin toss and is one enum value away if the view changes.

**Break the tie randomly.** Preserves the invariant and is trivially fair. Rejected because a hidden random outcome in a game whose entire premise is transparent number comparison would be jarring, and it is untestable without injecting yet another seed.

**Leave it undefined / throw.** Rejected: the engine is a total function, the seam test suite asserts on it, and an unreachable-in-practice branch that crashes is strictly worse than one that resolves.

## Consequences

**Easier:** the no-draw invariant holds, so `piles.sum() == 30` stays honest as a test assertion, both scores remain even, and the result screen needs only victory and defeat states — no third case, no draw copy, no draw artwork.

**Harder:** nothing material. The three-variant implementation costs a `when` in one place.

**Worth noting:** this reverses a decision the PRD recorded, albeit one explicitly flagged as unconfirmed. It needs the product owner's sign-off. Reinstating `EACH_KEEPS_OWN` is a one-line default change, but doing so means accepting that drawn matches exist and budgeting the UI work to handle them.
