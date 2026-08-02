# Slice 1 — Test Infrastructure Spike

> Part of the `card-visual-identity` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** Proof that `:app` can run a JUnit 4 + Robolectric + Compose UI test under this repo's
exact toolchain, before any card code depends on it.

## What to build

A new `:app` unit-test source set with the Gradle wiring for JUnit 4, Robolectric and Compose UI
testing, exercised by exactly one throwaway test: render a `Text` composable and assert it exists in
the semantics tree. Nothing about the card, the theme, or any real screen.

This is deliberately the smallest possible slice. Its only job is to convert "we believe this
combination of versions works" into "we know it does" — or to surface a real incompatibility while it
still costs an hour to fix, not after the card composable and its tests are built on top of it.

## Design notes

Full reasoning in [ADR: app test framework](../../adr/card-visual-identity-app-test-framework.md).

- `:core:*` is JUnit 5 (`useJUnitJupiter()`). `:app` diverges to **JUnit 4**, because
  `createComposeRule()` is a JUnit 4 `TestRule` and `RobolectricTestRunner` is a JUnit 4 `Runner` —
  there is no version of this seam that isn't JUnit 4 underneath. The divergence is contained: a
  different module, a different convention plugin, no shared configuration to conflict.
- `app/build.gradle.kts` needs `testOptions.unitTests.isIncludeAndroidResources = true` (still
  defaults to `false` under AGP 9) plus `testImplementation`/`debugImplementation` for
  `junit:junit:4.13.2`, `org.robolectric:robolectric:4.16.1`, and BOM-managed
  `androidx.compose.ui:ui-test-junit4` / `ui-test-manifest`. **`ui-test-manifest` must be
  `debugImplementation`**, not `testImplementation` — it has to reach the merged debug manifest.
  `testOptions` goes in `app/build.gradle.kts`, not the convention plugin (only `:app` has tests).
- Pin the emulated SDK explicitly in `app/src/test/resources/robolectric.properties` (`sdk=35`).
  **compileSdk 37 is not the risk it looks like** — Robolectric emulates `targetSdkVersion` (35,
  explicitly set by the convention plugin), never `compileSdk`. A manifest/emulated mismatch is a hard
  `ShadowPackageParser` failure, not a warning, which is exactly why pinning matters.
- **AGP 9.1.1 / Gradle 9.3.1 is the one genuine unknown** — Robolectric's own compatibility table
  stops at AGP 8.12, though Robolectric migrated its own build to AGP 9 in early 2026. This slice
  exists specifically to convert that from "probably fine" to "confirmed".
- Add `--add-opens=java.base/java.lang=ALL-UNNAMED` and `java.util` to the test task's `jvmArgs` — CI
  runs JDK 17, the local Studio JBR is JDK 25, and Robolectric does bytecode instrumentation.
- **Expect a `libs.versions.toml` merge conflict.** The unmerged `feature/history` branch already adds
  `junit4`, `robolectric` and `androidx-test-core` aliases with this exact reasoning. Copy that
  module's config rather than inventing a second one.
- Gradle 9's `failOnNoDiscoveredTests` defaults to `true`, so a JUnit 4/5 misconfiguration fails the
  build loudly rather than silently running zero tests.

## Blocked by

None — can start immediately, in parallel with [Slice 2](slice-2-theme-deck-data-foundation.md).

## Acceptance criteria

- [ ] `:app` has a working `src/test/kotlin` source set with the JUnit 4 + Robolectric + Compose test
      dependencies wired in `app/build.gradle.kts`
- [ ] One throwaway Robolectric + `createComposeRule()` test renders a `Text` and asserts it via
      `onNodeWithText(...).assertExists()`
- [ ] `./gradlew :app:test` runs it green, locally and matching CI's JDK 17
- [ ] `./gradlew build` picks it up with no change to `.github/workflows/ci.yml` — confirm by running
      the exact command CI runs
- [ ] The throwaway test is deleted (or clearly marked temporary) once Slice 3's real tests land

## Testing

This slice *is* the test — there is no seam above it. Prior art for the Gradle configuration exists on
the unmerged `feature/history` branch's `build.gradle.kts`, which should be copied rather than
reinvented. No production code changes in this slice.

## Delivered

Issue: [#26](https://github.com/rustycoopes/top-trumps-game/issues/26) · Branch:
`slice-1-test-infrastructure-spike` · Date: 2026-08-02

Built as designed, with one correction to the "expect a merge conflict" note: `:feature:history`
(already merged, not an unmerged branch) had already added the `junit4` and `robolectric`
aliases to `gradle/libs.versions.toml`, at `4.13.2`/`4.14.1` rather than the ADR's `4.16.1` — kept
the existing shared version rather than forking it, since a bump would also move
`:feature:history`'s test classpath and a real test run confirms `4.14.1` works fine on this
toolchain. Only the two BOM-managed Compose-testing aliases (`compose-ui-test-junit4`,
`compose-ui-test-manifest`) were new. `testOptions.unitTests.isIncludeAndroidResources`, the
`testImplementation`/`debugImplementation` split, and the `--add-opens` `jvmArgs` block landed in
`app/build.gradle.kts` exactly as specified.

One addition beyond the ADR's literal text: `app/src/test/resources/robolectric.properties` also
sets `application=android.app.Application`, overriding the manifest-declared
`TopTrumpsApplication` (whose `onCreate()` builds a full `AppGraph` — NSD, Room, sound) so the
smoke test — and every future `:app` unit test — starts isolated from real app startup rather
than silently inheriting it. Confirmed via `AskUserQuestion` before implementing; the ADR has been
updated with the same reasoning.

**AGP 9.1.1 / Gradle 9.3.1 confirmed working**, the "one genuine unknown" this slice existed to
resolve. Verified with a real JDK 17 (Temurin 17.0.20+8, matching CI's `temurin-17`) — the local
Android Studio JBR is JDK 25, which turned out to fail Robolectric tests for a different reason
than the ADR anticipated (see below), so it can't be used for local verification here. Both
`./gradlew :feature:history:test :app:test` and `./gradlew build --stacktrace` (the exact command
CI runs) are green, with zero changes to `.github/workflows/ci.yml`.

Code review (run in parallel by `code-review-master` and `code-quality-guardian`) independently
found the same real issue: running Robolectric tests under the local Android Studio JBR (JDK 25)
fails with `org.objectweb.asm.ClassReader: Unsupported class file major version 69`, not the
`InaccessibleObjectException` the ADR's `--add-opens` flags were written to prevent — a pre-existing
gap (also reproduces on unmodified `master`'s `:feature:history` suite), not something this slice
introduced, and CI is unaffected since it runs JDK 17. Filed as
[#31](https://github.com/rustycoopes/top-trumps-game/issues/31) rather than fixed here, since it's
a local-dev-only trap and the fix (possibly bumping `robolectric` to `4.16.1`) has classpath-wide
blast radius outside this slice's scope. The review's other finding — the `application=` override
being undocumented — was fixed directly (comments added to `robolectric.properties`, plus a new
paragraph in the ADR).

Not verified on-device: not applicable — this slice has no UI to run, only a JVM test.
