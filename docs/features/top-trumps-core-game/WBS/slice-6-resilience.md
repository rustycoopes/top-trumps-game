# Slice 6 — Resilience

> Part of the `top-trumps-core-game` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** A match survives a notification, an app switch, or a Wi-Fi hiccup — and ends cleanly when it genuinely can't.

## What to build

The work that makes a fifteen-round game completable in a real house rather than in a lab.

A foreground service runs for the duration of a match. An application-level heartbeat detects the peer going quiet. When it does, both devices show a named countdown, and the absent player's device reconnects and resumes exactly where it left off. Past the window, the match is abandoned with an explanation. A deliberate quit is distinguishable from a crash.

**This slice replaces the PRD's stated approach**, which was `FLAG_KEEP_SCREEN_ON` plus a grace window. Keep-screen-on is retained but demoted to what it actually is — a display-timeout fix, scoped to the match screen — because it addresses the *least* likely interruption in a game the player is staring at.

## Design notes

Why a foreground service is required: [FGS ADR](../../../adr/top-trumps-core-game-foreground-service.md). The dominant interruption is backgrounding, which this app invites — the content pitch is that players want to look the machines up. A backgrounded process is frozen (within seconds on many OEM ROMs), its coroutines stop, its heartbeat stops, and recent Android versions reset sockets held by frozen UIDs.

Type is `connectedDevice`, and it costs **zero runtime prompts**: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` and `CHANGE_NETWORK_STATE` are all normal permissions, and the service runs whether or not `POST_NOTIFICATIONS` is granted — denial only hides the notification. Start it from the foreground at match start, call `startForeground()` within five seconds, stop it the instant the match ends. Not in the lobby, not in solo mode.

`MatchSession` moves to an **application-scoped holder** whose lifetime mirrors the service, exposed by a thin Activity-scoped ViewModel. Portrait-only does not prevent configuration changes, and `android:configChanges` is not the fix.

Half-open detection needs an application heartbeat, because every platform mechanism fails: TCP keepalive defaults to two hours with no way to tune it on Android, retransmission timeout is 13–30 minutes, and `Socket.isConnected()` returns `true` forever on a half-open socket. Design: **2s ping piggybacked on any traffic, 3 misses ≈ 6s to declare the peer unreachable**, then the 60s countdown.

Reconnection is **full-state resync with sequence-number deduplication** — see the [ADR](../../../adr/top-trumps-core-game-reconnect-resync.md). The client keeps at most one intent in flight, which reduces the hard case to a single integer comparison: a resumed intent with `seq <= lastAccepted` was already applied and must not be re-applied; `seq == last + 1` never arrived and is applied normally. Because `apply()` is synchronous and total, no transition can be half-applied, so those are the only two cases.

To make reconnect fast, the host's port and session token ride the invite payload so the guest re-dials **directly with no NSD re-resolve** — ~200ms rather than ~3s, and it takes the flaky resolve out of the recovery path.

Deliberate quit sends `Leave`, flushes, then `shutdownOutput()` before closing, so the peer's read returns `-1` cleanly even if the frame races.

**Host-drop and guest-drop are not symmetric.** A guest drop is genuinely recoverable; a host drop is only recoverable if the host process survived. The countdown copy must not imply otherwise — the PRD's stories 58–60 read as though they are the same.

## Blocked by

- [Slice 5](slice-5-two-device-match.md)

## Acceptance criteria

- [ ] A foreground service runs for match duration only, with a notification naming the opponent and round
- [ ] No new runtime permission prompt appears; denying `POST_NOTIFICATIONS` does not stop the service
- [ ] Backgrounding the app mid-match for 30 seconds and returning leaves the match unaffected
- [ ] Turning Wi-Fi off on one device is detected by the other within ~6 seconds
- [ ] Both devices show a countdown naming the absent player
- [ ] Restoring Wi-Fi within the window resumes at the correct round with scores intact
- [ ] A choice sent but unacknowledged before the drop is applied **exactly once** after resume
- [ ] Exceeding the window abandons the match with an explanation on both devices
- [ ] A deliberate quit shows "X left the game", not a countdown
- [ ] Reconnect uses direct re-dial, falling back to NSD only if that fails
- [ ] Session tokens are regenerated per rematch
- [ ] Keep-screen-on is scoped to the match screen and released on leaving it

## Testing

Everything except the platform behaviour is testable at the existing seam, in **virtual time**.

This is where slice 1's dispatcher-injection discipline pays off. `runTest` collapses the 60-second window to microseconds — but only if production `delay` runs on the injected test dispatcher. A stray `withContext(Dispatchers.IO)` makes the test really take sixty seconds and then fail.

The countdown must be `delay`-driven, not wall-clock: `System.currentTimeMillis()` does not move under virtual time, so a "record start, compare to now" implementation is untestable. A `flow { repeat(60) { emit(60 - it); delay(1.seconds) } }` is both virtual-time friendly and exactly what the countdown UI needs — same code, one design.

The in-memory `Transport` grows `dropConnection()` and `reconnect(token)`. That is still one double and legitimate, but **budget it at ~100 lines** — exceeding that signals an under-specified session rather than a double needing more features. Do not simulate reordering or partial frames: TCP guarantees ordering, and framing is covered by the loopback tests.

Cases: resume inside the window restores the correct round; resume outside is rejected; a pending intent with a stale seq is not re-applied; a pending intent with `last + 1` is applied; deliberate quit is distinguishable from a drop; heartbeat timeout triggers the countdown.

Foreground-service behaviour, OEM background-kill and real Wi-Fi loss are manual, on two physical devices. **Test on the oldest phone in the family** — OEM freeze timing varies enormously and AOSP timings will mislead.
