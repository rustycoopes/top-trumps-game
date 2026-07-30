# Transport carries bytes; length-prefixed JSON frames

**Status:** Proposed
**Date:** 2026-07-30
**Feature:** [`top-trumps-core-game`](../features/top-trumps-core-game/TDD.md)

## Context

The PRD mandates one test seam — two `MatchSession` instances over an in-memory `Transport` — and states that the seam must cover "the join between rules and protocol", because that is where host-authoritative games break.

Where the serialisation boundary sits determines whether that is true. If `Transport` carries typed messages, the codec sits *outside* the seam and is never exercised by any seam test; the in-memory pair would pass objects by reference and every encoding bug would survive to the device.

Separately, the raw socket needs a framing scheme, and TCP delivers a byte stream with no message boundaries.

## Decision

**`Transport` carries `ByteArray`:**

```kotlin
interface Transport {
    val incoming: Flow<ByteArray>   // one complete frame per element
    suspend fun send(frame: ByteArray)
    suspend fun close()
}
```

Every seam test therefore round-trips real JSON encode/decode. `LoopbackTransport` routes through the same `ProtocolCodec` as `TcpTransport` — skipping it in the in-memory implementation would defeat the entire rationale.

**Framing: 4-byte big-endian length prefix**, read via `DataInputStream.readFully`. Reassembly is private to `TcpTransport`; callers above `Transport` always see whole frames. Max frame 16KB expected, 64KB hard ceiling — a header claiming more is treated as a corrupt or hostile stream and the connection is dropped, which bounds buffer allocation.

**Serialisation: kotlinx.serialization JSON**, with two separate sealed hierarchies (`GuestToHost`, `HostToGuest`) so it is a compile error for either side to construct the other's messages.

**Versioning: a single `Int`, strict equality, hard refuse** on mismatch.

`LoopbackTransport` ships as **production code** — solo mode runs a real host session and a real guest session over it on one device.

## Alternatives considered

**`Transport<WireMessage>` carrying typed messages.** More natural to read and write, and removes a serialisation step from every test. Rejected because it puts the codec outside the only seam, leaving serialisation bugs — polymorphic discriminators, missing `@Serializable`, unknown-key handling — entirely uncovered until two phones are in hand.

**Newline-delimited JSON framing.** Simpler to implement and to eyeball in a log. Rejected because its correctness depends on payloads never containing a raw newline, which is a property of the serialisation format rather than of the framing layer; display names are user-controlled, and the guarantee would evaporate the moment anyone considered a binary encoding.

**Protobuf or another binary format.** Smaller and faster. Rejected because payloads are tiny (no images cross the wire — cards are referenced by string and resolved from local assets), and binary formats mainly earn their keep supporting independently-versioned peers, which is irrelevant when both devices always run the same sideloaded APK. JSON's readability in logcat during two-phone debugging of a from-scratch protocol is worth more than the bytes.

**Semantic versioning with graceful degradation.** Rejected: there is no server to be out of step with, and the PRD's story 17 scenario is simply "one phone updated before the other" — served exactly by strict equality and an honest refusal.

## Consequences

**Easier:** the single seam genuinely covers rules, protocol and codec; solo mode exercises the real wire format on every play, so `LoopbackTransport` cannot rot into a fiction that diverges from the socket; framing is testable on the JVM against loopback with no double at all.

**Harder:** every seam test pays encode/decode cost (negligible at this payload size), and assertions on transport contents operate on bytes, so debugging a protocol test means decoding first. A small test helper covers that.

**Forecloses:** streaming or partial-message delivery, which this protocol has no use for. Also means a future binary encoding is a codec swap rather than a `Transport` change — which is the right place for it.
