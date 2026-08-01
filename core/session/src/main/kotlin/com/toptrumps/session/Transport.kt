package com.toptrumps.session

import kotlinx.coroutines.flow.Flow

/**
 * Carries raw bytes, never typed messages, so the single test seam exercises the real
 * serialisation codec and framing rather than bypassing them — see the byte-transport ADR.
 */
public interface Transport {
    public val incoming: Flow<ByteArray>
    public suspend fun send(bytes: ByteArray)
    public fun close()

    /**
     * Closes only once whatever was already handed to [send] has actually been written — a plain
     * [close] can otherwise race a courtesy message queued moments earlier (see the
     * reconnect-resync ADR's deliberate-quit note) and drop it silently. Defaults to [close] for
     * implementations with nothing to drain first; [TcpTransport] is the one that overrides it.
     */
    public suspend fun closeGracefully() {
        close()
    }
}
