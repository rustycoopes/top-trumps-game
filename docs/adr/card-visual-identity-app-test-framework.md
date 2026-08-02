# :app unit tests use JUnit 4 with Robolectric, diverging from the repo's JUnit 5

**Status:** Proposed
**Date:** 2026-08-01
**Feature:** [`card-visual-identity`](../features/card-visual-identity/TDD.md)

## Context

The card's tap-to-choose behaviour was previously a `Button`-per-stat loop and becomes rows on the
card itself. That is real interaction logic worth protecting from regression, and the repo has zero
automated coverage of any Compose UI today.

The obstacle is framework alignment. `toptrumps.jvm-library.gradle.kts` configures every `:core:*`
test suite with `useJUnitJupiter()`, and all existing tests are JUnit 5. But
`androidx.compose.ui.test.junit4.createComposeRule()` is a JUnit 4 `TestRule`, and Robolectric's
`RobolectricTestRunner` is a JUnit 4 `Runner`. `:app` has no test source set and no test dependencies
at all.

`CLAUDE.md` also states plainly that testing here is "Plain JVM tests, no emulator, no
instrumentation" — so whatever is chosen must run under `./gradlew test`, not on a device.

## Decision

**`:app` uses JUnit 4, in `:app` only.** `:core:*` keeps Jupiter, unchanged.

```kotlin
android { testOptions { unitTests { isIncludeAndroidResources = true } } }

dependencies {
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit4)                     // junit:junit:4.13.2
    testImplementation(libs.robolectric)                // org.robolectric:robolectric:4.16.1
    testImplementation(libs.compose.ui.test.junit4)     // BOM-managed
    debugImplementation(libs.compose.ui.test.manifest)  // BOM-managed
}
```

`ui-test-manifest` must be `debugImplementation`, not `testImplementation` — it contributes the
`ComponentActivity` declaration `createComposeRule()` launches, so it has to reach the **merged debug
manifest**.

`testOptions` goes in `app/build.gradle.kts`, **not** the convention plugin —
`toptrumps.android-application` is applied by `:app` alone, and `toptrumps.android-library` is applied
by two modules that have no tests.

**Pin the emulated SDK** in `app/src/test/resources/robolectric.properties`:

```
sdk=35
```

**Override the emulated `Application` class** in the same file, to `android.app.Application`.
`AndroidManifest.xml` declares `TopTrumpsApplication`, whose `onCreate()` builds a full
`AppGraph` — NSD discovery, `ConnectivityManager`, a real Room database, sound setup. With
`isIncludeAndroidResources = true` merging that manifest in, Robolectric would otherwise
instantiate the real `TopTrumpsApplication` for every `:app` test, coupling even a trivial
composable test to full app startup. The override applies to the whole `test` source set, not
per-test, so a future test that switches to `createAndroidComposeRule<MainActivity>()` will hit a
`ClassCastException` at `application as TopTrumpsApplication` — expected, given the override, not
a bug in that test.

**Add `--add-opens` JVM args** for `java.base/java.lang` and `java.base/java.util` on the test task:
CI runs JDK 17 while the local Android Studio JBR is JDK 25, and Robolectric performs bytecode
instrumentation. Without this the failure mode is "green in CI, `InaccessibleObjectException` in
Studio", or the reverse.

## Alternatives considered

**JUnit 5 vintage engine.** Rejected. Every API in this seam is JUnit 4 native, so even under the
vintage engine the test *code* would still be JUnit 4 — it would add `useJUnitPlatform()` plus two
artifacts to run JUnit 4 tests through a JUnit 5 launcher wrapping a Robolectric runner that already
does its own classloader surgery. More layers, no benefit.

**`tech.apter.junit5.jupiter:robolectric-extension`.** Rejected as the highest-risk option: version
0.9.0, last published 2024-11-18, pre-1.0, single maintainer. And it does not solve
`createComposeRule()` — still a JUnit 4 rule — so it would additionally force a switch to the
experimental `runComposeUiTest {}`.

**Instrumented `androidTest` on an emulator or device.** Rejected: more faithful (real rendering, real
touch dispatch) but reintroduces exactly the emulator dependency this repo has deliberately avoided
everywhere else, and CI has no emulator runner.

**Screenshot or pixel-diff testing (Paparazzi/Roborazzi).** Rejected in the PRD and not revisited: it
is new golden-image infrastructure with churn on every deliberate visual tweak, during a redesign
where visual churn is the whole point.

## Consequences

**Easier:** the seam runs on the JVM under `./gradlew test`, so **CI needs no change** — measured
against this repo, `:app:build` already resolves to `:app:testDebugUnitTest → :app:test → :app:check`,
and the existing `**/build/reports/tests/` failure-artifact upload already covers it. No convention
plugin change. No source-set configuration either: AGP 9's built-in Kotlin already registers
`src/test/kotlin`, matching the repo's `src/main/kotlin` convention.

**The divergence is genuinely contained** — different module, different convention plugin, different
classpath, no shared configuration to conflict. And Gradle 9's `failOnNoDiscoveredTests` defaults to
`true`, so a JUnit 4/5 misconfiguration fails loudly rather than passing silently with zero tests run.

**Harder:** two test frameworks in one repo, so contributors must know which module uses which. Anyone
writing `:app` tests by copying a `:core:*` test will get Jupiter annotations that do not run.

**A feared risk that turned out not to be one:** `compileSdk = 37` versus Robolectric's SDK lag.
Robolectric emulates `targetSdkVersion`, never `compileSdk`, and the convention plugin sets
`targetSdk = 35` explicitly — so AGP 9's `defaultTargetSdkToCompileSdkIfUnset` never fires. Robolectric
4.16.1 covers API 23→36 and needs only Java 17. Pinning `sdk=35` removes the residual risk entirely,
including on a future compileSdk bump; a manifest/emulated mismatch is a hard `ShadowPackageParser`
failure, not a warning.

**The one genuine unknown is AGP 9.1.1 / Gradle 9.3.1**, which is beyond anything Robolectric's
compatibility table certifies (it stops at AGP 8.12). Countervailing evidence is good — Robolectric
migrated its own build to AGP 9 with built-in Kotlin in early 2026 — but this is untested, not
known-working. **Mitigation: spend the first hour of slice 1 standing up a single trivial "renders a
Text, asserts it exists" Robolectric + Compose test before writing any card code.** If AGP 9 has broken
the `com/android/tools/test_config.properties` contract, that surfaces in an hour rather than after
the card is built.

**A CI cost worth knowing:** Robolectric fetches its ~100MB `android-all-instrumented` runtime jar into
`~/.m2/repository` at *test* time, outside Gradle's dependency cache. CI has no Gradle or Maven caching
today, so every run re-downloads it — roughly 30–60s per run, plus a hard dependency on Maven Central
during the test phase. Either add a cache step or declare the jar as a `testImplementation` dependency.

**An authoring trap this configuration creates:** `compose-stability-config.conf` force-declares
`com.toptrumps.rules.*` and `com.toptrumps.session.*` stable, and strong skipping is unconditional in
this compiler version. A test that mutates a `RemoteCardFace` **in place** and expects recomposition
will see nothing happen. Tests must recompose by replacing the whole state object.

**A design constraint this imposes:** Robolectric's default screen is 320×470 mdpi. The full-vs-mini
variant threshold must therefore be an **explicit parameter**, never inferred from measured screen
width, or these tests become silently qualifier-dependent and brittle.
