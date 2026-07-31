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
}
