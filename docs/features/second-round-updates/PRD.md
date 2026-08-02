# PRD: second-round-updates

## Problem Statement

Live two-device testing this session validated that the game actually works end to end — discovery,
handshake, and a full match all hold up on real hardware. Two gaps surfaced from just playing it,
independent of each other and of the connectivity work that produced them:

1. **Only two decks exist**, both aimed at an adult/enthusiast audience (Motorcycles, YouTubers).
   There's no lighter, more playful deck.
2. **The lobby — the first screen anyone actually spends time looking at — is unstyled Material
   defaults.** `card-visual-identity` deliberately scoped lobby/settings/name-entry out of its own
   brief (see that PRD's Out of Scope), so nothing has touched it since the app's very first screens
   were built.

(A third gap found in the same session — the shipped card design reading as "bland" even with a
deck's accent colour applied — is scoped as `card-visual-identity` slice 6, not here, since it
directly revisits a decision that feature already made about the same composable.)

## Solution

Two independent, separately-shippable pieces of work:

1. **A new deck, "Lucy's Squishies"** — thirty Squishmallow characters, following the same
   manifest-driven deck format every existing deck uses, with real photographs where a freely-licensed
   one can be found and a mix of real published specs and clearly-playful invented ratings as its five
   stats.
2. **A themed lobby screen** — the lobby (peer list, invite/accept flow, name entry it's reached
   from) gets a real visual identity in place of stock Material defaults, starting from a small set of
   mocked-up directions reviewed before any code is written.

## User Stories

### Lucy's Squishies deck

1. As a player, I want a lighter, more playful deck alongside Motorcycles and YouTubers, so that the
   game has something for a younger or more casual audience.
2. As a player, I want each Squishmallow's card to show a real photo where one exists under a licence
   that permits it, so that the deck feels as legitimate as the other two rather than using stock art.
3. As a player, I want the deck's five stats to include real, checkable facts (size, price, release
   year) alongside a couple of clearly-for-fun ratings, so that the deck feels grounded even though
   Squishmallows don't have "official" stats the way a motorcycle's top speed does.

### Lobby theme

4. As a player, I want the lobby to look like it belongs to this game rather than a generic Android
   settings screen, so that the game feels finished from the very first screen I see.
5. As a player, I want the peer list, invite prompts, and connection states (searching, connected,
   waiting) to be visually clear and distinct, so that I understand what's happening during discovery
   and pairing without reading carefully.

## Implementation Decisions

### Lucy's Squishies deck

- **Format:** identical manifest schema to `motorcycles`/`lucys-youtubers` — 30 cards, 5 metrics,
  every card carrying every metric, `DeckLoader`'s existing validation unchanged.
- **Image sourcing:** same rigor as `lucys-youtubers` — real, freely-licensed (Wikimedia Commons or
  equivalent CC source) photographs, cited per card (licence, author, source URL). Coverage will be
  patchy for specific named Squishmallow characters (official product photography is Jazwares/Kellytoy
  copyrighted, not freely licensed), so the roster is chosen from whichever characters *do* have a
  freely-licensed photo available, the same substitution approach `lucys-youtubers` already used when
  a planned roster member had no citable image.
- **Stats — a documented mix, not uniform:** real, checkable specs (height in inches, retail price,
  release year) alongside one or two explicitly-invented, clearly-playful ratings (e.g. Cuddliness,
  Rarity/collectibility). The manifest's `conventions` block must state, per metric, whether it's a
  real cited figure or an invented rating — the same transparency precedent `motorcycles` set for its
  weight/top-speed conventions, extended to cover "this one is just for fun" rather than only
  "sources disagree, here's our tiebreak."
- **Deck id/folder:** `decks/lucys-squishies/`, matching the existing `lucys-youtubers` naming
  pattern.

### Lobby theme

- **Scope: the lobby screen only** — peer list, invite/accept prompts, connection-state screens
  reached from it. Name entry, settings, and manual-connect stay on Material defaults for now, matching
  how `card-visual-identity` scoped its own "chrome" work narrowly rather than all at once.
- **Process, not just an implementation:** before any code changes, produce 2–3 distinct visual-
  direction mockups (e.g. a dark competitive/esports look, a warm playful card-game look, a clean
  minimalist look) and get sign-off on one before implementing it. This is a deliberate first
  checkpoint of the slice, not a separate slice of its own.
- **Relationship to `card-visual-identity`'s theme:** that feature introduced the app's *first* theme
  at all (`Theme.kt`/`Color.kt`, a bundled display font), scoped to card surfaces and their immediate
  chrome. This slice extends the same theme system to the lobby rather than inventing a second,
  competing one — the chosen mockup direction should read as an extension of the existing card/match
  theming, not a clashing third visual language.

## Testing Decisions

Consistent with every other feature in this repo: `NsdManager`/lobby behaviour is manual, on-device,
two-physical-device testing (per `CLAUDE.md` and every prior slice's precedent) — nothing about lobby
theming introduces new automatable behaviour. The new deck's content is covered the same way
`motorcycles`/`lucys-youtubers` are: `DeckLoader` validation tests against the real, committed
manifest, plus the "every card can win on at least one metric" property test the Motorcycles slice
established.

## Out of Scope

- **The card background/texture work** — that's `card-visual-identity` slice 6, not this feature,
  since it revisits a decision that feature already made about the composable it owns.
- **Restyling name entry, settings, or manual-connect.** Lobby only, this round.
- **Landscape/tablet layouts, localisation** — same standing exclusion as every other feature in this
  repo.
- **A Squishmallow-specific rules/mechanic change.** This is a content-only deck addition; the rules
  engine, session protocol, and manifest schema (beyond the existing optional `theme` block already
  supporting `backgroundImage` once `card-visual-identity` slice 6 ships) are untouched.

## Further Notes

### Sequencing

The two pieces of this feature are independent and can ship in either order or in parallel:

1. **Lucy's Squishies deck** — content-heavy, no blockers, can start immediately (same profile as the
   Motorcycles slice: research and sourcing is the principal effort and risk).
2. **Lobby theme** — starts with the mockup-and-sign-off checkpoint described above, then
   implementation once a direction is chosen.
