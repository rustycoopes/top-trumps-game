# Slice 8 — Match history

> Part of the `top-trumps-core-game` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** Bragging rights — a head-to-head record against everyone you've played.

## What to build

An independent vertical slice that shares nothing with the game engine or the networking.

Completed matches are recorded locally: timestamp, deck, opponent display name, final score and the cards won. From those, three views — head-to-head record per opponent, overall record and win rate, and the cards that have won you the most rounds. A history list and a stats screen to show them.

Everything stays on the device. No analytics, no telemetry, no network calls beyond the peer socket.

## Design notes

**Room**, confined entirely to `:feature:history` — see the [ADR](../../../adr/top-trumps-core-game-match-history-room.md). Two tables: a `match` row per game, and a `match_card_win(match_id, card_id)` child table, because cards-won is one-to-many. That child table is what decides the choice: "most-won cards" is one `GROUP BY` in SQL versus loading every match and hand-folding a one-to-many relationship in any document store.

Set `exportSchema = true` and commit the schema JSONs. Skipping it is the standard Room regret.

**History is fed by a collector, not a dependency** ([TDD §11](../TDD.md#11-persistence)). `:core:rules` emits a `MatchSummary` at match end; the session decorates it with the opponent's display name and an injected clock; `:app` observes and records. There is no `HistoryRepository` parameter on `MatchSession`. The test of whether this is right: **deleting `:feature:history` should leave `:core:*` compiling.**

Opponent identity is display name only — there are no accounts, so two different people using the same name are indistinguishable. That is accepted, not a defect.

DAOs return `Flow<List<T>>`, which drops straight into `collectAsStateWithLifecycle`.

If display name and mute were not already moved to Preferences DataStore in an earlier slice, do it here — and mind the trap: **DataStore's first read is async and story 1 depends on it**. `runBlocking { dataStore.data.first() }` on the main thread is a real ANR source on cold start; render a loading state and transition when the flow emits. Exactly one `DataStore` instance per file, or it throws.

## Blocked by

- [Slice 5](slice-5-two-device-match.md) — real matches with real opponents are needed to record anything meaningful.

## Acceptance criteria

- [ ] A completed match is recorded with timestamp, deck, opponent name, final score and cards won
- [ ] Both solo and two-device matches are recorded
- [ ] The history list shows past matches, most recent first
- [ ] Head-to-head record per opponent name is correct
- [ ] Overall record and win rate are correct
- [ ] Most-won cards is correct and ordered
- [ ] Stats screens update reactively as matches complete
- [ ] An abandoned or quit match is **not** recorded as a completed one
- [ ] Room schemas are exported and committed
- [ ] Deleting `:feature:history` leaves `:core:*` compiling
- [ ] Data never leaves the device

## Testing

Aggregation logic is the part worth testing, and it is testable in Room's in-memory database on the JVM — no emulator needed for the queries themselves.

Cases: head-to-head across several opponents including one played many times; win rate with a mixture of results; most-won cards where one card appears in several matches; and the boundary case that a match abandoned mid-play produces no row at all.

Add one architectural assertion, since it is the whole premise of the slice: verify that `:core:*` has no dependency on `:feature:history` — the CI dependency check from slice 1 can carry this.

This module sits **entirely outside** the game's single test seam and shares nothing with it, which is exactly why it can be built last, or dropped, without disturbing anything else. The stats screens themselves are verified by hand.
