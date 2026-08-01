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

## Delivered

Issue: [#9](https://github.com/rustycoopes/top-trumps-game/issues/9) · Branch: `slice-8-match-history` · Date: 2026-08-01

Built as designed, with one semantic correction to "cards won" found in code review (below). Room
(`androidx.room` 2.8.0, KSP `2.2.0-2.0.2`) lives entirely in the new `:feature:history` module —
`MatchEntity`/`MatchCardWinEntity`, an internal `HistoryDao`, `HistoryDatabase`
(`exportSchema = true`, schema JSON committed under `feature/history/schemas/`), and
`MatchHistoryRepository` as the module's whole public surface: `recordMatch(...)` plus four
`Flow`-backed derived views (`matches`, `headToHead`, `overallRecord`, `mostWonCards`). Its own
`Outcome`/`CardWin`/`MatchRecord`/etc. vocabulary carries no `:core:rules` or `:core:session` type,
by design — `:app` is the only module that imports both.

**The collector, exactly as the ADR specified.** `:core:rules` declares `MatchSummary`/`MatchResult`
as pure values; `:core:session`'s `MatchView.Finished.toMatchSummary(mySeat)` builds one identically
for `HostMatchSession` and `GuestMatchSession` (both hold a `MatchView`, not a `PlayerView`, by the
time a match ends); each session decorates it with an injected `Clock` and the opponent's display
name into a new `MatchSession.completedMatch: StateFlow<RecordedMatch?>`, set via
`compareAndSet(null, ...)` the instant (and only the instant) that session's own `view` reaches
`Finished`. Neither a quit (`leave()`) nor a grace-expired abandonment ever touches `view`, so
`completedMatch` structurally cannot fire for either — proven in a new `CompletedMatchTest`, including
the case that matters (quitting/dropping *mid-match*, not before a single round is played).
`AppGraph.trackHistory` is `:app`'s glue: it races `completedMatch` against the match's own
`CoroutineScope` job completion (`select` over two `async`s) so a quit/abandoned match's collector
resolves to `null` and returns instead of parking forever, wraps the whole write in `runCatching` so
a `Room`/IO failure can never crash the app over the deliberately-droppable history slice, and
resolves the deck's display name via one `DeckLoader.load` call on `Dispatchers.IO` rather than the
full-catalog `listDecks()` walk on the single-worker dispatcher every live match mutates state on.
Two-device matches thread the peer's already-known display name (from NSD/lobby discovery) straight
into session construction via a new `MatchController.onMatchStarted`/`peerDisplayName` pair; solo
matches use a fixed `"AI"` opponent name.

**Code review (`code-review-master` + `code-quality-guardian`, run in parallel) caught a real
semantic bug**, fixed before merge: the first pass credited a seat's final *pile* as "cards won" —
which also holds every card **captured off the opponent** by winning with something else — so a
weak card racked up wins precisely by losing and getting swept up by whichever of your cards beat
it, running backwards from the PRD's "so that I learn the deck." Fixed at the source:
`MatchState` gained `cardsWonWith: Map<Seat, List<Card>>`, populated in `RulesEngine.applyAdvance`
with only the *round winner's own card* (never under `EACH_KEEPS_OWN`, where nobody's card won
anything), threaded through `PlayerView.Finished`/`MatchView.Finished`/`MatchSummary` to
`match_card_win`. Same pass also found and fixed: `match_card_win` denormalizes `deckId` alongside
`cardId` (card ids are only unique *within* a deck) so a future second deck can't silently merge
tallies; the DAO's three query-row types were collapsed into `MatchHistoryRepository`'s own public
data classes (Room projects a query straight into any matching data class, so the extra layer was
pure `.map{}` ceremony); `completedMatch`'s null-check-then-set became `compareAndSet`; `StatsScreen`
gained `verticalScroll` (two unbounded lists — one row per opponent, one per distinct card ever
won — in a plain non-scrolling `Column` meant most of it was unreachable); and a naming collision
(`:feature:history` had its own `MatchOutcome` clashing with `:core:rules`' pre-existing one) was
resolved by renaming the former to `Outcome`. One finding was **filed as a follow-up instead of
fixed here**: [#21](https://github.com/rustycoopes/top-trumps-game/issues/21) — manual-connect
matches record the typed IP address as the opponent name, a pre-existing slice-4/5
`LobbyController`/handshake defect that slice 8 only inherited the consequence of; fixing it means
touching the pre-match wire protocol, out of scope here.

**Two environment notes, not design decisions.** AGP 9's built-in-Kotlin support rejects the classic
KSP plugin's `kotlin.sourceSets` DSL usage by default (`:feature:history` is this repo's first KSP
consumer) — worked around with `android.disallowKotlinSourceSets=false` in `gradle.properties`,
documented there. `feature/history`'s Room DAO tests need a real `Context`, which Robolectric
supplies without an emulator (`MatchHistoryRepositoryTest`, `Room.inMemoryDatabaseBuilder`, JUnit4
only in this one module since Robolectric doesn't integrate with the rest of the repo's JUnit5) —
pinned to `@Config(sdk = [34])` rather than following `compileSdk` (37), since Robolectric's shadow
support trails the newest SDKs and nothing under test is `compileSdk`-sensitive.

All acceptance criteria verified at the JVM/Robolectric test seam (`CompletedMatchTest`,
`MatchHistoryRepositoryTest`) and by full-project build (`./gradlew build`, including lint and the
extended `checkCoreDependencyAllowlist`). The two history/stats screens themselves — reactive
updates, layout, navigation from the new Lobby "History" button — are implemented per the design but
**not manually verified on a physical device this session** (none was available), consistent with
recent slices.
