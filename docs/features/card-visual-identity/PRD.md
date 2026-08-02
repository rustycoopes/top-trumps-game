# PRD: card-visual-identity

## Problem Statement

The game plays correctly but doesn't look like a card game. A card today is a bare photo in a square
box, with its name as a loose line of text above it and its five stats rendered as a scrolling stack
of generic Material buttons below. Nothing about it says "Top Trumps" — there's no printed frame, no
title banner, no stat table, no sense of holding a physical card. The app has no visual identity of
its own at all: every screen rides Jetpack Compose's stock Material3 defaults, because no theme was
ever built.

I want cards that look like the real thing: a coloured card face with the photo framed in a window at
the top and all five stats grouped into a clear table at the bottom, the way a physical Top Trumps
card is laid out.

## Solution

Every card in the game — your own card, your opponent's revealed card, a card in your win pile, a
deck's representative card in the picker — is redrawn as a single card-shaped object: a title banner
naming the item, a framed photo window beneath it, and a five-row stat table filling the bottom of
the card, styled like a printed card rather than a UI panel. Each deck can carry its own accent
colour, so a Motorcycles card and a future YouTubers card look like they came from different physical
packs, the way real Top Trumps decks do.

Your own card fills most of the screen as a single "hero" card with no scrolling — the score bar and
round counter sit in a slim strip above it, the stat rows on the card are what you tap to choose, and
the prompt and "Continue" button sit in a fixed strip below it. When a round resolves, your
opponent's card flips in beside yours at full detail, so you can compare every stat side by side, not
just the one that was played — the stat that decided the round is called out clearly (win, lose, or
tie), while the other four are visible but understated. The win pile becomes a grid of small versions
of the same card — recognisably the same object, just smaller — and tapping one opens it at full
size. The deck picker shows each deck as a themed card tile instead of a plain button.

## User Stories

### The card itself

1. As a player, I want my card to look like a printed Top Trumps card — a titled, framed, coloured
   card face — so that the game feels like the physical original rather than a spreadsheet.
2. As a player, I want the card's photo shown in a clearly framed window at the top of the card, so
   that the picture reads as part of the card's print design rather than a floating image.
3. As a player, I want all five of a card's stats grouped into one clear table at the bottom of the
   card, so that I can read them at a glance the way I would on a physical card.
4. As a player, I want each stat row to show its label, its unit, and its value together, so that I
   understand the number without needing a separate legend.
5. As a player, I want to see clearly whether a stat is won by the higher or the lower value shown
   directly on its row, so that I don't play a stat expecting the wrong outcome (delivers PRD story
   27 from `top-trumps-core-game`, whose arrow was specified but never built).
6. As a player, I want every card I see in the game — mine, my opponent's, a card in my pile, a
   deck's card in the picker — to be the same recognisable object at whatever size it's shown, so
   that the game has one consistent visual language rather than several ad hoc ones.
7. As a player, I want a card with an unusually shaped source photo to still look like a properly
   printed card, so that a handful of oddly-cropped photos don't break the illusion for the rest of
   the deck.

### Playing a round

8. As a player, I want my own card to fill most of the screen as the clear focus of the round, so
   that playing feels like holding up a physical card rather than scrolling a page.
9. As the player whose turn it is, I want to choose a stat by tapping its row directly on my card, so
   that the card itself is the thing I interact with, not a separate list of buttons.
10. As a player, I want a stat row that's unavailable (already tied and excluded from a tiebreak
    rechoice) to look and behave as disabled directly on the card, so that I can't tap something that
    won't be accepted.
11. As a player, I want the score, round number, and a way to leave the match always visible in a
    slim strip that doesn't compete for space with my card, so that the card can be as large as
    possible without hiding information I need throughout the round.

### The reveal

12. As a player, I want my opponent's full card to appear beside mine once a round is decided, so
    that I can see both complete cards at once rather than one replacing the other.
13. As a player, I want to compare all five stats between the two cards at the reveal, not just the
    one that was played, so that I can see how the round might have gone on a different stat.
14. As a player, I want the stat that actually decided the round to stand out clearly as a win, loss,
    or tie, so that the result of the round is never ambiguous even while I'm looking at the other
    four comparisons.
15. As a player, I want the other four stats shown plainly, without competing visually with the
    actual result, so that "how did I do overall" doesn't drown out "did I win this round".

### The win pile and deck picker

16. As a player, I want the cards in my win pile shown as small versions of the same card design, so
    that my pile visibly looks like a stack of the cards I've been playing, not a row of thumbnails.
17. As a player, I want to open any card in my win pile at full size to see its complete stat table,
    so that I can look properly at something I won earlier (delivers PRD story 49 from
    `top-trumps-core-game`, specified but never built).
18. As a player choosing a deck, I want each deck shown as a themed card-style tile with its own
    colour and a representative image, so that picking a deck feels like picking a physical pack
    rather than reading a list of names.

### Consistency and accessibility

19. As a player, I want the card to hold its shape and stay readable if I've turned up my phone's
    text size, so that accessibility settings don't break the card's layout.
20. As a player using the app at night, I want the surrounding screen (score bar, prompts, buttons)
    to adapt to my phone's dark mode, so that the app isn't jarringly bright in a dark room — while
    the card itself keeps its printed colours regardless, the way a physical card would.
21. As a player, I want stat rows on my own card to remain comfortably tappable regardless of screen
    size, so that choosing a stat is never a precision task.

## Implementation Decisions

### Scope and relationship to `top-trumps-core-game`

- This feature is a **visual and interaction redesign of existing screens**, not a new capability.
  It touches no rules, no session protocol, and no persistence. It closes two stories that were
  already in the `top-trumps-core-game` PRD but never implemented: story 27 (win-direction arrow on
  the card) and story 49 (opening a win-pile card at full size).
- The app currently has **no theme at all** — no `Theme.kt`/`Color.kt`/`Type.kt`, no bundled fonts,
  `MaterialTheme {}` is never called, every screen renders on Compose Material3 defaults. This
  feature introduces the app's first theme, scoped to card surfaces plus the chrome immediately
  around them (match screen, win pile, card back, deck picker). Lobby, settings, name entry, and
  manual connect are left on themed Material defaults — explicitly out of scope, see below.

### The card composable

- A single card composable replaces today's `CardImage` (a bare `AsyncImage` in a 220dp square) as
  the one place a card's photo and stats are drawn, reused at every size the game needs — the hero
  card during play, the side-by-side reveal, win-pile thumbnails, deck-picker tiles, and the face-down
  card back mid-flip.
- **Card shape:** nominal 2:3 portrait ratio, matching the physical reference cards used to define
  this feature and the ratio already assumed by the repo's (unbuilt) `design/assets.csv` card-back
  entry. The face is divided into three zones: a title banner (~10% of height), a framed image window
  (~50%), and the stat table (~40%).
- **Two variants of the same composable:** a **full** variant with the complete stat table, used
  anywhere there's room to read it (hero card, side-by-side reveal, an opened pile card), and a
  **mini** variant — frame, image window, and title only, no stat table — used below a size threshold
  where a table would be illegible (win-pile grid, deck-picker tile, the card mid-slide-to-pile).
  Both variants share the same frame geometry so a card never visibly changes shape between them.
- **Stat row:** `LABEL (UNIT) …… VALUE` with a direction arrow — e.g. "TOP SPEED (MPH) …… 186 ▲" —
  replacing today's single interpolated string on a Material `Button`. In the hero card, each row is
  itself the tap target for choosing that stat, with a **minimum 48dp row height** enforced even
  where a strict 2:3 card geometry would otherwise produce narrower rows — the card is allowed to run
  slightly taller than 2:3 rather than ship an under-sized touch target. In the side-by-side reveal
  and pile views, rows are read-only and can run narrower since nothing there is tappable.
- **Reveal marking:** the row for the round's decided stat gets a strong highlight and an explicit
  WIN/LOSE/TIE marker; the other four rows show both players' values with only a faint tint — loud
  result, quiet comparison.
- **Image fit:** photos are centre-cropped (`ContentScale.Crop`) into the fixed image window. Source
  photos in the Motorcycles deck range from 0.669 to 1.846 aspect ratio; no image re-cropping or
  per-card focal-point data is introduced by this feature.
- **Card back:** drawn programmatically from the deck's accent colour rather than a bundled image —
  same frame geometry, rounded corners, and border as the front, plus the deck's name in the display
  font. This replaces today's hardcoded navy/gold placeholder (`CardBack`) and guarantees the back
  and front are always geometrically identical, which the flip animation depends on.
- **Physicality:** flat saturated accent colour, a white inner border around the image window, rounded
  corners, a hairline outer edge, and a static drop shadow on the non-animating hero card only. No
  texture or gloss bitmaps, and no `Modifier.shadow` on anything mid-animation (it re-renders every
  frame — a constraint already called out in the `top-trumps-core-game` WBS for this exact reason).

### Per-deck theming

- A deck's manifest gains an **optional `theme` block**: an accent colour, a text/foreground colour
  for use on that accent, and an optional `heroCardId` naming which card represents the deck in the
  picker (defaults to the deck's first card if omitted). A deck with no `theme` block renders with a
  classic yellow-on-white default, so `test-deck` and any deck authored before this feature continue
  to load and display without modification.
- Colours are stored as **hex strings** on the plain-Kotlin `Deck`/`DeckTheme` types in `:core:rules`,
  not as Compose `Color` — `:core:*` modules are JVM-only and may not depend on Compose (CI-enforced
  dependency allowlist). `:app` converts hex to `Color` at the point of use.
- **The theme never crosses the wire.** `deckId` already reaches every screen that needs it, and the
  two-device handshake already proves both devices hold byte-identical deck manifests via the
  existing `manifestHash` check. Each device resolves its own theme from its own local copy of the
  deck; `RemoteCardFace`, `RemoteMetricSpec`, and the session protocol are unchanged by this feature.
- The manifest's `theme` block is additive and read via the existing "unknown keys are ignored"
  JSON parsing, so it doesn't break older manifests. It does change the SHA-256 the handshake
  computes over `manifest.json`'s bytes for any deck that adopts it — both devices already need to be
  on the same build for a deck's hash to match today, so this introduces no new constraint, just
  extends an existing one.
- Accent colours for the two decks the project currently has content for (Motorcycles,
  `lucys-youtubers`) are proposed and contrast-checked during implementation, not fixed by this PRD.

### Typography

- One bundled open-licence (SIL OFL) heavy condensed display font is added for card titles and stat
  labels/values — the elements that carry most of the "printed card" look. Everything else keeps the
  system font. This is the first bundled font in the app; no `res/font` directory exists today.

### Screen layout changes

- **Match screen:** restructured from a single scrolling column into three fixed regions — a slim top
  bar (scores, round counter, leave-match), the hero card (no scrolling), and a bottom action strip
  (prompt text, Continue). The existing `AnimationGate`, flip, and slide-to-pile mechanics are
  preserved; the flip (`FlippableOpponentCard`) and slide (`SlideOverlay`/`SlidingCard`) currently
  assume a single square `size: Dp` and must change to width-plus-derived-height so an animating card
  never changes shape mid-flight, and the slide overlay renders the mini card variant instead of a
  bare image.
- **Win pile:** grid of mini cards; tapping one opens it as a full card (closing story 49).
- **Deck picker:** each deck rendered as a themed card tile (accent colour, hero card image, deck
  name) in place of today's plain per-deck button.
- **Result screen:** gets the same chrome theming as the rest of the app (colours, typography) but
  stays a text-and-button summary — no card showcase of the match's cards.

### Accessibility

- Card text honours the system font-scale setting up to a **cap of roughly 1.3×**, beyond which the
  card's geometry stops growing to avoid the layout collapsing at extreme scale (up to 2× on Android).
  Chrome outside the card (top bar, action strip, deck picker, result screen) scales without a cap.
- The card's colours and geometry are **invariant between light and dark mode** — a physical card
  doesn't change colour when the room lights dim. The surrounding chrome adopts light/dark theming.
  Each deck's proposed accent is checked for at least 4.5:1 contrast against its stat text.

## Testing Decisions

### What makes a good test here

As with `top-trumps-core-game`, tests assert on externally observable behaviour, not on internal
structure or pixel output. Two distinct kinds of "externally observable" apply to this feature:

- **Data correctness** (does a deck's theme parse the way the schema says it should) is exactly the
  kind of thing the existing manifest-validation tests already cover for the rest of the manifest,
  and extends naturally.
- **Interaction correctness** (is the right row tappable, does tapping it fire the right choice, is a
  disabled row genuinely inert) is behaviour a player would notice going wrong, independent of exact
  colours or pixel layout.

Neither test kind asserts on visual appearance — colour, spacing, crop quality — which this project
has consistently treated as a manual, on-device concern (see `top-trumps-core-game`'s testing
decisions: "Compose UI... animation and audio are verified manually on device. They sit above the
seam by design."). This feature accepts that same boundary rather than introducing pixel/screenshot
testing.

### Seam 1 — deck theme parsing, at the existing `DeckLoader.parse` seam

Extends the JVM unit tests already exercising manifest validation in `:core:decks`. No new seam; the
`theme` block is just more manifest surface for the same parser.

- A manifest with a valid `theme` block parses into a `Deck` carrying that theme.
- A manifest with no `theme` block parses successfully and the deck carries the default theme.
- A manifest with a malformed accent colour (not a valid hex string) fails validation with a clear
  error, the same way a malformed metric direction does today.
- A `heroCardId` that doesn't match any card in the deck fails validation.
- The real, committed `decks/motorcycles/manifest.json` continues to validate whether or not it has
  adopted a `theme` block at the time these tests are written.

### Seam 2 — the card composable's interaction contract, via Robolectric Compose semantics tests (new)

A new seam, introduced because the card's tap-to-choose behaviour — previously a `Button`-per-stat
loop, now rows on the card itself — is real interaction logic worth protecting from regression, and
the repo currently has zero automated coverage of any Compose UI.

Runs on the JVM via `./gradlew test` (Robolectric + `createComposeRule()`), not on an emulator or
physical device — consistent with this repo's existing, deliberate avoidance of instrumentation
testing everywhere else (`top-trumps-core-game`'s seam is explicitly "no emulator, no
instrumentation"). This requires new test dependencies (`androidx.compose.ui:ui-test-junit4`,
Robolectric) in `:app`, which has no test source set today.

Asserts on the semantics tree — what's exposed and what's clickable — not on rendered pixels:

- Every enabled stat row is exposed as clickable and, when tapped, invokes the card's choose-stat
  callback with that row's metric key.
- A row marked unavailable (tied and excluded from a re-choice) is exposed as disabled and tapping it
  does not invoke the callback.
- The mini card variant exposes no clickable rows at all — it is a read-only view.
- After a reveal, the decided stat's row carries a win/lose/tie semantics label; the other four rows
  do not.

Explicitly not covered by this seam: colour, crop, spacing, animation timing/perspective, and font
rendering. Those remain `@Preview` (per-state, in Android Studio), a debug-build-only style gallery
screen (all 30 cards of a deck, every card state, reachable from Settings, for real photos/real crops/
long names on a physical device), and a manual on-device pass — matching how flip/slide/sound/inset
handling were verified in `top-trumps-core-game`'s polish slice.

### What is tested, by seam

| Behaviour | Seam |
|---|---|
| Theme block parses; absent block defaults correctly; malformed hex/heroCardId rejected | `DeckLoader.parse` (extended) |
| Correct row tappable/disabled; correct callback and metric key; mini variant has no tap targets; win/lose/tie semantics on the decided row | Robolectric Compose semantics (new) |
| Colour, crop, layout, font rendering, contrast | `@Preview` + debug gallery + manual device pass |
| Flip has no shape jump between back/front; no mirrored face; both cards slide together; no dropped frames on an old device; status/nav-bar clearance; card holds up at 130% font scale | Manual on-device pass |
| Two-device handshake still succeeds once a deck's manifest carries a `theme` block | Manual two-device pass, both devices on the same build |

### Prior art

`DeckLoader`'s existing manifest-validation tests in `:core:decks/src/test` are the direct pattern for
seam 1. There is no prior art in this repo for seam 2 — it's the first Compose test of any kind — so
its scope is deliberately narrow (interaction contract only) rather than an attempt to cover
everything Compose can break.

## Out of Scope

- **Flavour text / prose on the card** (a "Top Trumps File" style description block). The target
  layout for this feature is the classic/plain reference style — title, image, stat table — which
  needs no new per-card content. Adding prose would mean authoring new text for every card in every
  deck, which this feature does not take on.
- **Per-card focal-point cropping.** Centre-crop is accepted as-is; a focal-point hint per card is a
  plausible future refinement, not part of this feature.
- **Screenshot/pixel-diff testing.** Explicitly rejected in favour of the existing project convention
  of manual on-device verification for anything visual — see Testing Decisions.
- **Restyling lobby, settings, name entry, or manual connect** beyond inheriting the app's base theme
  colours/typography as Material defaults. Those screens carry no card visuals and are not part of
  this feature's brief.
- **A card showcase on the result screen.** The result screen is themed but stays a text summary.
- **Any change to the rules engine, session protocol, match history, or deck manifest's non-theme
  content.** This feature is additive to the manifest schema (one optional `theme` block) and touches
  no gameplay logic.
- **New decks or deck content.** Only the two decks that already exist in the repo (Motorcycles,
  `lucys-youtubers`) get theme colours as part of this feature.
- **Landscape or tablet layouts, localisation, or accessibility beyond what's specified above** — same
  exclusions as `top-trumps-core-game`, unchanged by this feature.

## Further Notes

### Relationship to existing design notes

`design/assets.csv` describes an abandoned fantasy-creature deck and was already marked superseded by
the `top-trumps-core-game` PRD; this feature does not revive it, but does adopt its assumed 2:3
card-back ratio, which lines up independently with the physical reference cards used to scope this
feature.

### Sequencing

Recommended slicing for `/to-wbs`, so the risky new-Compose-test-infrastructure work is proven before
it's relied on by later slices:

1. **Card foundation** — theme (Color/Type/Theme), the bundled font, the `theme` manifest block and
   its `DeckLoader` validation, the card composable itself (full + mini + back), `@Preview`s, the
   debug gallery, and the new Robolectric Compose test seam. Nothing user-visible changes yet — this
   is the highest-risk slice for tooling (first Compose test in the repo) but the lowest-risk slice
   for gameplay regression, since it touches no screen still in active use.
2. **Match screen** — the visible redesign: hero card, tappable rows, slim chrome, side-by-side
   reveal, reworked flip/slide geometry.
3. **Remaining surfaces** — win pile (mini cards + open-at-full-size), deck picker tiles, themed
   result screen, and applying the two decks' proposed accent colours.

### Risk

The Robolectric Compose semantics seam is genuinely new infrastructure for this repo — Robolectric's
interaction with the Compose UI test APIs, and with the Compose compiler's stability configuration
already in place for `:app` (`compose-stability-config.conf`), should be proven on a small, contained
composable (the card) before any later feature leans on the same seam for something larger.
