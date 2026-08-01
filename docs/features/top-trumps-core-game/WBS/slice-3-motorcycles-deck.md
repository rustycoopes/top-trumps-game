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

- [x] Thirty cards authored, matching the PRD roster, spanning 1923–2018
- [x] Every stat value carries a cited source; no value is invented
- [x] A weight convention and a top-speed convention are nominated and recorded
- [x] Every card has a CC-licensed photograph of the correct model, with licence, author and source URL recorded
- [x] All images are WebP, ~1080px long edge; total deck under ~5MB
- [x] All filenames are lowercase
- [x] Manifest validation rejects: wrong card count, a card missing a metric, a metric lacking unit or direction, an unresolvable image reference
- [x] The deck picker enumerates deck folders at launch and shows Motorcycles
- [x] Adding a second deck folder makes it appear in the picker **with no code change**
- [x] A full solo match plays with real cards and images
- [x] The win-pile grid renders thirty thumbnails without an OOM on the oldest available phone

## Testing

Validation tests run at the JVM seam against the **real** `decks/` content through the `java.io.File` `DeckSource` — no fixture, so nothing can drift from what ships.

Assert the structural rules above, and add one that matters disproportionately: **every card can win on at least one metric** against the rest of the deck. A card that loses on all five under its own deck's win directions is dead weight in a hand, and story 73 exists precisely to prevent that. This is a property of the *content*, so it belongs in a content test rather than an engine test.

Also assert that age comparison is independent of the current date, by evaluating it against two different clock values.

Image rendering, thumbnail sizing and memory behaviour are verified by hand on device — they sit above the seam.

## Delivered

Issue: [#4](https://github.com/rustycoopes/top-trumps-game/issues/4) · Branch: `slice-3-motorcycles-deck` · Date: 2026-07-31

All acceptance criteria met. The thirty-card `decks/motorcycles/manifest.json` was built from four
parallel research passes (three ten-bike batches plus one focused follow-up on the hardest-to-cite
gaps), each using live web search/fetch against real sources — manufacturer archives,
motorcyclespecs.co.za, Wikipedia infoboxes, period road tests, owners' clubs, museums and auction
houses — never invented. Every card's `_sources` block (not consumed by `DeckLoader`, just
human-readable) records which source backs each of its five stats, and the manifest carries a
`conventions` block documenting the weight (dry, kerb/wet as a flagged fallback) and top-speed
(claimed, else best-corroborated road test) conventions the PRD asked for.

**The genuinely hard case: overall length for eight pre-1970s/naked-bike models.** After three
independent research passes, no source anywhere (manufacturer literature, owners' clubs, museums,
auction houses, workshop manuals) publishes an overall-length figure for the Brough Superior SS100,
Triumph Speed Twin, Vincent Black Shadow, Triumph Thunderbird 6T, BSA Gold Star DBD34, Velocette
Venom, Kawasaki H2 Mach IV 750 or KTM 1290 Super Duke R — every one of them publishes wheelbase and
nothing more. Per the PRD's own instruction to nominate a documented convention rather than invent
a figure, wheelbase is used as a clearly flagged fallback for exactly those eight cards (each
`_sources.length` carries `isWheelbaseFallback: true`, a citation, and an explanation); every other
card's length is genuine overall length. One card (Brough Superior SS100) had no citable dry weight
either; its `_sources.weight` is flagged as using a lower-tier aggregator's wet-weight figure as a
documented fallback, for the same reason. A follow-up research pass also resolved two source
conflicts from the initial batches: Moto Guzzi 850 Le Mans top speed (126 vs 130 mph — corroborating
period road tests recorded 132–134 mph, so 130 is used) and KTM 1290 Super Duke R top speed (the
commonly-repeated 180 mph traces to an unsourced aggregator, not KTM; 159 mph from MCN's spec table
is used as the best-attested independent figure).

Schema: `CardImage` (file/licence/author/sourceUrl) was added to `Card` in `:core:rules`, and a
`MetricDisplay` enum (`RAW`/`YEARS_SINCE_VALUE`) plus `MetricSpec.displayDirection()` implement the
TDD §7 age design — the stored value stays a plain model year compared `LOW_WINS` by the engine
(never a clock, never a derived value), while `displayDirection()` flips the *displayed* direction
so "Age — N years" correctly reads "higher wins" to a player. The host computes this once in
`MatchView.toMatchView()` before it ever reaches the wire, so the client only ever renders it.
`DeckLoader` validation was tightened to match the TDD/AC: exactly 30 cards, exactly 5 metrics,
every metric has a non-blank unit, and every card's image reference must resolve via `DeckSource` —
covered by new `DeckLoaderTest` cases (including a `@TempDir`-built fixture deck for the
unresolvable-image case, deliberately kept out of the shared `/decks` root since that root ships
wholesale into the app's assets) and a new `MotorcyclesDeckTest` that validates the real content and
asserts the WBS's "every card can win on at least one metric" property directly against the deck.

`:app` gained `AppGraph.listDecks()`/`startSoloMatch(deckId)` (previously hardcoded to
`"test-deck"`), a `DeckPickerScreen`, and Coil 3 `AsyncImage` rendering in `MatchScreen` with the
disk cache disabled and explicit per-usage decode sizes (220dp live card, 96dp win-pile thumbnail)
— both `test-deck` and `motorcycles` show up in the picker with no code change, satisfying that
criterion directly. Manually verified end-to-end on the same physical Samsung device as prior
slices: the picker lists both decks, a solo match plays through several rounds with real card art
rendering correctly on both the live card and the fully-revealed opponent card, the win-pile grid
renders real thumbnails, and the age display reads correctly (e.g. a 1968 card showing "58 years"
against 2026). The win pile was exercised with a handful of cards rather than a full 30-card pile
(reaching that would mean rigging every round against the AI); thumbnail memory safety rests on the
explicit 96dp decode target design (TDD §7) rather than an observed 30-card OOM-free run.

Code review (code-review-master + code-quality-guardian, run in parallel) found and fixed three
issues before merging: `AppGraph.listDecks()` was being called unmemoized from the picker's
composable body, redoing a full manifest-parse-and-image-resolution walk on every recomposition —
now wrapped in `remember`; `MetricSpec.displayDirection()` was built but never actually called from
any production path — now wired into `MatchView.toMatchView()`, which also let a real gap get
closed (story 27's "see clearly whether a stat is won by the higher or lower value" had no UI
anywhere; stat buttons now show "(higher wins)"/"(lower wins)"); and the Coil `ImageLoader` had no
shutdown path — added a `DisposableEffect`. A fourth, lower-priority finding (manifest image
sub-fields being required-with-no-default means a missing one collapses `DeckLoader`'s per-field
error reporting into one generic parse-error message) was filed as
[#13](https://github.com/rustycoopes/top-trumps-game/issues/13) rather than fixed now, since it's a
deck-authoring-experience quality issue, not a functional bug — content is developer-authored and
already caught by tests.

**Diverged from the plan:**
- The Vincent Black Shadow's Commons photo is a 1950 Series C (Girdraulic front forks), not a
  literal 1948 Series B (girder forks) — no genuinely-licensed Commons photo of the specific 1948
  variant was found. Same model family, visually close, flagged for a maintainer's judgement call.
- The Suzuki GSX-R750's only usable Commons photo shows two bikes side by side (1985 and 1989
  models); it was cropped to isolate the correct 1985 machine before conversion.
- Several other Commons images are one model-year off the exact roster year (e.g. a 1937 Speed Twin
  for the 1938 launch year, a 1995-captioned Ducati 916 for 1994) — same unchanged first-series
  spec in each case, noted per-card in `_sources` rather than silently accepted.
