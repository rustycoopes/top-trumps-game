# Display name goes in the mDNS instance name

**Status:** Proposed
**Date:** 2026-07-30
**Feature:** [`top-trumps-core-game`](../features/top-trumps-core-game/TDD.md)

## Context

The PRD correctly identifies `NsdManager.resolveService` as a known failure source — concurrent resolves fail with `FAILURE_ALREADY_ACTIVE`, and it prescribes serialising them.

But it did not notice that its own lobby design **maximises** resolve volume. `onServiceFound` delivers only the service name and type: no host, no port, **no TXT records**. If the player's display name lives in a TXT attribute, then rendering a lobby list of "Amy, Ben, Cara" requires resolving *every* peer, and re-resolving as peers come and go. The symmetric lobby the PRD asks for is precisely the design that generates the most calls to the API it flagged as fragile.

There is a second, related problem: Android auto-renames a service on name collision (typically appending " (2)"), in a format that is not contractual. Self-filtering by comparing service names therefore breaks after a rename, and the symptom — seeing yourself in the lobby — then makes the mutual-invite UUID tiebreak fire against your own device.

## Decision

**Encode the display name and a per-launch instance UUID directly in the mDNS instance name:**

```
Instance name:  "Russ·a1b2c3d4"      Service type: _toptrumps._tcp
```

mDNS instance names are UTF-8 and may be up to 63 **bytes**, which comfortably fits a display name plus 8 hex characters.

Consequences that fall straight out:

- The lobby renders from `onServiceFound` alone. **Zero resolves.**
- `resolveService` is called exactly **once per game**, when the user taps a peer to invite — one at a time, user-paced. The concurrency failure becomes nearly unreachable, and the serialising wrapper becomes cheap insurance rather than a hot path.
- The UUID suffix provides self-filtering that survives auto-rename, collision-free names, and the mutual-invite tiebreak key — all without a resolve.

Protocol version, deck id and deck hash are **not** put in TXT records. They are only needed at handshake time, by which point the socket exists, so they travel as the first frames over TCP instead. (A protocol-version hint may optionally ride the instance name so incompatible peers can be badged before anyone taps.)

Names are sanitised before use: strip `.` (the mDNS label separator) and control characters, and truncate to 63 bytes in UTF-8, not 63 characters.

Two supporting rules, both guarding known failure modes:

- Always read `serviceName` back from `onServiceRegistered`; never trust the value passed in.
- Normalise trailing dots on `serviceType` before comparing. Some Android versions return `_toptrumps._tcp.` from `onServiceFound` even when registered without one; the naive equality check then silently yields a permanently empty lobby.

## Alternatives considered

**Display name in a TXT record, resolve every peer.** The conventional approach and semantically tidier — the instance name stays an opaque identifier, and the name is structured data rather than string-packed. Rejected because it makes the fragile operation the common one, on every lobby refresh, for every peer. It converts a rare failure into a routine one.

**Resolve lazily on first render with aggressive caching.** Reduces resolve volume without changing the record layout. Rejected as strictly more complex than not needing resolves at all, and it still resolves N peers on first entry to the lobby — the exact burst that triggers the concurrency bug.

**`registerServiceInfoCallback` (API 34+), which supersedes the deprecated resolve path and has no single-in-flight limitation.** Genuinely better where available. Rejected as the primary approach because it would require maintaining two code paths for something that, after this decision, happens once per game. Worth adding behind a version check only if resolve failures are observed in practice. `getHostAddresses()` on 34+ *is* worth using, since `getHost()` returns a single address and is unreliable on dual-stack networks.

## Consequences

**Easier:** the lobby is fast and resolve-free; the highest-risk NSD API is exercised once per game instead of continuously; self-filtering is correct even after auto-rename.

**Harder:** the display name becomes part of a network identifier, so it needs sanitising and byte-length truncation, and changing your name means re-registering the service. Names longer than ~50 characters will be truncated in other players' lobbies.

**Also note:** `onServiceLost` is unreliable regardless of this decision — mDNS goodbye packets are routinely dropped and cache expiry is inconsistent. **User story 8 ("the list updates live as people close the app") will feel broken without a last-seen TTL** that prunes peers not re-announced within ~30s. A failed resolve on tap is the most reliable liveness signal available and should immediately remove the peer.
