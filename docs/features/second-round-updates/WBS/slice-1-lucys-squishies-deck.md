# Slice 1 — Lucy's Squishies deck

> Part of the `second-round-updates` feature. PRD: [`../PRD.md`](../PRD.md)

**Delivers:** A thirty-card Squishmallow deck, real-photo-and-cited where a freely-licensed photo
exists, with a documented mix of real specs and playful invented ratings as its five stats.

## What to build

Author `decks/lucys-squishies/manifest.json` following the same schema every existing deck uses (30
cards, 5 metrics, every card carrying every metric). Choose a roster of 30 Squishmallow characters,
biased toward whichever ones have a real, freely-licensed (Wikimedia Commons or equivalent CC-source)
photograph available — official Squishmallow product photography is Jazwares/Kellytoy copyrighted and
won't clear this bar for most named characters, so expect real substitution work: research a larger
candidate list than 30, keep whichever have a citable photo, and swap out ones that don't.

Five stats, documented in the manifest's `conventions` block as either real-and-cited or
invented-and-playful — do not blur the two:

- **Real, cited specs** (pick at least two): retail height/size, retail price (MSRP), release year —
  each sourced the same way `motorcycles`/`lucys-youtubers` cite theirs, recorded per card in a
  `_sources` block.
- **Invented, playful ratings** (the remainder): e.g. Cuddliness, Rarity/collectibility — clearly
  framed in the `conventions` block as "not a real published figure, invented for gameplay/fun,"
  rather than presented alongside the real specs without distinction.

## Design notes

Follow the `motorcycles`/`lucys-youtubers` precedent throughout: lowercase filenames, WebP images at
the same size/quality target, licence/author/source URL recorded per image, and the same
`DeckLoader.load()`/`DeckLoader.parse()` validation path (no shortcuts, no hand-rolled JSON-shape
checks). See the [Motorcycles WBS slice](../../top-trumps-core-game/WBS/slice-3-motorcycles-deck.md)
for the exact acceptance-criteria shape this slice mirrors.

If `card-visual-identity` slice 6 (themed card backgrounds) has shipped by the time this deck is
authored, give it a background image motif too (plush/stitching pattern or similar) — a follow-up
asset addition, not a blocker either direction.

## Blocked by

None — the manifest schema this needs already exists (`Card.image`, `theme` block). Research and
image sourcing can start immediately, same as the Motorcycles slice's own risk profile.

## Acceptance criteria

- [ ] Thirty Squishmallow characters authored, each with a real, freely-licensed photograph, licence,
      author and source URL recorded
- [ ] Five metrics defined, with the manifest's `conventions` block clearly stating which are
      real-and-cited and which are invented-and-playful — no metric left ambiguous between the two
- [ ] Every real-and-cited stat value has a source recorded in `_sources`; no real-framed stat is
      invented
- [ ] All images are lowercase-named WebP, matching the existing deck size/quality convention
- [ ] `DeckLoader` validates the deck with no schema changes required
- [ ] The deck appears in the picker automatically (no code change), same as adding any deck folder
- [ ] Every card can win on at least one metric against the rest of the deck (the same property test
      the Motorcycles slice established)
- [ ] A full solo match plays with the real deck content and images

## Testing

Same seam as every other deck: JVM validation tests against the real, committed
`decks/lucys-squishies/` content through `DeckLoader`/`FileDeckSource` (mirroring
`MotorcyclesDeckTest`/the equivalent `lucys-youtubers` test) — no fixture, nothing can drift from
what ships. Include the "every card can win on at least one metric" property test.

Image rendering and thumbnail memory behaviour are verified by hand on device, same boundary as every
other deck.

## Delivered

_Not yet delivered._
