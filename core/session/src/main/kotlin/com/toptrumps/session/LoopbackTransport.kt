package com.toptrumps.session

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Solo mode's transport, and production code rather than a test double — every solo game
 * exercises the real [ProtocolCodec] this way, so it cannot rot into a fiction. See TDD §5.
 *
 * [dropConnection]/[reconnect] exist only for slice 6's tests, simulating a peer going silent and
 * a connection recovering — same-process, so there is no real socket to model, just delivery.
 */
public class LoopbackTransport private constructor(
    inboundChannel: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
) : Transport {

    // Real TCP gives no signal for a frozen/half-open peer either, so a "dropped" connection here
    // means the same thing: frames keep arriving on the channel but are filtered out rather than
    // ever queued for the collector to see, and resuming delivery is all `reconnect` needs to do.
    @Volatile private var deliveryEnabled = true
    override val incoming: Flow<ByteArray> = inboundChannel.receiveAsFlow().filter { deliveryEnabled }

    override suspend fun send(bytes: ByteArray) {
        outgoing.send(bytes)
    }

    /** Closes only this end's outgoing channel — call [close] on both ends of a [createPair] to fully tear one down. */
    override fun close() {
        outgoing.close()
    }

    /** Simulates the peer going quiet — a same-socket blip, not a real disconnect (there's no separate "new transport" here). */
    public fun dropConnection() {
        deliveryEnabled = false
    }

    /**
     * Simulates the same connection recovering after a blip. A genuine drop-and-redial is tested
     * with a fresh [createPair] instead, matching how a real reconnect dials a brand new socket.
     */
    public fun reconnect() {
        deliveryEnabled = true
    }

    public companion object {
        /** Two ends of one in-memory pipe: whatever `first` sends, `second.incoming` receives, and vice versa. */
        public fun createPair(): Pair<LoopbackTransport, LoopbackTransport> {
            val hostToGuest = Channel<ByteArray>(Channel.UNLIMITED)
            val guestToHost = Channel<ByteArray>(Channel.UNLIMITED)
            val host = LoopbackTransport(inboundChannel = guestToHost, outgoing = hostToGuest)
            val guest = LoopbackTransport(inboundChannel = hostToGuest, outgoing = guestToHost)
            return host to guest
        }
    }
}
