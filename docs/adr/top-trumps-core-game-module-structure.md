# Six Gradle modules, with the core on the JVM plugin

**Status:** Proposed
**Date:** 2026-07-30
**Feature:** [`top-trumps-core-game`](../features/top-trumps-core-game/TDD.md)

## Context

The PRD mandates JVM-only tests at a single seam — no emulator, no instrumentation, no Wi-Fi, no second device. That only holds if the rules engine and session logic contain no Android dependencies.

In a single `:app` module, `src/test/` runs on the JVM but `android.jar` stubs sit on the compile classpath. Nothing prevents `android.util.Log.d(...)` or `@DrawableRes` appearing inside the rules engine, and nothing fails when it does — the test still compiles and then dies at runtime with `Stub!`. Android-freedom would be a convention enforced by code review.

The PRD is explicit that it wants structural guarantees rather than discipline (it applies the same reasoning to the reveal rule).

## Decision

Six modules. `:core:rules`, `:core:decks`, `:core:session` and `:core:ai` use the **`kotlin("jvm")` plugin**, not `com.android.library`. `:platform:net`, `:feature:history` and `:app` are Android.

Under the JVM plugin, `import android.*` is a **compile error**.

`:core:*` may depend only on `kotlinx-coroutines-core`, `kotlinx-serialization-json`, `kotlinx-datetime` and `androidx.annotation`, enforced by a CI check. Build configuration is four convention plugins in a `build-logic` included build, plus a version catalog.

Two placement calls worth stating explicitly:

- **TCP sockets live in `:core:session`, not the Android module.** `java.net.Socket` and `ServerSocket` are plain JVM. Only `NsdManager` is genuinely Android. This puts socket lifecycle and frame framing — where the nastiest bugs live — under JVM test against `127.0.0.1` with no test double at all.
- **`:core:ai` is its own module** specifically so the AI cannot reach `MatchState` through `internal` visibility. It can only consume `PlayerView`, which is the same guarantee a remote opponent has.

No dependency-injection framework; a hand-written `AppGraph` in `:app`.

## Alternatives considered

**Single module with package boundaries.** Simplest build, no inter-module plumbing, and at ~13 screens this app is not large. Rejected because it provides no enforcement whatsoever — the entire point is that Android-freedom is checkable by the compiler rather than by reviewers, and package boundaries in Kotlin are not access boundaries.

**Two modules (`:core` JVM + `:app` Android).** Gets most of the benefit for much less build configuration, and is a defensible middle ground. Rejected primarily because it collapses `:core:ai` into `:core:session`, where `internal` visibility would let the AI read `MatchState` and quietly cheat — and because `:feature:history` is genuinely deletable only if it is genuinely separate.

**Hilt for DI.** Rejected at this size: the hand-written graph is smaller, builds faster, and removes the temptation to inject `Context` into places that should never see it.

## Consequences

**Easier:** the single test seam actually works; a stray `Dispatchers.IO` or `android.util.Log` in the engine fails the build rather than a test run months later; `:feature:history` and `:core:ai` are provably severable; slice boundaries in the PRD map one-to-one onto modules.

**Harder:** more build configuration up front (~40 lines across six modules plus convention plugins); mismatched JVM targets between JVM and Android modules is the most common multi-module build failure and must be set once centrally.

**Forecloses:** nothing significant. If the module count proves excessive, merging is trivial; splitting later is not.

**Residual risk:** the JVM plugin blocks `android.*` but not everything harmful. `System.currentTimeMillis()`, `Dispatchers.IO` and JVM-artifact `androidx` libraries all still compile and all still break virtual-time testing. The CI dependency check and injected dispatchers cover this.
