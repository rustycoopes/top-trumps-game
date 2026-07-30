# Reconnect by full-state resync, with sequence-number deduplication

**Status:** Proposed
**Date:** 2026-07-30
**Feature:** [`top-trumps-core-game`](../features/top-trumps-core-game/TDD.md)

## Context

The PRD says only that the guest "resumes using a session token issued at handshake". It does not address the actual hard case: the guest sent "I choose Top Speed", the socket dropped, and the guest never learned whether the host received it.

On reconnect the host must not double-apply that choice (the guest would lose a round it never played) and must not lose it (the game would deadlock waiting for an intent already sent). Nothing in the PRD resolves this.

There is one strong simplifying fact available: `RulesEngine.apply()` is synchronous and total, with no IO. **No transition can be half-applied** — either the entire state change happened or none of it did.

## Decision

**Client invariant: at most one intent in flight.** The choice UI is disabled from submit until the resolving `View` arrives. This reduces "pending intent" from a queue requiring gap detection to a single optional value.

Guest envelopes carry a monotonic `seq`; the host tracks `lastAcceptedGuestSeq`. The resume message carries the token and the single unconfirmed intent inline:

```kotlin
data class Resume(val sessionToken: String, val pendingIntent: PendingIntent?)
data class PendingIntent(val seq: Long, val payload: PlayerIntentWire)
```

Host handling:

1. Validate the token against a live or paused session, else `ResumeRejected(UNKNOWN_SESSION)`.
2. If a pending intent is present, compare `seq` to `lastAcceptedGuestSeq`:
   - `seq <= last` → already applied before the drop. **Do not re-apply.**
   - `seq == last + 1` → never received. Apply it through the normal reducer path.
   - anything else → protocol violation, unreachable given the in-flight invariant.
3. Reply `ResumeAck` carrying a **full `project()` of current state**.

A `resync: Boolean` flag tells the client to hard-cut rather than replay animations for transitions it missed. Session tokens are 128-bit and **regenerated per rematch**, so a late `Resume` from a finished match cannot land in a new one.

## Alternatives considered

**Event-log replay from `lastAppliedEventId`.** The obvious alternative: the host keeps a durable log and replays events the guest missed. Rejected on three counts. There is no bandwidth argument — `PlayerView` is small enough that resending it entirely costs nothing. It requires the host to maintain a log as a *second source of truth* alongside `MatchState`, purely to serve reconnection, with the attendant risk of the two drifting. And it is separate code to write, test and get subtly wrong, whereas full resync reuses the exact `project()` path every other push already uses — so it is covered by every existing test.

**Idempotency keys on the intent rather than sequence numbers.** Equivalent in effect. Sequence numbers were preferred because the at-most-one-in-flight invariant makes `last + 1` a complete specification, and a monotonic counter is trivially inspectable in logs.

**Resend the intent unconditionally and let the host detect duplicates by content.** Rejected — two identical legitimate choices ("Top Speed" in consecutive rounds) are indistinguishable by content, and the round number alone doesn't disambiguate a tiebreak sequence.

**No resume at all; abandon on any drop.** Rejected by the PRD's own requirements, and made unnecessary by the foreground service reducing drop frequency in the first place.

## Consequences

**Easier:** reconnection has exactly two cases to reason about and both are decided by an integer comparison; a mid-flight tiebreak needs no special handling because `remainingMetrics` and `revealHistory` are ordinary `MatchState` fields captured by the same resync; there is no replay code path to maintain.

**Harder:** the in-flight invariant is a real UI constraint that must be honoured everywhere a choice can be submitted, including the AI driver — violating it silently breaks the deduplication scheme.

**Important asymmetry this exposes:** a *guest* drop is genuinely recoverable, because the host's state is untouched. A *host* drop is only recoverable if the host process survived; if it died there is nothing to resume into, and the guest's countdown can only end in abandonment. This is inherent to the host-authoritative, in-memory-only model rather than a flaw in this decision — but the PRD's stories 58–60 read as though the two are symmetric, and the countdown copy must not overpromise.
