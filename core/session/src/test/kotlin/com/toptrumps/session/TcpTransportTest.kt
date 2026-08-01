package com.toptrumps.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.DataOutputStream
import java.net.Socket
import javax.net.ServerSocketFactory
import javax.net.SocketFactory

/**
 * Real sockets on `127.0.0.1:0`, plain JVM — the framing bugs the TDD calls out (partial reads,
 * two frames in one read, a frame split across segments) are invisible on a fast LAN and surface
 * once in ten games in the field.
 */
class TcpTransportTest {

    @Test
    fun `a frame sent by one end is received whole by the other`() = runBlocking {
        withConnectedPair { host, guest ->
            host.send("hello".encodeToByteArray())
            val received = withTimeout(5_000) { guest.incoming.first() }
            assertEquals("hello", received.decodeToString())
        }
    }

    @Test
    fun `messages round-trip in both directions`() = runBlocking {
        withConnectedPair { host, guest ->
            host.send("ping".encodeToByteArray())
            guest.send("pong".encodeToByteArray())
            assertEquals("ping", withTimeout(5_000) { guest.incoming.first() }.decodeToString())
            assertEquals("pong", withTimeout(5_000) { host.incoming.first() }.decodeToString())
        }
    }

    @Test
    fun `two frames written in a single TCP write are still delivered as two separate frames`() = runBlocking {
        withListener { listener, port ->
            val raw = Socket("127.0.0.1", port)
            val out = DataOutputStream(raw.getOutputStream())
            writeFrame(out, "one".encodeToByteArray())
            writeFrame(out, "two".encodeToByteArray())
            out.flush()

            val accepted = withTimeout(5_000) { listener.connections.first() }
            val messages = withTimeout(5_000) { accepted.incoming.take(2).toList() }
            assertEquals(listOf("one", "two"), messages.map { it.decodeToString() })
            raw.close()
        }
    }

    @Test
    fun `a frame split across two separate TCP writes is still reassembled correctly`() = runBlocking {
        withListener { listener, port ->
            val raw = Socket("127.0.0.1", port)
            val out = DataOutputStream(raw.getOutputStream())
            val payload = "a-message-split-across-segments".encodeToByteArray()
            out.writeInt(payload.size)
            out.flush()
            out.write(payload, 0, payload.size / 2)
            out.flush()
            out.write(payload, payload.size / 2, payload.size - payload.size / 2)
            out.flush()

            val accepted = withTimeout(5_000) { listener.connections.first() }
            val received = withTimeout(5_000) { accepted.incoming.first() }
            assertEquals(payload.decodeToString(), received.decodeToString())
            raw.close()
        }
    }

    @Test
    fun `a frame header claiming more than the hard ceiling is treated as a corrupt stream`() = runBlocking {
        withListener { listener, port ->
            val raw = Socket("127.0.0.1", port)
            val out = DataOutputStream(raw.getOutputStream())
            out.writeInt(TcpTransport.MAX_FRAME_BYTES + 1)
            out.flush()

            val accepted = withTimeout(5_000) { listener.connections.first() }
            // The oversized header kills the reader coroutine; the incoming flow simply completes
            // rather than delivering a frame or crashing the process.
            val frames = withTimeout(5_000) { accepted.incoming.toList() }
            assertEquals(emptyList<ByteArray>(), frames)
            raw.close()
        }
    }

    private fun writeFrame(out: DataOutputStream, payload: ByteArray) {
        out.writeInt(payload.size)
        out.write(payload)
    }

    private suspend fun withListener(block: suspend (TcpListener, Int) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = TcpListener.bind(ServerSocketFactory.getDefault(), scope, Dispatchers.IO)
        try {
            block(listener, listener.localPort)
        } finally {
            listener.stop()
            scope.cancel()
        }
    }

    private suspend fun withConnectedPair(block: suspend (Transport, Transport) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = TcpListener.bind(ServerSocketFactory.getDefault(), scope, Dispatchers.IO)
        try {
            val guestDeferred = scope.async { listener.connections.first() }
            val host = TcpTransport.connect(SocketFactory.getDefault(), "127.0.0.1", listener.localPort, scope, Dispatchers.IO)
            val guest = withTimeout(5_000) { guestDeferred.await() }
            block(host, guest)
        } finally {
            listener.stop()
            scope.cancel()
        }
    }
}
