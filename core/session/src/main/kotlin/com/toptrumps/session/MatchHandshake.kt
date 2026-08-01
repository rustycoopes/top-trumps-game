package com.toptrumps.session

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The two handshake gates ahead of a real [HostMatchSession] — TDD §5. Runs on an already-connected
 * [Transport] (post lobby-invite acceptance); once [chooseDeck] accepts, the caller constructs a
 * [HostMatchSession] exactly as solo mode does, and this object has no further part to play.
 */
public object HostHandshake {

    /** How long [chooseDeck] waits for a possible [GuestToHost.DeckMismatch] before dealing — same-Wi-Fi round trips are single-digit milliseconds, so this is generous rather than tight. */
    public val DEFAULT_DECK_CONFIRM_WINDOW: Duration = 500.milliseconds

    public sealed interface HelloResult {
        /** The guest is running an incompatible build; the caller must not offer a deck to pick. */
        public data class Refused(val guestVersion: Int) : HelloResult
        public data object Ready : HelloResult
    }

    /** Gate 1: blocks deck selection until the guest's [GuestToHost.Hello] is seen and its protocol version matches ours. */
    public suspend fun awaitHello(transport: Transport): HelloResult {
        val hello = transport.incoming
            .map { ProtocolCodec.decodeGuestToHost(it) }
            .filterIsInstance<GuestToHost.Hello>()
            .first()
        return if (hello.protocolVersion != PROTOCOL_VERSION) {
            transport.send(ProtocolCodec.encodeHostToGuest(HostToGuest.VersionMismatch(PROTOCOL_VERSION)))
            HelloResult.Refused(hello.protocolVersion)
        } else {
            transport.send(ProtocolCodec.encodeHostToGuest(HostToGuest.HelloAck(sessionToken = UUID.randomUUID().toString())))
            HelloResult.Ready
        }
    }

    public sealed interface DeckResult {
        public data object Accepted : DeckResult
        public data object Refused : DeckResult
    }

    /**
     * Gate 2: sends the chosen deck, then gives the guest [confirmWindow] to flag a mismatch
     * before the caller is told it's safe to deal. There is no positive acknowledgement in the
     * wire protocol (TDD §5's message table has none) — silence within the window is the accept
     * signal, which is sound on a same-Wi-Fi link but is a deliberate, documented trade-off rather
     * than a guaranteed race-free handshake.
     */
    public suspend fun chooseDeck(
        transport: Transport,
        deckId: String,
        deckHash: String,
        config: WireMatchConfig,
        confirmWindow: Duration = DEFAULT_DECK_CONFIRM_WINDOW,
    ): DeckResult {
        transport.send(ProtocolCodec.encodeHostToGuest(HostToGuest.DeckChosen(deckId, deckHash, config)))
        // Anything the guest sends during this window that *isn't* a DeckMismatch is silently
        // discarded by filterIsInstance — inert today since nothing else is legitimate to send
        // here, but a future GuestToHost.Leave (slice 6) arriving in this exact window would
        // vanish the same way and needs its own handling when that message starts being sent.
        val mismatch = withTimeoutOrNull(confirmWindow) {
            transport.incoming
                .map { ProtocolCodec.decodeGuestToHost(it) }
                .filterIsInstance<GuestToHost.DeckMismatch>()
                .first()
        }
        return if (mismatch != null) DeckResult.Refused else DeckResult.Accepted
    }
}

/** The guest's half of the same two gates — a thin, reactive counterpart with no timing of its own. */
public object GuestHandshake {

    public sealed interface Result {
        public data class VersionRefused(val hostVersion: Int) : Result
        public data class DeckRefused(val deckId: String) : Result
        public data class Ready(val deckId: String, val config: WireMatchConfig, val yourHand: List<RemoteCardFace>, val roundCount: Int) : Result
    }

    /**
     * Sends [GuestToHost.Hello] and drives both gates to a conclusion. [localDeckHash] reads the
     * guest's own copy of [HostToGuest.DeckChosen.deckId]'s manifest — `null` (deck not found or
     * unreadable) is treated the same as a hash mismatch. [protocolVersion] defaults to this
     * build's [PROTOCOL_VERSION]; a test overrides it to simulate the "one phone auto-updated
     * before the other" scenario the version gate exists for.
     */
    public suspend fun run(
        transport: Transport,
        displayName: String,
        instanceId: String,
        localDeckHash: (deckId: String) -> String?,
        protocolVersion: Int = PROTOCOL_VERSION,
    ): Result {
        transport.send(ProtocolCodec.encodeGuestToHost(GuestToHost.Hello(protocolVersion, displayName, instanceId)))
        val messages = transport.incoming.map { ProtocolCodec.decodeHostToGuest(it) }

        val afterHello = messages.first { it is HostToGuest.VersionMismatch || it is HostToGuest.HelloAck }
        if (afterHello is HostToGuest.VersionMismatch) return Result.VersionRefused(afterHello.hostVersion)

        val deckChosen = messages.filterIsInstance<HostToGuest.DeckChosen>().first()
        val localHash = localDeckHash(deckChosen.deckId)
        if (localHash == null || localHash != deckChosen.deckHash) {
            transport.send(ProtocolCodec.encodeGuestToHost(GuestToHost.DeckMismatch(deckChosen.deckId)))
            return Result.DeckRefused(deckChosen.deckId)
        }

        val matchStart = messages.filterIsInstance<HostToGuest.MatchStart>().first()
        return Result.Ready(deckChosen.deckId, deckChosen.config, matchStart.yourHand, matchStart.roundCount)
    }
}
