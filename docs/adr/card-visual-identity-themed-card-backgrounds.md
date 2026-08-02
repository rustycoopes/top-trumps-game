# Themed card backgrounds are a static, per-deck, shared bitmap layer — not a per-frame effect

**Status:** Proposed
**Date:** 2026-08-02
**Feature:** [`card-visual-identity`](../features/card-visual-identity/TDD.md), slice 6

## Context

The original card-visual-identity PRD called for "flat saturated accent colour... No texture or
gloss bitmaps, and no `Modifier.shadow` on anything mid-animation (it re-renders every frame)." Two
distinct reasons were bundled into that one sentence: a **print-aesthetic** choice (the reference
cards this feature was scoped against are flat-printed, not textured) and a **performance** concern
(anything that recomputes itself every animation frame is expensive, and `Modifier.shadow` was the
concrete example that motivated the ban).

Live use surfaced a real complaint the flat-colour design didn't anticipate: the card reads as
plain/bland, even with per-deck accent colour applied. The ask is a faded, per-deck-themed
background image behind the stat table (and, per this session's discussion, retrofitted onto the
two decks that shipped without one).

This ADR re-examines only the **performance** half of the original reasoning, since the print-
aesthetic preference is a judgement call this slice is explicitly revising, not a constraint to
work around.

## Decision

**A background image is a static per-deck asset, decoded once and shared across all 30 cards of
that deck** — not a per-card image, and not something recomputed per frame or per animation state.
This is the same cost shape as the card's own framed photo, which the shipped feature already draws
during flip/slide with an accepted "no dropped frames on an old device" bar — the difference between
an accepted cost (a decoded bitmap composited into a `Canvas`/`Image`) and a rejected one
(`Modifier.shadow`'s per-frame blur-geometry recomputation) is exactly the distinction that matters
here, and a shared static bitmap sits on the accepted side of it.

Concretely:

- The deck's optional `theme` manifest block gains an optional `backgroundImage` field (a filename,
  same resolution rules as `Card.image`) resolved once per deck, not per card.
- Rendered full-bleed behind the whole card face, at reduced opacity (a fixed alpha, not
  configurable per deck — consistent faded look across every deck). The framed photo window and
  title banner sit on top and naturally occlude most of it; the stat-table zone is where it actually
  shows through.
- A semi-transparent scrim sits between the background image and the stat text, at the same
  4.5:1-contrast bar the PRD already set for accent-vs-text — the background must never compromise
  legibility, only add atmosphere behind it.
- No licence/author/source-citation fields the way `Card.image` carries them: this is decorative
  chrome, not a photographed real-world subject, so the "real, cited, never invented" sourcing rule
  that governs card *subject* photos doesn't apply here. Background art is generated per deck
  (illustration/AI-generated), not sourced.
- Loaded via the same Coil pattern as the card photo (local asset, disk cache disabled, explicit
  decode size), but resolved and cached **once per deck** rather than once per card — the win-pile
  grid must not decode the same background bitmap 30 times.

## Alternatives considered

**Drawn programmatically (a `Canvas` pattern/gradient), no bitmap at all.** Considered, since it
would sidestep asset generation and decode cost entirely. Rejected: "generate a Top Trumps
background" specifically asked for per-deck illustrated art (a road/engine motif for Motorcycles, a
social-feed motif for YouTubers, a plush-stitching motif for Squishies) — a procedural pattern can't
carry that per-deck identity as convincingly as a real image can.

**Per-card background instead of per-deck.** Rejected on memory grounds for the same reason the
existing win-pile thumbnail decode-size discipline exists: 30 additional full-size bitmaps decoded
per deck (one per card) is exactly the OOM risk class this feature already treats carefully
elsewhere. A per-deck shared bitmap is one decode, reused everywhere that deck's cards are drawn.

**Re-litigating the ban on `Modifier.shadow` mid-animation.** Not in scope. That ban is about a
`Modifier` that recomputes geometry every frame based on animatable elevation state — nothing here
touches it, and nothing about this decision weakens that constraint.

## Consequences

**Easier:** the card gets real per-deck visual identity beyond a flat accent colour, addressing the
"bland" complaint directly, using an asset-loading pattern this codebase already has proven (Coil,
disk cache disabled, explicit decode sizes) rather than inventing a new one.

**Harder:** the manifest schema grows again (a second optional asset reference alongside
`heroCardId`), and `DeckLoader` validation must resolve `backgroundImage` the same way it already
resolves `Card.image` and `heroCardId` — cosmetic-degrades-at-runtime, fails-CI, per TDD decision 7's
existing precedent, extended to cover this new field rather than inventing a third validation
posture.

**A caveat worth stating plainly:** this reopens a documented design decision rather than leaving it
untouched. Anyone reading the original PRD's "no texture or gloss bitmaps" line in isolation should
be pointed here rather than assuming it still holds — TDD decision 8 records that pointer.
