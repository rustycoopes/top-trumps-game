# Slice 5 — Two-device match

> Part of the `top-trumps-core-game` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** The actual product — two people playing a full game of Top Trumps on their own phones.

## What to build

Join the working game to the working lobby by swapping `LoopbackTransport` for `TcpTransport`. If slices 1–4 were built correctly, this slice is mostly plumbing and handshake, because the session already speaks bytes and neither side ever had a shortcut to shared memory.

Player One picks a deck and starts; Player Two sees the choice while waiting. The host deals fifteen cards each and pushes each player's own view. The guest sends nothing but intents. Both screens show the same score, the same round number, and the same reveal at the same moment.

Add the handshake gates, and note the PRD's single word "handshake" is really **two** gates: protocol version is checked before deck selection is even possible, and the deck hash is checked after Player One picks but before any card is dealt.

## Design notes

Message inventory and directions: [TDD §5](../TDD.md#5-wire-protocol). The guest is a thin client — enforce it with two **separate** sealed hierarchies, `GuestToHost` and `HostToGuest`, so it is a compile error for either side to construct the other's messages.

The PRD's "metric chosen / round resolved / tiebreak required / full reveal / advance round" collapses to a **single `View` message**, because a resolved round's projected view already carries all five UX moments. `MatchStart`, `Abandoned`, `PeerDisconnected` and `RematchOffer` stay discrete, because a client needs unambiguous one-shot triggers for animations and navigation that cannot be reliably inferred by diffing views.

Framing is a 4-byte big-endian length prefix read via `readFully`, serialisation is kotlinx JSON, versioning is a single `Int` with strict equality and a hard refusal — see the [byte transport ADR](../../../adr/top-trumps-core-game-byte-transport.md).

**Socket role is not match role.** Whoever dialled the TCP connection is not necessarily the host — the inviter is host regardless of which device called `connect()`. Name this explicitly in the transport code so nobody wires it backwards.

Socket hygiene ([TDD §8](../TDD.md#8-networking-on-the-device)): `accept()` and `read()` do not respond to coroutine cancellation, so close from outside via `job.invokeOnCompletion`; use a **single writer coroutine** consuming a channel, because two writers sharing an `OutputStream` interleave frames; set `TCP_NODELAY`.

Confine all state mutation to a single dispatcher and always use `MutableStateFlow.update {}` — host state mutated from both the socket-read coroutine and the UI path is otherwise a data race.

[TDD Open Question 4](../TDD.md#open-questions) is still unanswered and lands here: does the host stay host across a rematch, or does Player One rotate?

## Blocked by

- [Slice 2](slice-2-complete-solo-match.md) — needs a complete game.
- [Slice 4](slice-4-discovery-lobby.md) — needs a connected socket.

## Acceptance criteria

- [ ] Player One picks a deck; Player Two sees the choice while waiting — **needs two physical devices**, see Delivered
- [ ] Fifteen cards are dealt to each device from one shuffle — **needs two physical devices**, see Delivered
- [ ] A complete match plays to a result on two physical phones — **needs two physical devices**, see Delivered
- [ ] Both screens agree on score, round number and every reveal — **needs two physical devices**, see Delivered
- [ ] Tiebreaks work across the wire, with the same player choosing again — **needs two physical devices**, see Delivered
- [x] Mismatched protocol version is refused before deck selection, with a clear message
- [x] Mismatched deck id or content hash is refused before any card is dealt
- [x] The guest can send nothing but intents and lifecycle messages — enforced by types
- [x] A player's unplayed stats are never present in any frame sent to the other device
- [x] Solo mode still works, unchanged, through the same session interface

## Testing

The seam is unchanged, and this is where the byte-carrying `Transport` decision pays off: the seam tests written in slices 1–2 already exercise the real codec, so this slice adds contract tests rather than rewriting anything.

New at the seam: handshake refusal on version, deck id and hash mismatch; that dealing is a partition (15 and 15, no duplicates, no omissions) and reproducible from a seed; and a **frame-level leak assertion** — capture every frame the host sends to the guest during a full match and assert that no unplayed stat value appears in any of them. That is a stronger claim than the view-level assertion from slice 1, and it is the one that matters once real bytes are moving.

Extend the loopback TCP tests from slice 4 to cover a full match over a real socket on `127.0.0.1`, including serialisation round-trips over a genuine stream.

Two physical devices remain mandatory for final verification.

## Delivered

Issue: [#6](https://github.com/rustycoopes/top-trumps-game/issues/6) · Branch: `slice-5-two-device-match` · Date: 2026-08-01

Built as designed. `:core:session` gained the `GuestToHost`/`HostToGuest` sealed hierarchies from
TDD §5 — `GuestToHost` is exactly `Hello`, `DeckMismatch`, `ChooseMetric`, `AdvanceRound` and
`Leave`, so it is a compile error for the guest to construct anything else, which is what makes
the "guest can send nothing but intents and lifecycle messages" acceptance criterion a type-system
guarantee rather than a convention. `Intent`/`View` from the TDD's message table collapsed into
`GuestToHost.ChooseMetric`/`AdvanceRound` and `HostToGuest.View` directly, dropping the `seq`/
`roundNumber`/`cause`/`resync` fields the table lists — none of them do anything until slice 6's
resume/dedup logic exists to read them, so they're deferred rather than carried as dead fields.
`HostHandshake`/`GuestHandshake` (`MatchHandshake.kt`) implement the two gates: `awaitHello`/`run`
check `PROTOCOL_VERSION` before deck selection is even reachable, and `chooseDeck` sends
`DeckChosen` then gives the guest a short window (default 500ms, injectable) to reply
`DeckMismatch` before the caller is told it's safe to deal — there is no positive acknowledgement
in the wire protocol, so a same-Wi-Fi timing window is the deliberate, documented trade-off in
place of a guaranteed race-free ack. `DeckLoader.manifestHash` (SHA-256 of `manifest.json`'s raw
bytes) is what each side actually compares. `HostMatchSession`/`GuestMatchSession` (unchanged
constructors, so `AppGraph.startSoloMatch` didn't need to change at all) now send `MatchStart`
with the guest's real dealt hand before the first `View`, and both solo and two-device play go
through the identical wire path. `:app` gained `MatchController` (the handshake orchestration,
parallel to `LobbyController`), `TwoDeviceMatchScreen` (renders per handshake/match phase), and a
`localSeat` parameter on `MatchScreen` — it previously hardcoded the local player as `"HOST"`,
which was only ever true in solo mode.

**Product decisions made this slice:** TDD Open Question 4 (rematch host continuity) is resolved
— the host stays host, reusing the transport with a fresh session token per rematch — but building
the actual two-device `RematchOffer`/`RematchResponse` flow is out of scope here (no acceptance
criterion calls for it) and is filed as a follow-up issue instead.

**Both `code-review-master` and `code-quality-guardian` were run against the diff and found real
issues, all fixed before merging:**

- **Critical:** `LobbyController` never stopped reading a transport once it resolved to
  `InvitationState.Connected` — its `attachMessageListener` collector and `MatchController`'s
  handshake/session collectors would then race for every frame on the same `Channel`-backed
  `Transport.incoming` (not a broadcast; each frame goes to whichever collector wins), so the
  handshake could hang forever if the wrong side won. Fixed by tracking one listener `Job` per
  lobby transport and cancelling it the instant that transport becomes `Connected` or is closed.
- **High:** a peer dropping mid-handshake (backgrounded, crashed, walked out of Wi-Fi range) threw
  an uncaught `NoSuchElementException` from `Flow.first()` on the surviving device — a real crash
  triggered purely by the *other* device leaving. Fixed with a catch-all in `MatchController` that
  routes anything but cancellation into a new `MatchPhase.ConnectionLost` dead end instead.
- **High:** frame decoding in `HostMatchSession`/`GuestMatchSession`'s ongoing collectors had no
  guard — a malformed frame (corruption, or any device on the same Wi-Fi connecting to the fixed
  lobby port and sending garbage) would throw uncaught. Fixed with the same drop-and-continue
  `runCatching` pattern `LobbyController` already used for lobby messages.
- **Medium:** `MatchController.start()`'s doc comment claimed idempotency it didn't implement, and
  `pickDeck()` had no re-entrancy guard at all — both were real instances of the same single-
  consumer-channel race as the critical finding above, just self-inflicted via a double
  recomposition or double-tap. Fixed with explicit guards on both.
- **Low/quality:** the new "dealing is a partition" test only checked the guest's hand in
  isolation (size, no internal duplicates) — never that it was actually disjoint from the host's
  hand or that the two together covered the whole deck, so it didn't verify a partition at all.
  Fixed by strengthening `:core:rules`' `RulesEngineTest` (which has access to both hands) with
  the missing disjoint/covers-the-deck assertions. Also fixed: dead nav-route arguments on
  `Connected`, a duplicated deck-load-or-throw block (extracted to `DeckLoader.loadOrThrow`),
  hardcoded `"HOST"`/`"GUEST"` literals where `Role` was already in scope, a duplicated
  `Card`/`CardFace` → `RemoteCardFace` mapping (extracted to a shared `toRemoteStats()`), and
  genuinely dead code (`WireMatchConfig.toDomain()`, unused and unsafe against an unrecognised
  wire value — removed rather than hardened, since nothing calls it).

Also added, per the WBS's own testing note that this slice's tests had missed: a real-socket
(`127.0.0.1`, not loopback-in-memory) test in `TcpTransportTest.kt` driving the full handshake and
a complete match to a result, which is the kind of test that would have caught the critical
`LobbyController` finding above had it existed sooner.

`./gradlew build` is green: `:core:rules`, `:core:decks`, `:core:session` (including the new
`MatchHandshakeTest` and the strengthened `MatchSessionTest`/`TcpTransportTest`), `:core:ai`, and
`:app:assembleDebug` all pass/succeed.

**No on-device verification was possible this session** — no physical Android device or emulator
was available in this environment, and (per `CLAUDE.md`) an emulator and a phone can never
discover each other over NSD regardless. Every acceptance criterion that inherently requires
watching two real phones talk to each other (deck-pick visibility, the actual fifteen-card deal,
a played-out match, cross-device score/round/reveal agreement, tiebreaks over a real socket) is
left unchecked above pending that manual pass; everything checkable by type system, unit test, or
code inspection alone (both handshake gates, the guest's type-enforced thin-client property, the
frame-level redaction guarantee, and solo mode's continued behaviour) is checked off and covered
by the test suite described above.
