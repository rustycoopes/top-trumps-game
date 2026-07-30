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

- [ ] First run asks for a display name, prefilled from the device name; it persists and is editable later
- [ ] Two physical devices on the same Wi-Fi list each other by name within a few seconds
- [ ] The lobby list renders with **zero** `resolveService` calls
- [ ] A device never lists itself, even after an auto-rename
- [ ] Closing the app on one device removes it from the other's list within ~30s
- [ ] Tap-to-invite shows an accept/decline prompt naming the inviter; declining is reported back
- [ ] Pending invitations are cancellable and time out
- [ ] On accept, a TCP connection is established and the inviter is Player One
- [ ] Simultaneous mutual invitation resolves to exactly one session and one surviving socket
- [ ] A third device inviting a busy player receives `BUSY`
- [ ] An empty lobby shows a hint that both devices must be on the same Wi-Fi
- [ ] Manual connect-by-address works when discovery is disabled
- [ ] Connection succeeds on a device with mobile data enabled and a no-internet Wi-Fi network
- [ ] Still no runtime permission prompts (or exactly the one slice 0 identified)

## Testing

`InvitationResolver` and `LobbyReducer` are **pure functions** in `:core:session`, tested directly with no double. The mandate is one *double*, not one *test*.

One test must exist by name: *given an outbound pending invite to peer X, an inbound invite from X arrives → resolve by UUID and transition straight to Accepted, skipping the prompt.* That is the case real users hit, and it is wrong on the first attempt.

Also test TTL-based peer pruning and self-filtering by UUID suffix as pure reducer cases, with `NsdManager` reduced to an event source feeding `DiscoveryEvent`s in.

Add **loopback TCP tests** in `:core:session` — a real `ServerSocket` on `127.0.0.1:0`, plain JVM, no emulator. These catch framing bugs (partial reads, two messages in one read, a message split across segments) that are invisible on a fast LAN and surface once in ten games in the field. The PRD put socket handling in the manual-only bucket; that was a miss.

`NsdManager` behaviour itself is manual, on two physical devices. **An emulator and a phone will never discover each other** — plan the hardware before starting.
