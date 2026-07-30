# Slice 7 — Polish

> Part of the `top-trumps-core-game` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** It feels like a card game rather than a table of numbers.

## What to build

Cards flip on reveal and slide into the winner's pile. Sounds fire on stat selection, flip, round win and loss, and match victory and defeat, with a mute toggle that persists. The layout handles system insets properly.

Also in this slice, because it is invisible but load-bearing: the **Compose stability configuration**. Without it the match screen recomposes entirely on every state emission — during exactly these animations.

## Design notes

**Compose stability is a structural trap, not an accident** ([TDD §10](../TDD.md#10-ui)). `:core:*` are `kotlin("jvm")` modules compiled without the Compose compiler, so `PlayerView` and its collections are inferred **unstable** and the whole match screen recomposes on every emission. Fix with strong skipping plus a `stabilityConfigurationFile` listing the core packages. Do **not** add `androidx.compose.runtime` to `:core` to use `@Immutable` — that defeats the module discipline established in slice 1. Verify with recomposition counts *before* the animations land, or you will be tuning animation timings against a recomposition problem.

Card flip: `Modifier.graphicsLayer { rotationY; cameraDistance }`, not `AnimatedContent` (which loses the 3D entirely). Two things everyone gets wrong — `cameraDistance` defaults to `8 * density`, which for a full-width card produces a grotesque ballooning perspective, so raise it to ~12–16× density; and the back face renders **mirrored** past 90°, so swap content at the crossing *and* counter-rotate the back by 180°. Drive it with `Animatable` rather than `animateFloatAsState` so reconnect can `snapTo` without an unwanted animation.

Slide-to-pile: an **overlay `Box` with an animated offset**. `SharedTransitionLayout` is overkill for one animation and awkward here because the source composable is leaving the tree; `LookaheadScope` has the same problem. Capture start and target rects with `onGloballyPositioned`, animate a lightweight copy, remove on completion.

Both animations must use the **lambda modifier overloads** — `graphicsLayer { }` and `offset { }`. The value-taking versions recompose every frame and this is the single biggest Compose animation performance mistake. Keep `Modifier.shadow` off anything animating; the shadow re-renders per frame.

Audio via `SoundPool` with `USAGE_GAME`, preloaded at app start. **`load()` is asynchronous** — register `setOnLoadCompleteListener` or the first stat-selection sound is silent every launch. Mute reads from a `StateFlow` mirrored from DataStore, not a DataStore read per sound.

Edge-to-edge is **enforced at `targetSdk 35`** and cannot be opted out of. The always-visible score bar and round counter will render under the status bar without inset handling. Budget half a day; it always takes longer than expected on a screen with custom chrome.

Prefer deriving effects from state transitions (`LaunchedEffect` on round and phase) over a one-shot effects channel — it replays correctly after recomposition. But note the trap from [TDD §10](../TDD.md#10-ui): `collectAsStateWithLifecycle` stops at `STOPPED`, so anything genuinely one-shot must not ride that collection or it is dropped while backgrounded.

## Blocked by

- [Slice 3](slice-3-motorcycles-deck.md) — animating placeholder cards tells you nothing about how it feels.
- [Slice 5](slice-5-two-device-match.md) — animation timing must be verified against real network latency, not just loopback.

## Acceptance criteria

- [ ] Compose stability configuration in place; match-screen recomposition counts verified as bounded **before** animation work begins
- [ ] Cards flip on reveal with correct perspective and no mirrored back face
- [ ] Won cards visibly travel to the winner's pile
- [ ] Animations are skippable/instant on reconnect and resync
- [ ] Six sounds fire at the right moments; the first sound after launch is not silent
- [ ] Mute persists across launches and silences everything
- [ ] Score bar and round counter clear the status bar and navigation bar on a gesture-nav device
- [ ] No dropped frames on the oldest phone in the family during a reveal
- [ ] A backgrounded and resumed match does not replay stale animations

## Testing

Almost entirely manual and on device, by design — this slice sits above the seam.

The exception worth automating: assert that animation state is **derived from** `PlayerView` transitions rather than driving them, so a resync can hard-cut without leaving the UI in a stale phase. That is a seam-level property about the `resync` flag, not a UI test.

Verify recomposition counts with Layout Inspector or Compose compiler metrics. **Test animations on the oldest available device** — jank is invisible on a flagship and this is a family game that will run on hand-me-downs.
