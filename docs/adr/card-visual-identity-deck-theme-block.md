# Deck visual theme is optional manifest data, held as ARGB value classes in :core:rules, resolved locally on each device

**Status:** Proposed
**Date:** 2026-08-01
**Feature:** [`card-visual-identity`](../features/card-visual-identity/TDD.md)

## Context

Cards must look like they came from distinct physical packs — a Motorcycles card visually different
from a YouTubers card — which means each deck needs an accent colour, a legible ink colour to sit on
it, and a nominated card to represent the deck in the picker.

Four constraints bear on where that data lives and how it is shaped:

1. **`:core:*` cannot see Compose.** `checkCoreDependencyAllowlist` in
   `build-logic/src/main/kotlin/toptrumps.jvm-library.gradle.kts` fails the build if anything outside
   `org.jetbrains.*` plus `androidx.annotation:annotation` reaches a `:core:*` compile classpath. So
   `androidx.compose.ui.graphics.Color` is unavailable wherever `Deck` is declared.
2. **Adding a deck must stay a content drop.** The deck-storage ADR and `AppGraph.listDecks()` exist
   precisely so a new deck folder needs no code change.
3. **Two phones must agree.** The guest renders cards from its own assets; a divergence would show
   the wrong colours.
4. **The Compose stability configuration** declares `com.toptrumps.rules.*` and
   `com.toptrumps.session.*` stable. A type placed in a package outside that list, and used as a
   composable parameter, silently destroys recomposition skipping on the match screen.

## Decision

**An optional `theme` block in `manifest.json`**, absent by default:

```json
"theme": { "accent": "#B3121B", "onAccent": "#FFFFFF", "heroCardId": "kawasaki-ninja-h2" }
```

**`DeckTheme` is declared in `:core:rules`** (package `com.toptrumps.rules`), with
`Deck.theme: DeckTheme = DeckTheme.DEFAULT`. A deck with no `theme` block renders in a classic
yellow-on-white default, so every existing manifest keeps working unmodified.

**Colours are a value class over ARGB `Int`, not hex strings:**

```kotlin
@JvmInline public value class ArgbColor(public val argb: Int)
```

`DeckLoader.parse` accepts `#RRGGBB` only, forces alpha to `0xFF`, and converts once — parse, don't
validate. Alpha is forced because a translucent card accent is never the intent and accepting eight
digits would invite alpha-compositing questions in the renderer for no benefit.

**The theme does not cross the wire.** `RemoteCardFace`, `RemoteMetricSpec` and the session protocol
are unchanged. Each device resolves the theme from its own local manifest via a memoised
`AppGraph.deckTheme(deckId)`.

**`:app` converts `ArgbColor` → `androidx.compose.ui.graphics.Color`** in `theme/DeckPalette.kt`.

**Invariant to preserve:** `DeckTheme` may hold only values a manifest author literally types. No
`Dp`, no ratios, no font names, no zone percentages, no light/dark variants. The moment card geometry
is wanted on it, it belongs in `:app`.

## Alternatives considered

**Hex `String` on the domain type.** Rejected. `DeckTheme` has a public constructor, so a `String`
leaves an invalid state representable in a type that has supposedly already passed validation —
forcing `:app` to carry a fallback branch for a malformed value it can never actually receive, which
is untestable in anger and will rot. `ArgbColor` makes `Color(theme.accent.argb)` total. It also
matches the repo's own idiom: `MetricKey` and `StatValue` are both `@JvmInline value class` over a
primitive, so a raw `Int` would be the odd one out and a `String` doubly so.

**`DeckTheme` in `:core:decks`.** Rejected — it cannot work. `core/decks/build.gradle.kts` declares
`api(project(":core:rules"))`, so `:core:rules` cannot depend on `:core:decks` without a cycle, and
`Deck.theme` could therefore not be typed there. The workaround — returning theme *alongside* `Deck`
as a `LoadedDeck(deck, theme)` — means two values that must travel together through
`AppGraph.startSoloMatch`, `MatchController`, and the guest resolution path. A worse failure mode than
the purity cost.

**A new `:core:theme` module or an `:app`-only package.** Rejected. Any package not listed in
`compose-stability-config.conf` is inferred unstable and would break match-screen skipping during
exactly the animations this feature makes more expensive.

**An `:app`-side lookup keyed by `deckId`.** Rejected outright: adding a deck would require an app
code change, contradicting the deck-storage ADR and the entire point of `listDecks()`.

**Sending the theme over the wire.** Rejected as unnecessary coupling. `RemoteCardFace.imageFile`
already does exactly this on *weaker* footing — the guest renders the host's photos from its own
assets using a filename that arrived over the wire, and `manifestHash` deliberately covers the
manifest bytes only, not the images. Theme colours live *inside* the hashed file, so they are
strictly better protected than something already shipped and load-bearing.

**Extracting a dominant colour from each photo at runtime.** Rejected: unpredictable (muddy greys from
dark photos), varies card-to-card within a deck which breaks the "one pack" illusion, and adds
per-image processing to the load path.

**A sidecar `theme.json` next to `manifest.json`.** Rejected and explicitly warned against — it would
sit outside the hashed bytes and invalidate the local-resolution reasoning entirely. This is the most
likely way a future change silently breaks this ADR.

## Consequences

**Easier:** decks keep their content-drop property; `:core:*` stays Android-free with no allowlist
change (`ArgbColor` adds no dependency); every `DeckTheme` reaching `:app` is valid by construction;
the protocol is untouched, so no two-device compatibility work; and stability is free because
`com.toptrumps.rules.*` is already declared stable.

**Harder:** `Deck` widens further into presentation territory — though that line was already crossed
deliberately (`Deck.name` is documented as "the picker's display label"; `Card.image` carries licence
metadata the engine never reads). Without the stated invariant, `Deck` becomes the dumping ground for
every subsequent visual field.

**Wider visibility than expected:** `Deck` is passed into `HostMatchSession` and `AiOpponentDriver`.
`:core:ai` was split out so it cannot see more than a remote opponent can; theme is harmless
information-wise, but it is now visible there.

**A hash-mismatch caveat worth recording accurately:** the `manifestHash` check is **guest-side only** —
`HostHandshake.chooseDeck` treats 500ms of silence as acceptance, and only `GuestHandshake.run`
compares hashes. So "both devices hold identical manifests" is guest-verified, host-assumed. For theme
this fails closed (a guest with a divergent manifest returns `DeckRefused` and never renders a card),
but the invariant should not be stated more strongly than the code supports.

**The decisive safety argument:** the worst case of local resolution being wrong is *a wrong colour*.
No rules divergence, no protocol desync, no crash, no possibility of the two devices disagreeing about
who won. That asymmetry is what makes this correct rather than merely lucky.

**A new failure mode, and how it is handled.** `AppGraph.listDecks()` is
`mapNotNull { Invalid -> null }`, so hard-failing on a malformed theme would make an entire 30-card
deck **vanish from the picker with no message anywhere** over a single typo'd hex — the validation
errors are handed to `listDecks()`, which discards them.

That silent-drop behaviour is correct for everything `DeckLoader` validates today, because all of it is
**functional** (30 cards, 5 metrics, every card carrying every metric, every image resolving) — such a
deck genuinely cannot be played. The `theme` block is **cosmetic**, so it is treated differently:

- **At runtime, malformed theme fields degrade.** Bad accent/`onAccent` hex falls back to the default
  colour; a `heroCardId` matching no card falls back to the deck's first card. The deck loads and
  plays, just in the wrong colour.
- **In CI, they fail the build.** An all-decks test enumerates every folder under `/decks` and asserts
  each theme block parses without degrading, so a typo is caught at commit time.

The rule to preserve: **functional manifest errors stay hard failures; cosmetic ones degrade.** This
also gives `decks/lucys-youtubers/` its only test coverage — today only `test-deck` and `motorcycles`
have deck tests.
