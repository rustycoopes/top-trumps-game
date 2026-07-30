# Slice 3 — The real Motorcycles deck

> Part of the `top-trumps-core-game` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** Thirty real motorcycles with photographs and accurate specs, chosen from a deck picker.

## What to build

The content that makes the game worth playing, plus the machinery to select it.

Author the thirty-card Motorcycles deck against the roster in the [PRD](../PRD.md#deck-format-and-content) — 1923 to 2018, all internal-combustion. For each card: engine capacity, top speed, model year, dry weight and length, **sourced and cited, never invented**; a Wikimedia Commons CC-licensed photograph of the actual model; and the licence, author and source URL recorded per card.

Add manifest validation that refuses a malformed deck loudly, a deck picker for Player One that enumerates whatever deck folders are present, and Coil-based image rendering on the card and in the win-pile grid.

**Published motorcycle figures genuinely disagree between sources** — dry versus kerb versus wet weight, claimed versus tested top speed. Nominate a convention per metric, record it in the manifest, and cite a source per card, or the game will assert things enthusiasts will dispute.

This slice is content-heavy and largely independent of the rest of the build. Only the manifest schema from slice 1 blocks it, so **the research and image sourcing can start immediately, in parallel with everything else**. The PRD flags deck content as the principal effort risk and it sits on the critical path to anything that looks finished.

## Design notes

Storage layout and why: [TDD §7](../TDD.md#7-deck-storage-and-loading) and the [deck storage ADR](../../../adr/top-trumps-core-game-deck-storage.md). Content lives at repo root in `decks/`, registered via `assets.srcDir` rather than a `Copy` task. `assets/` is the **only** location that supports runtime enumeration — classpath directory walking is impossible on Android, and `res/drawable` fails outright.

Images: WebP lossy at quality ~80, ~1080px on the long edge, roughly 120KB each and ~3.6MB total. Coil with the **disk cache disabled** (the source is already local storage) and **explicit target sizes per usage** — a win-pile grid loading full-resolution bitmaps is ~3.5MB each in memory and will OOM a mid-range phone at thirty of them.

The deck content hash covers the **manifest bytes only**, or is precomputed at build time. Hashing 3.6MB of images during the handshake is 100–300ms of the user waiting for nothing.

Age is `LOW_WINS` on the stored year, with `display: YEARS_SINCE_VALUE` applied as a UI transform. The engine never sees a derived value or a clock.

**Lowercase every filename.** Asset paths are case-sensitive on device but not on Windows, and this repo lives on `C:\dev\` — the failure mode is a build that works in your emulator and breaks on the phone.

`AssetManager.list()` does not distinguish files from directories; enumerate `decks/` and accept every entry whose `manifest.json` opens successfully.

## Blocked by

- [Slice 1](slice-1-walking-skeleton.md) — **schema only.** The research and image sourcing need no code and can begin at once.

## Acceptance criteria

- [ ] Thirty cards authored, matching the PRD roster, spanning 1923–2018
- [ ] Every stat value carries a cited source; no value is invented
- [ ] A weight convention and a top-speed convention are nominated and recorded
- [ ] Every card has a CC-licensed photograph of the correct model, with licence, author and source URL recorded
- [ ] All images are WebP, ~1080px long edge; total deck under ~5MB
- [ ] All filenames are lowercase
- [ ] Manifest validation rejects: wrong card count, a card missing a metric, a metric lacking unit or direction, an unresolvable image reference
- [ ] The deck picker enumerates deck folders at launch and shows Motorcycles
- [ ] Adding a second deck folder makes it appear in the picker **with no code change**
- [ ] A full solo match plays with real cards and images
- [ ] The win-pile grid renders thirty thumbnails without an OOM on the oldest available phone

## Testing

Validation tests run at the JVM seam against the **real** `decks/` content through the `java.io.File` `DeckSource` — no fixture, so nothing can drift from what ships.

Assert the structural rules above, and add one that matters disproportionately: **every card can win on at least one metric** against the rest of the deck. A card that loses on all five under its own deck's win directions is dead weight in a hand, and story 73 exists precisely to prevent that. This is a property of the *content*, so it belongs in a content test rather than an engine test.

Also assert that age comparison is independent of the current date, by evaluating it against two different clock values.

Image rendering, thumbnail sizing and memory behaviour are verified by hand on device — they sit above the seam.
