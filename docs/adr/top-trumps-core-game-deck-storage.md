# Deck content lives at repo root and is read through a DeckSource interface

**Status:** Proposed
**Date:** 2026-07-30
**Feature:** [`top-trumps-core-game`](../features/top-trumps-core-game/TDD.md)

## Context

The PRD requires that adding a themed deck be a **content drop with no code change** — the app enumerates whatever deck folders are present at launch and shows them in a picker.

Three properties are in tension:

1. Deck folders must be **enumerable at runtime on the device**.
2. `:core:decks` must stay Android-free so its validation logic runs under plain JUnit.
3. JVM tests should validate the **real** deck content, not a fixture that can drift from it.

The obvious answer — put the deck in a JVM module's `src/main/resources/` so tests read it from the classpath and `:app` gets it transitively — fails property 1 in a way that is easy to miss.

## Decision

**Deck content lives at the repository root, in no module:**

```
/decks/motorcycles/manifest.json
/decks/motorcycles/images/*.webp
```

`:app` registers it as an asset source directory rather than copying it:

```kotlin
android.sourceSets.getByName("main").assets.srcDir(rootProject.file("decks"))
```

Access is behind a pure interface in `:core:decks`:

```kotlin
interface DeckSource {
    fun listDecks(): List<String>
    fun open(deckId: String, path: String): InputStream
}
```

Android implementation wraps `AssetManager`; the JVM test implementation wraps `java.io.File` and reads the real content. The test path takes its location from an injected system property set by Gradle — never a relative path, which breaks the moment a test is run from the IDE with a different working directory.

**The deck content hash covers the manifest bytes only**, or is precomputed at build time and stored in the manifest.

**Images are WebP lossy q80 at ~1080px on the long edge** (~120KB each, ~3.6MB total), loaded by Coil with the **disk cache disabled** and **explicit target sizes per usage**.

## Alternatives considered

**JVM module `src/main/resources/`.** This was the natural candidate: the resources *are* packaged into the APK and point-reads via `ClassLoader.getResourceAsStream` *do* work on Android. Rejected because **classpath directory enumeration is impossible on Android** — `getResource("decks/")` returns nothing walkable, and there is no directory-entry walk over an APK's resource namespace. It would force a hardcoded deck list, which is precisely what the PRD forbids. Worse, if the content sat in both a module's resources and `assets/`, the APK would silently ship 3.6MB twice.

**`res/drawable`.** Rejected outright: enumerable only via `Resources.getIdentifier()` name-by-name or reflection over `R.drawable`, both discouraged and both silently broken by resource shrinking. Fails the core requirement.

**A Gradle `Copy` task from a module into `:app/assets`.** Works, and was the original suggestion. Rejected in favour of `assets.srcDir` because a copy task introduces task-ordering and up-to-date bugs — the classic symptom being an edited manifest that doesn't reach the APK because the task didn't rerun.

**Hashing the full deck including images.** Rejected: reading 3.6MB of assets during the handshake is 100–300ms of the user waiting, and the manifest already names every image. The check guards against version skew between two copies of our own app, not against tampering.

## Consequences

**Easier:** a new deck is genuinely a folder drop; `:core:decks` stays Android-free with no classpath tricks; validation tests exercise real production content, so there is no fixture to drift; the deck folder is visible at repo root where a content author would look for it.

**Harder:** deck content sits outside the module system, so it is not expressed as a dependency and nothing in Gradle enforces that `:app` includes it — a missing `assets.srcDir` line yields an empty picker with no build error.

**Platform gotchas this makes live:**
- `AssetManager.list()` does not distinguish files from directories. The robust idiom is to list `decks/` and accept every entry whose `decks/<x>/manifest.json` opens successfully.
- **Asset paths are case-sensitive on device but not on a Windows dev machine.** Since this repo lives at `C:\dev\`, a build that works in the emulator and fails on the phone is a live risk. Lowercase all filenames.
- A win-pile grid loading full-resolution bitmaps is ~3.5MB each in memory; 30 of them will OOM a mid-range phone. Thumbnails in the grid, full size only in the detail view.
