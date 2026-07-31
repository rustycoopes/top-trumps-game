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

- [x] Six modules build; `:core:rules`, `:core:decks`, `:core:session`, `:core:ai` use the `kotlin("jvm")` plugin
- [x] Adding `import android.util.Log` to `:core:rules` **fails the build**
- [x] A CI check rejects any `androidx.*` dependency in `:core:*` beyond `androidx.annotation`
- [x] A four-card test deck loads through `DeckSource` and validates
- [x] One round plays end to end on a physical device: choose a stat, see both values, see a winner
- [x] Win direction works both ways — a `LOW_WINS` metric is won by the lower value
- [x] The mid-round view exposes only the contested metric on the opponent's card
- [x] `Transport` carries `ByteArray`, and `LoopbackTransport` round-trips the real codec
- [x] No `Dispatchers.*` literal appears anywhere in `:core:*`
- [x] A debug APK installs and runs on a physical phone

## Testing

This slice establishes the test pattern for everything that follows: plain JVM tests, no emulator, no instrumentation, one double.

At the seam — two `MatchSession` instances over an in-memory `Transport` — assert that a single round resolves correctly under both win directions, and that the mid-round `PlayerView` carries `OpponentCardView.Contested` holding exactly the played metric.

Add the belt-and-braces leak test now, while the view graph is small: serialise the mid-round view to JSON and assert that no unplayed stat value appears anywhere in the string. It is crude, but it is the assertion that catches an accidental field addition a year from now.

Deck validation tests read the real test-deck files through the `java.io.File` `DeckSource`, taking the directory from an injected system property rather than a relative path — relative paths break the moment someone runs a test from the IDE.

No prior art exists; these are the first tests in the codebase.

## Delivered

Issue: [#2](https://github.com/rustycoopes/top-trumps-game/issues/2) · Branch: `slice-1-walking-skeleton` · Date: 2026-07-31

All acceptance criteria met. The six-module structure, version catalog, and `build-logic`
convention plugins (`toptrumps.jvm-library`, `toptrumps.android-library`,
`toptrumps.android-application`) are in place; `:core:rules`, `:core:decks`, `:core:session`,
`:core:ai` use `kotlin("jvm")` with `explicitApi()`, and `import android.util.Log` in
`:core:rules` was manually confirmed to fail the build. `checkCoreDependencyAllowlist` (wired
into `check` for every `:core:*` module, and run in CI) enforces the androidx.annotation-only
allowlist. A four-card test deck at `/decks/test-deck/manifest.json` loads and validates through
`DeckSource` (`FileDeckSource` for JVM tests, `AndroidAssetDeckSource` on device). `RulesEngine`
has no `when (metric)` anywhere and resolves a round correctly in both win directions. `Transport`
carries `ByteArray`; `LoopbackTransport` round-trips the real `ProtocolCodec` (JSON), verified by
a leak test that serialises the guest's view and asserts no un-contested stat value appears in it.
No `Dispatchers.*` literal appears in `:core:*` production code — dispatchers are threaded through
as an injected `CoroutineScope`. A debug APK was installed and played end-to-end (both win
directions) on a physical Samsung device via `adb`, confirmed by screenshot at each step.

**Toolchain, decided during implementation (not pre-specified in the WBS):** Gradle 9.3.1 (the
minimum AGP 9.1.1 will run on), AGP 9.1.1, Kotlin 2.2.0, JDK 17
(`C:\dev\android-build-tools\jdk-17.0.20+8`, the only JDK 17 available on the dev machine — the
Android Studio-bundled JBR is JDK 25, too new for Gradle at the time this was written). AGP 9.x
has built-in Kotlin support, so the convention plugins apply `com.android.application`/
`com.android.library` without a separate `org.jetbrains.kotlin.android` plugin.

**Diverged from the plan:**
- `RulesEngine.deal()` picks the first chooser at random (`Seat.HOST` or `Seat.GUEST`) rather than
  always `Seat.HOST`. Otherwise the guest-side AI would never have a turn to exercise in this
  slice's single-round shape.
- `MatchView` (`:core:session`) is a wire-serializable mirror of `PlayerView`, not `PlayerView`
  itself. `PlayerView`'s constructor is `internal` to `:core:rules` by design (structural-redaction
  ADR), so `:core:session` cannot reconstruct one from decoded bytes in a different Gradle module —
  `MatchView` is what the guest side of `MatchSession` (and the AI, and the Compose UI) actually
  holds. It mirrors `OpponentCardView`'s full shape, including the `Revealed` variant, even though
  `RulesEngine.project()` never produces `Revealed` yet (win piles are out of scope this slice).
- A code review pass (code-review-master + code-quality-guardian) surfaced a real race condition —
  `HostMatchSession.submit()` originally mutated shared state synchronously on the caller's thread
  (the Compose UI thread) while guest-originated intents mutated it via the injected
  `CoroutineScope`'s dispatcher. Fixed by routing `submit()` through `scope.launch {}` like the
  guest-intent path, and by having `AppGraph` (`:app`, not `:core:*`) supply a
  `Dispatchers.Default.limitedParallelism(1)`-confined scope — matching the TDD's own guidance
  ("confine all state mutation to a single dispatcher") without hardcoding a dispatcher inside
  `:core:session` itself. Also fixed in the same pass: an uncaught `IOException` path in
  `DeckLoader.load()`, a missing `AppGraph`/`MatchSession` teardown in `MainActivity.onDestroy()`,
  and dead code (`RemoteRoundState.winnerSeat()`).
