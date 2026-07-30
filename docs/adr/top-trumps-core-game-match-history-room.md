# Room for match history

**Status:** Proposed
**Date:** 2026-07-30
**Feature:** [`top-trumps-core-game`](../features/top-trumps-core-game/TDD.md)

## Context

The PRD persists, per completed match: timestamp, deck, opponent display name, final score, and the cards won. From that it derives three views — head-to-head record per opponent, overall record and win rate, and most-won cards.

The volume is small: a family might accumulate a few hundred matches over the app's life. That smallness is a genuine argument for the simplest possible storage, so the choice is not automatic.

The PRD also treats match history as an independent vertical slice that shares nothing with the engine or the networking, which means whatever is chosen must stay confined to `:feature:history`.

## Decision

**Room**, confined entirely to `:feature:history`, with `exportSchema = true` and schema JSONs committed.

Two tables: a `match` row per completed game, and a `match_card_win(match_id, card_id)` child table, because "cards won" is one-to-many.

DAOs return `Flow<List<T>>`, which drops straight into `collectAsStateWithLifecycle`.

History is fed by a **collector, not a dependency**: `:core:rules` emits a `MatchSummary` value at match end, `MatchSession` decorates it with the opponent's display name and an injected clock, and `:app` observes that stream and records it. There is no `HistoryRepository` parameter on `MatchSession`. Deleting `:feature:history` leaves `:core:*` compiling.

Display name and mute toggle go in **Preferences DataStore**, not Room — different lifetime, different shape, and pulling Room into the first-run flow would be silly.

## Alternatives considered

**A plain JSON file.** The obvious "it's only a few hundred rows" answer, and the least machinery. Rejected because all three derived views become full-table loads plus hand-written aggregation, and because atomic writes (write-temp-then-rename), concurrent access and schema migration all become things we implement ourselves. It is simplest on day one and worst by slice 7.

**Proto DataStore.** Same aggregation problem as JSON but with schema evolution handled, and genuinely about 100 lines. It is the strongest alternative and would be defensible. Rejected on one specific requirement: **most-won cards**. That is `SELECT card_id, COUNT(*) FROM match_card_win GROUP BY card_id ORDER BY 2 DESC` — one query in Room, versus loading every match into memory and hand-folding a one-to-many relationship, which is more code than Room's entire setup and gets slower with every match played. DataStore also rewrites the whole file on every write.

**SQLDelight.** Comparable capability with better multiplatform support and compile-time-checked SQL. Rejected only because there is no multiplatform requirement here and Room is the better-trodden path on a pure-Android project.

## Consequences

**Easier:** the three derived views are three SQL queries rather than three hand-written folds; reactive DAOs give the stats screens live updates for free; migrations are a solved problem when a future deck or field is added.

**Harder:** KSP in the build, and Room needs `Context`, so `:feature:history` is necessarily an Android module and is verified with Room's in-memory builder rather than at the pure-JVM seam. That is acceptable precisely because it sits entirely outside the game's single test seam and shares nothing with it.

**Two traps this makes live:**
- Skipping `exportSchema` is the standard Room regret — do it now, not after the first migration is needed.
- **DataStore's first read is async, and story 1 depends on it** ("ask for a name on first run" requires knowing whether a name exists before choosing a screen). The tempting `runBlocking { dataStore.data.first() }` on the main thread is a real ANR source on cold start. Render a loading state and transition when the flow emits. Also: exactly one `DataStore` instance per file, or it throws.
