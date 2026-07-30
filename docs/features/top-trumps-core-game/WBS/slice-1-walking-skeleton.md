# Slice 1 — Walking skeleton: one round, solo, on device

> Part of the `top-trumps-core-game` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** Tap a stat on your card, see both values compared, and find out who won the round — running on a phone, against the app.

## What to build

The thinnest possible path that touches **every** layer, so that all the architectural boundaries exist and are proven before anything is thickened.

Stand up the six-module structure, the version catalog and the convention plugins. Define the deck manifest format and the `DeckSource` interface, with a deliberately tiny four-card test deck — no images, no real motorcycles. Build `RulesEngine` with just enough to compare one metric in both win directions, `project()` with the redaction types, `MatchSession` with both host and guest implementations, and `LoopbackTransport`. Add a trivial AI that picks its best-ranked stat. Put an unashamedly ugly Compose screen on top.

The result is one round of Top Trumps, played solo, end to end.

**Deliberately excluded, to keep the skeleton thin:** tiebreaks, win piles, scoring, the 15-round loop, real deck content, images, animation, sound, and anything to do with the network.

Two decisions in this slice are constructor-signature commitments that are painful to retrofit later, so make them now:

- **No hardcoded `Dispatchers.*` anywhere in `:core:*`.** Dispatchers are injected. Slice 6's virtual-time tests of the 60-second grace window are impossible otherwise, and by then the signatures are everywhere.
- **`Transport` carries `ByteArray`**, and `LoopbackTransport` routes through the real codec. Passing typed objects by reference here would leave every serialisation bug undiscovered until two phones are in hand.

## Design notes

Module layout and toolchain: [TDD §1](../TDD.md#1-module-structure) and the [module structure ADR](../../../adr/top-trumps-core-game-module-structure.md). The `kotlin("jvm")` plugin on the four `:core:*` modules is the whole point — it makes `import android.*` a compile error rather than a review comment.

The projection types are the most important thing built in this slice: [TDD §2](../TDD.md#2-rules-engine-and-the-projection-boundary) and the [structural redaction ADR](../../../adr/top-trumps-core-game-structural-redaction.md). `OpponentCardView.Contested` must have nowhere to put unplayed stats, and `@ConsistentCopyVisibility` must accompany every `internal constructor` or a public `copy()` reopens the hole.

`PlayerView` carries a monotonic `revision: Long` from the outset — `StateFlow` conflates on `equals()`, and a transition producing a structurally identical view would silently fail to emit.

Deck storage and the `DeckSource` split: [TDD §7](../TDD.md#7-deck-storage-and-loading) and the [deck storage ADR](../../../adr/top-trumps-core-game-deck-storage.md). Age is modelled as `LOW_WINS` on the stored year so the engine needs no clock.

`RulesEngine` must contain no `when (metric)` — metric labels, units and directions all come from the manifest. That absence is the "second deck, zero code change" guarantee and should be grep-checkable from this slice onward.

## Blocked by

- [Slice 0](slice-0-nsd-platform-spike.md) — the spike's `targetSdk` and permission findings feed the manifest and build config set up here.

## Acceptance criteria

- [ ] Six modules build; `:core:rules`, `:core:decks`, `:core:session`, `:core:ai` use the `kotlin("jvm")` plugin
- [ ] Adding `import android.util.Log` to `:core:rules` **fails the build**
- [ ] A CI check rejects any `androidx.*` dependency in `:core:*` beyond `androidx.annotation`
- [ ] A four-card test deck loads through `DeckSource` and validates
- [ ] One round plays end to end on a physical device: choose a stat, see both values, see a winner
- [ ] Win direction works both ways — a `LOW_WINS` metric is won by the lower value
- [ ] The mid-round view exposes only the contested metric on the opponent's card
- [ ] `Transport` carries `ByteArray`, and `LoopbackTransport` round-trips the real codec
- [ ] No `Dispatchers.*` literal appears anywhere in `:core:*`
- [ ] A debug APK installs and runs on a physical phone

## Testing

This slice establishes the test pattern for everything that follows: plain JVM tests, no emulator, no instrumentation, one double.

At the seam — two `MatchSession` instances over an in-memory `Transport` — assert that a single round resolves correctly under both win directions, and that the mid-round `PlayerView` carries `OpponentCardView.Contested` holding exactly the played metric.

Add the belt-and-braces leak test now, while the view graph is small: serialise the mid-round view to JSON and assert that no unplayed stat value appears anywhere in the string. It is crude, but it is the assertion that catches an accidental field addition a year from now.

Deck validation tests read the real test-deck files through the `java.io.File` `DeckSource`, taking the directory from an injected system property rather than a relative path — relative paths break the moment someone runs a test from the IDE.

No prior art exists; these are the first tests in the codebase.
