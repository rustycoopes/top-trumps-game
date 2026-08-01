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

- [ ] Compose stability configuration in place; match-screen recomposition counts verified as bounded **before** animation work begins — config verified via Compose compiler metrics (see Delivered), runtime recomposition counts **need a physical device**
- [ ] Cards flip on reveal with correct perspective and no mirrored back face — **needs a physical device to confirm visually**, see Delivered
- [ ] Won cards visibly travel to the winner's pile — **needs a physical device to confirm visually**, see Delivered
- [ ] Animations are skippable/instant on reconnect and resync — implemented and unit-tested at the seam (`MatchSession.lastResync`); the two-device reconnect path itself **needs two physical devices**, see Delivered
- [ ] Six sounds fire at the right moments; the first sound after launch is not silent — **needs a physical device to confirm audibly**, see Delivered
- [ ] Mute persists across launches and silences everything — **needs a physical device to confirm**, see Delivered
- [ ] Score bar and round counter clear the status bar and navigation bar on a gesture-nav device — **needs a physical device**, see Delivered
- [ ] No dropped frames on the oldest phone in the family during a reveal — **needs the oldest phone in the family**, see Delivered
- [ ] A backgrounded and resumed match does not replay stale animations — **needs a physical device**, see Delivered

## Testing

Almost entirely manual and on device, by design — this slice sits above the seam.

The exception worth automating: assert that animation state is **derived from** `PlayerView` transitions rather than driving them, so a resync can hard-cut without leaving the UI in a stale phase. That is a seam-level property about the `resync` flag, not a UI test.

Verify recomposition counts with Layout Inspector or Compose compiler metrics. **Test animations on the oldest available device** — jank is invisible on a flagship and this is a family game that will run on hand-me-downs.

## Delivered

Issue: [#8](https://github.com/rustycoopes/top-trumps-game/issues/8) · Branch: `slice-7-polish` · Date: 2026-08-01

Built as designed. `app/compose-stability-config.conf` declares `com.toptrumps.rules.*` and
`com.toptrumps.session.*` stable in the Compose compiler's `stabilityConfigurationFiles`, wired via
`app/build.gradle.kts`'s `composeCompiler {}` block (strong skipping needed no setting — this
compiler version enables it unconditionally; the toggle is deprecated and slated for removal).
Verified with Compose compiler metrics reports (`app/build/compose_reports`), compared before and
after: every match-screen composable taking `MatchSession`, `MatchView.InProgress`,
`RoundState.AwaitingChoice`/`Resolved` or `MatchView.Finished` flipped from `unstable` to `stable`.
A debug-only `LogRecomposition` helper (gated on `BuildConfig.DEBUG`) was added so the actual
runtime recomposition count — the half of this criterion that needs a device — can be checked via
`adb logcat -s Recomposition` rather than Layout Inspector.

The card flip (`FlippableOpponentCard` in the new `CardAnimations.kt`) uses `Animatable` +
`Modifier.graphicsLayer { rotationY; cameraDistance = 14 * density }`, content-swapped to the real
card face and counter-rotated 180° past the 90° crossing so it doesn't render mirrored; there's no
back-of-card art yet (`design/assets.csv`'s `card_back` was never generated), so a plain styled
placeholder stands in. The slide-to-pile (`SlideOverlayState`/`SlideOverlay`) is an overlay `Box`
inside `InProgressScreen`, positions captured via `onGloballyPositioned`/`boundsInRoot()` and
converted into the overlay's local coordinate space; both the player's and the opponent's card
travel to the winner's pile label concurrently.

**Skip-on-resync/background is `AnimationGate` (in `CardAnimations.kt`)**, built from two
independent signals rather than one latch — see its doc comment for why a single boolean was
wrong (code review caught this; see below). `MatchSession` gained `lastResync: StateFlow<Long?>`
— the revision of the most recent view a `HostToGuest.ResumeAck` delivered, `null`/never-set for
the host — finally putting the `resync` wire flag slice 6 added (and never read) to use; covered by
a new seam-level `ConnectionResilienceTest` matching the WBS's testing note exactly.

Six placeholder sound effects (`app/src/main/res/raw/sfx_*.wav`, matching `design/assets.csv`'s
naming and durations) were synthesized with a small Python script — pure sine/noise, no licensing
question, but **explicitly not final sound design**; swap the six files for real audio whenever
that's ready, same filenames. `SoundEffects` wraps `SoundPool` (`USAGE_GAME`), preloaded from
`AppGraph`'s constructor (which runs at app start, from `TopTrumpsApplication`), with a
pending-until-loaded queue so a cue requested before its own async `load()` completes still plays
once it does. `SoundPreferences` mirrors a mute flag from Preferences DataStore into a `StateFlow`,
toggled from a new switch on `SettingsScreen`.

Edge-to-edge: `enableEdgeToEdge()` in `MainActivity` plus a single `Modifier.safeDrawingPadding()`
on the `NavHost` itself, rather than per-screen — one fix for every screen's status/nav-bar
clearance (this slice's specific concern) and a free IME-avoidance fix for every text-entry screen.

**Both `code-review-master` and `code-quality-guardian` were run against the diff, in parallel, and
both found real issues — all fixed before merging:**

1. **Critical — two competing `preferencesDataStore(name = "settings")` delegates.** `SoundPreferences`
   declared its own delegate pointing at the same on-disk file `DisplayNamePreferences` already
   uses. DataStore allows exactly one open instance per file per process; since
   `SoundPreferences.muted` collects eagerly from `AppGraph`'s constructor and `DisplayNamePreferences.displayName`
   collects from `AppRoot()` moments later, both delegates would have opened concurrently and
   thrown `IllegalStateException: There are multiple DataStores active for the same file` on every
   launch — invisible to `./gradlew test` since it's pure-JVM with no real DataStore file I/O. Fixed
   by making `DisplayNamePreferences.kt`'s existing delegate `internal` and having `SoundPreferences`
   reuse it instead of declaring a second one.
2. **Major — `AnimationGate` could permanently mis-suppress a live animation.** The original design
   used one boolean (`isLive`), set back to `true` only when `InProgressScreen` recomposed — but a
   backgrounded-then-resumed match with an unchanged `view` value (StateFlow conflates on
   structural equality) never recomposes on resume, so the flag could stay stuck at "hard-cut" and
   silently suppress the *next genuinely live* reveal, slide and sound (and, worse, permanently miss
   the match-victory/defeat fanfare if that live transition happened to be the match's last).
   Redesigned into two independent signals: `hasSeenAwaitingChoice` (a one-time structural fact —
   was this mount's first content already resolved, i.e. a resync signature) and a one-shot
   `backgroundedSinceLastConsume` flag set directly by the `ON_STOP` lifecycle observer and consumed
   by exactly the next transition, regardless of what does or doesn't recompose in between.
3. **Major — winning cards slid to the pile one after another, not together.** `SlideOverlayState.slide`
   is `suspend` and doesn't return until its own animation finishes; the call site awaited the
   player's card's slide before even starting the opponent's, doubling the effective travel time and
   contradicting the class's own "a round can win both cards at once" doc comment. Fixed by launching
   both slides concurrently (`coroutineScope { launch { … }; launch { … } }`).
4. **Major — a fast "Continue" tap could leak a permanently frozen card.** `SlideOverlayState.slide`
   removed its item from the overlay list only after its animation completed normally; cancelling it
   mid-flight (advancing past `ResolvedContent` before the ~450ms slide finished, which the pile-tap
   flow does nothing to prevent) skipped that removal, leaving a frozen, partially-shrunk card
   rendered on top of every subsequent round for the rest of the match. Fixed with `try`/`finally`.
5. **Minor — `SlideOverlay` read the animated progress value in the composable body instead of
   inside the `offset {}`/`graphicsLayer {}` lambdas**, which meant the whole overlay recomposed on
   every animation frame — precisely the mistake the WBS's design notes call out by name. Fixed by
   deferring the read into each lambda.
6. **Minor — an inaccurate doc comment** claimed `enableEdgeToEdge()` "opts into predictive back";
   it doesn't (that's a separate manifest attribute plus callback API). Corrected.

**Not verified this session — no physical device (or a second one) was available.** Everything the
WBS's own Testing section calls "almost entirely manual and on device, by design": actual
recomposition counts on a running app, the flip's visual perspective and back-face orientation, the
slide's visible travel, all six sounds firing audibly and the first one not being silent, mute
actually silencing playback, status/nav-bar clearance on a real gesture-nav device, frame timing on
an old phone, and — because it requires a live two-device reconnect — the resync hard-cut's visual
effect end to end (its non-UI half, `MatchSession.lastResync`, is unit-tested).
