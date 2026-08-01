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

- [ ] A foreground service runs for match duration only, with a notification naming the opponent and round — **needs two physical devices**, see Delivered
- [ ] No new runtime permission prompt appears; denying `POST_NOTIFICATIONS` does not stop the service — **needs two physical devices**, see Delivered
- [ ] Backgrounding the app mid-match for 30 seconds and returning leaves the match unaffected — **needs two physical devices**, see Delivered
- [ ] Turning Wi-Fi off on one device is detected by the other within ~6 seconds — **needs two physical devices**, see Delivered
- [ ] Both devices show a countdown naming the absent player — **needs two physical devices**, see Delivered
- [ ] Restoring Wi-Fi within the window resumes at the correct round with scores intact — **needs two physical devices**, see Delivered
- [x] A choice sent but unacknowledged before the drop is applied **exactly once** after resume
- [ ] Exceeding the window abandons the match with an explanation on both devices — **needs two physical devices**, see Delivered
- [ ] A deliberate quit shows "X left the game", not a countdown — **needs two physical devices**, see Delivered
- [ ] Reconnect uses direct re-dial, falling back to NSD only if that fails — **needs two physical devices**, see Delivered
- [x] Session tokens are regenerated per rematch
- [ ] Keep-screen-on is scoped to the match screen and released on leaving it — **needs a physical device**, see Delivered

## Testing

Everything except the platform behaviour is testable at the existing seam, in **virtual time**.

This is where slice 1's dispatcher-injection discipline pays off. `runTest` collapses the 60-second window to microseconds — but only if production `delay` runs on the injected test dispatcher. A stray `withContext(Dispatchers.IO)` makes the test really take sixty seconds and then fail.

The countdown must be `delay`-driven, not wall-clock: `System.currentTimeMillis()` does not move under virtual time, so a "record start, compare to now" implementation is untestable. A `flow { repeat(60) { emit(60 - it); delay(1.seconds) } }` is both virtual-time friendly and exactly what the countdown UI needs — same code, one design.

The in-memory `Transport` grows `dropConnection()` and `reconnect(token)`. That is still one double and legitimate, but **budget it at ~100 lines** — exceeding that signals an under-specified session rather than a double needing more features. Do not simulate reordering or partial frames: TCP guarantees ordering, and framing is covered by the loopback tests.

Cases: resume inside the window restores the correct round; resume outside is rejected; a pending intent with a stale seq is not re-applied; a pending intent with `last + 1` is applied; deliberate quit is distinguishable from a drop; heartbeat timeout triggers the countdown.

Foreground-service behaviour, OEM background-kill and real Wi-Fi loss are manual, on two physical devices. **Test on the oldest phone in the family** — OEM freeze timing varies enormously and AOSP timings will mislead.

## Delivered

Issue: [#7](https://github.com/rustycoopes/top-trumps-game/issues/7) · Branch: `slice-6-resilience` · Date: 2026-08-01

Built as designed, with one deliberate scope call flagged below. `:core:session` gained a shared
`ConnectionWatchdog` (used by both `HostMatchSession` and `GuestMatchSession`) implementing the
2s-heartbeat/3-miss/60s-countdown design exactly, plus the wire messages the ADR specifies —
`Heartbeat`, `Resume`/`ResumeAck`/`ResumeRejected`, a `seq: Long` on `ChooseMetric`/`AdvanceRound`,
and `AbandonReason.{GRACE_EXPIRED, PEER_QUIT}` — behind `PROTOCOL_VERSION` 2. The resume/dedup
rule (`seq <= last` → already applied, don't re-apply; `seq == last + 1` → apply) is a direct
translation of the ADR's two-case analysis. `GuestMatchSession` gained the at-most-one-intent
invariant (`hasPendingIntent`, defensively enforced in `submit()` itself, not just trusted to the
UI) and `reconnect(newTransport)`; `HostMatchSession` gained `acceptResume(newTransport, resume)`.
`TcpTransport` half-closes before closing, and gained `remoteHost` — since the guest never dials
the original connection (the host always does, even in the mutual-tap tiebreak case), this is the
*only* way a guest ever learns an address to redial, which is what let direct-redial ship with
**no new wire field**: `LOBBY_PORT` was already a shared constant, so only the host's IP needed a
source, and the accepted socket already has it. `:app` gained `MatchForegroundService`
(`connectedDevice` type, started/stopped around a two-device match's lifetime only),
`TopTrumpsApplication` (hosts `AppGraph` at process scope), a guest-only reconnect coordinator in
`MatchController` (direct redial via `remoteHost`, NSD fallback via the same `NsdLobbyDiscovery`
instance `LobbyController` already keeps warm), and `LobbyController` inbound-frame sniffing that
routes a reconnecting guest's `Resume` to the live `HostMatchSession` instead of treating it as a
stray lobby invite. `MatchScreen` scopes keep-screen-on to itself via `DisposableEffect` on
`LocalView`, disables its buttons while `hasPendingIntent` is true, and gained a "Leave match"
affordance mid-play (there wasn't one before this slice — no acceptance criterion needed it until
"X left the game" needed something to trigger it from).

**Scope call:** the ADR's "thin Activity-scoped ViewModel" was not built literally. Instead,
`AppGraph` (already the graph's single hand-rolled composition root) now holds the process's one
`LobbyController` and the active `MatchController` directly, and `TopTrumpsApplication` hosts
`AppGraph` itself — Compose reads both straight off `AppGraph`'s `StateFlow`s rather than through
a `ViewModel` layer. Functionally this satisfies the ADR's actual goal (a match's socket and state
outlive the Activity instance that happened to be showing it), reuses the codebase's existing
hand-rolled-controller idiom instead of introducing a pattern used nowhere else in the app, and
needed one more fix than originally planned to be true end-to-end — see the first review finding
below.

**Both `code-review-master` and `code-quality-guardian` were run against the diff, in parallel,
and both found real issues — all fixed before merging:**

- **Critical (code-review-master):** the first pass promoted `AppGraph` to `Application` scope
  but left `LobbyController` and the connected screen's `MatchController` Composable-`remember`-scoped,
  same as before this slice. Since nothing in this app locks orientation, a plain rotation mid-match
  triggered a full Activity recreation that silently and permanently killed the match — `DisposableEffect.onDispose`
  fired `MatchController.close()` (a hard `scope.cancel()`, no courtesy message), a *second*
  `LobbyController` was constructed and raced the old one (not yet actually torn down, since
  cancellation is asynchronous) to rebind the fixed `LOBBY_PORT`, and the new composition never
  looked at any surviving state to reattach — the user was just dumped back in the Lobby with no
  explanation, and if the *host* was the one who rotated, the guest's every subsequent resume
  attempt got silently dropped instead of even a `ResumeRejected`. Fixed properly rather than
  patched: `AppGraph.lobbyController(displayName)` now hands back the *same* `LobbyController`
  instance across a recreation (with `LobbyController.start()` made idempotent to match), the
  connected screen reuses `appGraph.activeMatch.value` instead of always constructing a fresh
  `MatchController` over the same live transport, all teardown moved out of `DisposableEffect.onDispose`
  and into the explicit "Leave" action (disposal fires on a mere recreation; a deliberate user
  action does not), and `Loading`'s post-name-resolved navigation now checks `activeMatch` and
  routes straight back to `Connected` instead of always landing on `Lobby`.
- **High (code-review-master):** the old `Transport` was never closed on either side of a
  reconnect/resume swap, and a *rejected* resume's brand-new transport was never closed either —
  each leaked a blocked reader coroutine, a parked writer coroutine, and a file descriptor per
  drop, and since the resume path accepts unauthenticated inbound connections at a fixed port, a
  repeatedly-rejected resume was a real, remotely-triggerable resource-exhaustion angle on the
  host. Fixed: the superseded transport is closed (hygiene close, not graceful — it's presumed
  already dead) the moment a swap or reconnect completes, and both `acceptResume` rejection
  branches now close the transport they just replied to.
- **High (code-review-master):** `TcpTransport.close()`'s new half-close didn't actually guarantee
  a just-queued courtesy frame (`Leave`/`Abandoned(PEER_QUIT)`) reached the wire before the socket
  died — `send()` only enqueues onto the writer coroutine's channel and returns immediately, so a
  `leave()` calling `send()` then `close()` back-to-back could tear the socket down before the
  writer coroutine got scheduled, silently dropping the deliberate-quit message (swallowed as an
  `IOException`) and leaving the peer to fall through to the ordinary heartbeat-timeout path
  instead of an immediate "X left the game". Fixed with a new `Transport.closeGracefully()`
  (default `= close()`; `TcpTransport` overrides it to join the writer job before closing), used by
  both sessions' `leave()`.
- **Medium (code-review-master):** `GuestMatchSession.pendingIntent` was read-and-set synchronously
  on the caller's thread inside `submit()` (Compose's main thread) while also being written from
  the session's own confined background dispatcher inside `listen()` — an unguarded cross-thread
  race on the exact field the whole dedup scheme's at-most-one-intent invariant depends on. Fixed
  by routing `submit()`'s check-and-set through `scope.launch` like every other state mutation in
  this class already is, rather than adding a lock.
- **High (code-quality-guardian):** the new "Leave match" button in `MatchScreen` called
  `session.leave()` itself and then invoked `onLeave`, which *also* called `session.leave()` —
  double-firing a non-re-entrant method, whose second call sent on an already-`close()`d channel
  and threw an uncaught `ClosedSendChannelException` on a real two-device quit. Fixed by making
  `onLeave` (now `leaveMatch` in `MainActivity`) pure cleanup-and-navigate, with the courtesy
  `session.leave()` call living only at the one call site that actually decides to interrupt a live
  match.
- **Medium (code-quality-guardian):** a resume attempt with no live host session to answer it
  (already left, or never was the host) was just silently socket-closed rather than sent
  `ResumeRejected("UNKNOWN_SESSION")` as `AppGraph.clearActiveMatch`'s own doc comment claimed —
  the reconnecting guest would just time out its own countdown instead of learning immediately.
  Fixed by sending the rejection before closing.
- **Medium (code-quality-guardian):** the `PeerUnreachable` status copy read identically for host
  and guest, re-introducing the symmetry the reconnect-resync ADR explicitly warns the copy must
  not imply — a host isn't "trying to reconnect," it's waiting for one. Fixed with role-differentiated copy.
- **Minor (both):** several new KDoc comments were single unwrapped lines 180–300 characters long,
  well past this file's established width — reflowed. Added a `ConnectionResilienceTest` case for
  `hasPendingIntent`'s own transition/no-second-frame contract, which nothing exercised before the
  review.

`./gradlew build` is green end to end: all `:core:*` unit tests (63 in `:core:session` including
the 9-case `ConnectionResilienceTest`), `:core:ai`, `:platform:net`, lint across every module
(zero issues), the `:core:*` android-import allowlist check, and both `:app:assembleDebug`/`assembleRelease`.

**No on-device verification was possible this session** — no physical Android device or emulator
was available, and (per `CLAUDE.md`) an emulator and a phone can never discover each other over
NSD regardless, so a two-emulator substitute wasn't an option either. Every acceptance criterion
above left unchecked inherently needs two real phones on the same Wi-Fi to observe (the foreground
service notification and permission behaviour, real backgrounding, real Wi-Fi toggling and its
~6s detection, the on-screen countdown/abandon/left copy, direct-redial-vs-NSD-fallback against a
real network, and keep-screen-on's actual display effect) — implemented and internally consistent
with the design, but not watched happening. The two criteria checkable by automated test alone
(exactly-once resume application, and session tokens regenerating per rematch) are checked off and
covered by `ConnectionResilienceTest`/`MatchHandshakeTest`. **Test on two physical devices — oldest
phone in the family first — before relying on this in a real house.**
