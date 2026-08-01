# Slice 4 — Discovery, lobby and invitations

> Part of the `top-trumps-core-game` feature. PRD: [`../PRD.md`](../PRD.md) · Technical design:
> [`../TDD.md`](../TDD.md)

**Delivers:** Two phones on the same Wi-Fi see each other by name, and one can invite the other into a connected session.

## What to build

Everything up to the moment a match would start — and then stop. No game in this slice; the success condition is a connected socket and a screen that says so.

First-run name entry, prefilled from the device name and remembered afterwards, plus a settings route to change it. A lobby that advertises this device and browses for others simultaneously, listing peers by display name and updating as people come and go. Tap a peer to invite; they see who invited them and accept or decline; the inviter becomes Player One. Pending invitations are cancellable and time out. A manual connect-by-address fallback for networks where discovery is blocked.

**The manual fallback is load-bearing, not a nicety.** mDNS is defeated by client isolation, some mesh systems and any active VPN. It ships in this slice, not later.

## Design notes

**Put the display name in the mDNS instance name** (`Russ·a1b2c3d4`) — see the [instance-name ADR](../../../adr/top-trumps-core-game-discovery-instance-name.md). `onServiceFound` carries no TXT records, so a name in TXT would force resolving *every* peer on every lobby refresh, which is exactly the concurrency the PRD flagged as fragile. With the name inline, the lobby renders with **zero resolves** and `resolveService` is called once per game, user-paced, when someone taps invite.

Sanitise names: strip `.` and control characters, truncate to 63 **bytes** in UTF-8, not characters.

Failure modes to build against from the start, each of which has bitten everyone ([TDD §8](../TDD.md#8-networking-on-the-device)):

- Read `serviceName` back from `onServiceRegistered`; never trust what you passed in. Auto-rename is real and its format is not contractual — **never self-filter by name**, filter on the UUID suffix.
- Normalise trailing dots on `serviceType` before comparing, or the lobby is silently and permanently empty.
- A fresh listener object per discovery and registration session, always. Reuse throws, and `stopServiceDiscovery` is async.
- `onServiceLost` is unreliable — goodbye packets are routinely dropped. **Story 8 will feel broken without a last-seen TTL** pruning peers not re-announced within ~30s. A failed resolve on tap is the most reliable liveness signal and should remove the peer immediately.
- Restart discovery on `ConnectivityManager` network changes; Wi-Fi roaming kills it with no callback.
- No `MulticastLock` is needed — that belongs to in-process stacks like JmDNS.

**Wi-Fi network binding lands here**, via injected `javax.net.SocketFactory` — see the [ADR](../../../adr/top-trumps-core-game-wifi-network-binding.md). A phone with Wi-Fi *and* cellular, where the Wi-Fi has no internet, routes local sockets over mobile data and fails. This is the most likely connection failure in an ordinary house and it is absent from the PRD.

The mutual-invitation rule needs three corollaries the PRD left implicit ([TDD §12](../TDD.md#12-lobby-invitation-resolution)): the losing socket is closed after sending `InviteCancel`; detection is **receive-side**, triggered whenever an inbound invite arrives while an outbound one is pending; and `DeclineReason.BUSY` exists so a third device gets an honest answer.

For the manual fallback, read the device's own IPv4 from `ConnectivityManager.getLinkProperties` — no `ACCESS_WIFI_STATE`, no location gate. Since home networks are overwhelmingly /24, consider displaying only the last octet as a two-digit code, with a full-address affordance behind it.

## Blocked by

- [Slice 0](slice-0-nsd-platform-spike.md) — the permission and LNP answers determine the onboarding flow.
- [Slice 1](slice-1-walking-skeleton.md) — needs the module structure and injected dispatchers.

## Acceptance criteria

- [x] First run asks for a display name, prefilled from the device name; it persists and is editable later
- [ ] Two physical devices on the same Wi-Fi list each other by name within a few seconds — **needs a second device**, see Delivered
- [x] The lobby list renders with **zero** `resolveService` calls
- [x] A device never lists itself, even after an auto-rename
- [ ] Closing the app on one device removes it from the other's list within ~30s — **needs a second device**
- [ ] Tap-to-invite shows an accept/decline prompt naming the inviter; declining is reported back — **needs a second device**
- [ ] Pending invitations are cancellable and time out — **needs a second device** to exercise the UI; the underlying state machine is unit-tested
- [ ] On accept, a TCP connection is established and the inviter is Player One — **needs a second device**
- [ ] Simultaneous mutual invitation resolves to exactly one session and one surviving socket — **needs two devices**; the tiebreak itself is unit-tested by name per the Testing section
- [ ] A third device inviting a busy player receives `BUSY` — **needs three devices**; unit-tested at the pure-logic layer
- [x] An empty lobby shows a hint that both devices must be on the same Wi-Fi
- [ ] Manual connect-by-address works when discovery is disabled — **needs a second device**; own-code/full-address display confirmed on one
- [ ] Connection succeeds on a device with mobile data enabled and a no-internet Wi-Fi network — **needs a second device and a no-internet Wi-Fi network**
- [x] Still no runtime permission prompts (or exactly the one slice 0 identified)

## Testing

`InvitationResolver` and `LobbyReducer` are **pure functions** in `:core:session`, tested directly with no double. The mandate is one *double*, not one *test*.

One test must exist by name: *given an outbound pending invite to peer X, an inbound invite from X arrives → resolve by UUID and transition straight to Accepted, skipping the prompt.* That is the case real users hit, and it is wrong on the first attempt.

Also test TTL-based peer pruning and self-filtering by UUID suffix as pure reducer cases, with `NsdManager` reduced to an event source feeding `DiscoveryEvent`s in.

Add **loopback TCP tests** in `:core:session` — a real `ServerSocket` on `127.0.0.1:0`, plain JVM, no emulator. These catch framing bugs (partial reads, two messages in one read, a message split across segments) that are invisible on a fast LAN and surface once in ten games in the field. The PRD put socket handling in the manual-only bucket; that was a miss.

`NsdManager` behaviour itself is manual, on two physical devices. **An emulator and a phone will never discover each other** — plan the hardware before starting.

## Delivered

Issue: [#5](https://github.com/rustycoopes/top-trumps-game/issues/5) · Branch: `slice-4-discovery-lobby` · Date: 2026-07-31

Built as designed, following the TDD/ADRs closely. `:core:session` gained the lobby wire protocol
(`LobbyMessage`: `Invite` / `InviteAccept` / `InviteDecline(reason)` / `InviteCancel`, symmetric
rather than the in-match `GuestToHost`/`HostToGuest` split since host/guest roles don't exist yet),
`LobbyReducer` (mDNS instance-name parsing tolerant of Android's auto-rename suffix, sanitisation
and UTF-8-byte truncation to the 63-byte budget, self-filtering by instance id, TTL pruning), and
`InvitationResolver` — a pure state machine (`Idle` / `OutboundPending` / `InboundPrompt` /
`Connected`) covering tap-invite, cancel, timeout, accept/decline, the receive-side mutual-invite
UUID tiebreak (the TDD's named test scenario, both directions), and `DeclineReason.BUSY`. A real
`TcpTransport`/`TcpListener` pair implements the byte-transport ADR's 4-byte length-prefix framing
over injected `SocketFactory`/`ServerSocketFactory`, with loopback JVM tests covering two-frames-
in-one-read and a frame split across separate writes. `:platform:net` wraps `NsdManager`
(zero-resolve discovery, one-resolve-per-invite, trailing-dot normalisation, a fresh listener per
session) and `ConnectivityManager` (Wi-Fi `Network` acquisition with `NET_CAPABILITY_INTERNET`
removed from the request — required for a no-internet Wi-Fi network to match at all — plus
socket factories bound to it, including a hand-written `ServerSocketFactory` bound to the Wi-Fi
link address). `:app` gained Navigation-Compose routes (name entry, lobby, settings, manual
connect, a bare "connected" screen — no match yet, per this slice's scope) wired through a new
`LobbyController` that glues the pure logic to real sockets/NSD/coroutines, and a
`DisplayNamePreferences` backed by Preferences DataStore with `Settings.Global.DEVICE_NAME`
prefill. The lobby listens on a fixed port (`LOBBY_PORT`) rather than an OS-assigned one
specifically so the manual-connect fallback's "two-digit code" is a real code (last IPv4 octet)
rather than requiring a port number too.

Both `code-review-master` and `code-quality-guardian` were run against the diff and independently
converged on the same three real bugs in `:app`'s wiring layer (the pure `:core:session` logic
came back clean from both): (1) `LobbyController` fired an effect's courtesy `Send` via
`scope.launch` immediately followed by a synchronous `CloseTransport`, racing the message against
the channel close and risking an uncaught `ClosedSendChannelException` on every cancel/decline/
BUSY/tiebreak-loser path — fixed by running one event's effects sequentially in a single
coroutine; (2) `Connected` had no way back to `Idle`, so the shipped "Leave" button created an
unbreakable navigation loop and leaked the socket — fixed by adding `InvitationEvent.Leave`; (3)
`LobbyController.start()` was called from two places on the same shared controller, racing to
bind the fixed `LOBBY_PORT` a second time on every revisit to the lobby — fixed by removing the
redundant call site. `code-quality-guardian` additionally caught the `NetworkRequest` missing
`removeCapability(NET_CAPABILITY_INTERNET)` (the ADR's entire no-internet-Wi-Fi scenario would
otherwise never match), that discovery wasn't pinned to the Wi-Fi `Network` on API 33+ per the
ADR, and a narrow leak where a dialled-but-not-yet-applied `TapInvite` transport could survive if
local state changed mid-dial. All fixed and re-verified; `./gradlew build` is green (40 tests
across `:core:session`, full lint, the `:core:*` dependency allowlist) both before and after.

**On-device verification is partial.** One physical device was available this session (Samsung
Galaxy SM-G998U, Android 13/API 33 — the same unit as the Slice 0 spike). Installed and exercised
live via `adb`: first-run name entry prefilled with the real device name and persisting through
Settings; the empty-lobby Wi-Fi hint; the manual-connect screen resolving and displaying a real
Wi-Fi IPv4 (`192.168.1.188`, code `188`) with the full-address reveal working; `dumpsys` confirming
only `INTERNET`/`ACCESS_NETWORK_STATE` are requested and both are pre-granted with no runtime
prompt; revisiting the lobby after Settings with no crash and no port-conflict error (confirming
fix #3 above actually holds under real navigation, not just in review); and solo mode
(`DeckPickerScreen` → deck list) unregressed. No crashes appeared in `logcat` at any point. What
this session could **not** verify — everything requiring a second device on the same Wi-Fi:
two-device discovery latency, the accept/decline round trip, the mutual-invite tiebreak on real
sockets, `BUSY`, TTL-based removal on app close, and the no-internet-Wi-Fi routing scenario. The
acceptance criteria above are checked accordingly; the unchecked ones are implementation-complete
and unit-tested at the pure-logic seam but need a second phone before they can be marked done.

**Diverged from the plan:**
- The lobby's `TcpListener` binds a fixed well-known port (`LOBBY_PORT = 45632`) rather than an
  OS-assigned one. Not specified either way by the TDD/WBS, but the manual-connect design note
  ("only the last octet as a two-digit code") only works if the port is already known to both
  apps — an OS-assigned ephemeral port would have forced the code to carry a port number too,
  defeating the point of a short code.
- `Hello`/`HelloAck`/`VersionMismatch` (the protocol-version handshake) are **not** part of this
  slice — TDD §5 lists them, but slice 5's own WBS text ("add the handshake gates") claims them
  explicitly, and slice 4's acceptance criteria never mention a version check. Deferred there as
  planned, not an oversight.
