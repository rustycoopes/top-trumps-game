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

- [ ] Player One picks a deck; Player Two sees the choice while waiting
- [ ] Fifteen cards are dealt to each device from one shuffle
- [ ] A complete match plays to a result on two physical phones
- [ ] Both screens agree on score, round number and every reveal
- [ ] Tiebreaks work across the wire, with the same player choosing again
- [ ] Mismatched protocol version is refused before deck selection, with a clear message
- [ ] Mismatched deck id or content hash is refused before any card is dealt
- [ ] The guest can send nothing but intents and lifecycle messages — enforced by types
- [ ] A player's unplayed stats are never present in any frame sent to the other device
- [ ] Solo mode still works, unchanged, through the same session interface

## Testing

The seam is unchanged, and this is where the byte-carrying `Transport` decision pays off: the seam tests written in slices 1–2 already exercise the real codec, so this slice adds contract tests rather than rewriting anything.

New at the seam: handshake refusal on version, deck id and hash mismatch; that dealing is a partition (15 and 15, no duplicates, no omissions) and reproducible from a seed; and a **frame-level leak assertion** — capture every frame the host sends to the guest during a full match and assert that no unplayed stat value appears in any of them. That is a stronger claim than the view-level assertion from slice 1, and it is the one that matters once real bytes are moving.

Extend the loopback TCP tests from slice 4 to cover a full match over a real socket on `127.0.0.1`, including serialisation round-trips over a genuine stream.

Two physical devices remain mandatory for final verification.
