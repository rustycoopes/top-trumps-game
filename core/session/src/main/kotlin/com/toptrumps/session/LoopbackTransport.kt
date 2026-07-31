package com.toptrumps.session

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Solo mode's transport, and production code rather than a test double — every solo game
 * exercises the real [ProtocolCodec] this way, so it cannot rot into a fiction. See TDD §5.
 */
public class LoopbackTransport private constructor(
    override val incoming: Flow<ByteArray>,
    private val outgoing: Channel<ByteArray>,
) : Transport {

    override suspend fun send(bytes: ByteArray) {
        outgoing.send(bytes)
    }

    /** Closes only this end's outgoing channel — call [close] on both ends of a [createPair] to fully tear one down. */
    override fun close() {
        outgoing.close()
    }

    public companion object {
        /** Two ends of one in-memory pipe: whatever `first` sends, `second.incoming` receives, and vice versa. */
        public fun createPair(): Pair<Transport, Transport> {
            val hostToGuest = Channel<ByteArray>(Channel.UNLIMITED)
            val guestToHost = Channel<ByteArray>(Channel.UNLIMITED)
            val host = LoopbackTransport(incoming = guestToHost.receiveAsFlow(), outgoing = hostToGuest)
            val guest = LoopbackTransport(incoming = hostToGuest.receiveAsFlow(), outgoing = guestToHost)
            return host to guest
        }
    }
}
