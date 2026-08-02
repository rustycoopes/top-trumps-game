package com.toptrumps.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import javax.net.ServerSocketFactory
import javax.net.SocketFactory

/**
 * The real [Transport]: a 4-byte big-endian length-prefixed frame over a [Socket], per the
 * byte-transport ADR. Writes are serialised through a single writer coroutine consuming
 * [outgoing] — two coroutines sharing one [java.io.OutputStream] would otherwise interleave
 * frames.
 *
 * `accept()`/`read()` do not respond to coroutine cancellation, so this class does not rely on
 * it: [close] closes the socket directly, which is what unblocks a pending read on the reader
 * coroutine. Callers must wire [close] into their own teardown path (the same way `:app`'s
 * connection holders already do for [MatchSession]) rather than expect cancelling the owning
 * scope alone to tear the socket down.
 *
 * [ioDispatcher] is injected rather than a hardcoded `Dispatchers.IO` — blocking socket calls
 * must still run wherever the caller says, per `:core:*`'s no-hardcoded-dispatcher discipline,
 * so slice 6's virtual-time tests aren't at the mercy of a real thread pool.
 */
public class TcpTransport private constructor(
    private val socket: Socket,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : Transport {

    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    /**
     * The peer's address — for an accepted (not dialled) connection, this is the only way the
     * local side ever learns it, which is exactly the guest's situation: the host is always the
     * one who dialled (see the reconnect-resync ADR). Lets a guest redial directly on reconnect
     * with no NSD re-resolve.
     */
    public val remoteHost: String get() = socket.inetAddress.hostAddress ?: socket.inetAddress.hostName

    private val received = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = received.receiveAsFlow()

    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)

    private val writerJob: Job

    init {
        scope.launch(ioDispatcher) {
            try {
                while (true) {
                    received.send(readFrame())
                }
            } catch (e: IOException) {
                // Peer closed, or `close()` closed the socket out from under us — either way,
                // there is nothing more to read.
                println("TTWire: reader loop ended on socket=${System.identityHashCode(socket)}: ${e.message}")
            } finally {
                received.close()
            }
        }
        writerJob = scope.launch(ioDispatcher) {
            try {
                for (frame in outgoing) {
                    writeFrame(frame)
                }
            } catch (e: IOException) {
                // The reader coroutine observes the same closed socket and unwinds on its own.
                println("TTWire: writer loop ended on socket=${System.identityHashCode(socket)}: ${e.message}")
            }
        }
    }

    override suspend fun send(bytes: ByteArray) {
        println("TTWire: queuing send of ${bytes.size} bytes on socket=${System.identityHashCode(socket)}")
        outgoing.send(bytes)
    }

    /**
     * Idempotent: closing an already-closed socket is a silent no-op. Half-closes the output
     * side first — see the reconnect-resync ADR's deliberate-quit note — so the peer's read
     * returns a clean EOF (`-1`) rather than risking a reset racing the last frame, whether this
     * close is a deliberate quit or ordinary teardown; there's no case where the reset is
     * preferable.
     *
     * Does *not* wait for a just-`send()`-ed frame to actually reach the wire — `outgoing` is
     * only queued into, not flushed, by `send()`'s return. A caller that needs its last frame
     * guaranteed to land before the socket dies (a deliberate-quit courtesy message) must use
     * [closeGracefully] instead.
     */
    override fun close() {
        outgoing.close()
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    /**
     * Waits for the writer coroutine to actually drain [outgoing] — including a frame `send()`
     * just queued — before closing, so a courtesy message can't lose the race against the socket
     * dying underneath it.
     */
    override suspend fun closeGracefully() {
        outgoing.close()
        writerJob.join()
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    private fun readFrame(): ByteArray {
        println("TTWire: blocking on readFrame socket=${System.identityHashCode(socket)}")
        val length = input.readInt()
        // An IOException, not `require`'s IllegalArgumentException — the reader loop below only
        // catches IOException, and a peer sending a bogus header must drop the connection
        // cleanly rather than escape as an uncaught exception in the reader coroutine.
        if (length !in 0..MAX_FRAME_BYTES) {
            throw IOException("Frame length $length outside 0..$MAX_FRAME_BYTES — corrupt or hostile stream")
        }
        val frame = ByteArray(length)
        input.readFully(frame)
        println("TTWire: read ${frame.size} bytes on socket=${System.identityHashCode(socket)}")
        return frame
    }

    private fun writeFrame(frame: ByteArray) {
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
        println("TTWire: wrote ${frame.size} bytes on socket=${System.identityHashCode(socket)}")
    }

    public companion object {
        /** Expected frames stay well under this; a header claiming more is a corrupt or hostile stream. */
        public const val MAX_FRAME_BYTES: Int = 64 * 1024

        /** Wraps an already-connected or already-accepted [Socket]. */
        public fun wrap(socket: Socket, scope: CoroutineScope, ioDispatcher: CoroutineDispatcher): TcpTransport {
            socket.tcpNoDelay = true
            return TcpTransport(socket, scope, ioDispatcher)
        }

        /** Dials out through the (typically Wi-Fi-bound) [socketFactory] — see the network-binding ADR. */
        public suspend fun connect(
            socketFactory: SocketFactory,
            host: String,
            port: Int,
            scope: CoroutineScope,
            ioDispatcher: CoroutineDispatcher,
        ): TcpTransport {
            val socket = withContext(ioDispatcher) { socketFactory.createSocket(host, port) }
            return wrap(socket, scope, ioDispatcher)
        }
    }
}

/**
 * Binds a listening socket through an injected [ServerSocketFactory] and hands each inbound
 * connection back as a framed [TcpTransport]. Bind with port `0` and read [localPort] *after*
 * [bind] and before NSD registration — `setPort(0)` on the registration is rejected. A fresh
 * [TcpListener] per lobby session, matching the "fresh listener object per session" NSD rule
 * this component sits next to (TDD §8).
 */
public class TcpListener private constructor(
    private val serverSocket: ServerSocket,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    public val localPort: Int get() = serverSocket.localPort

    private val _connections = Channel<TcpTransport>(Channel.UNLIMITED)
    public val connections: Flow<TcpTransport> = _connections.receiveAsFlow()

    private val acceptJob: Job = scope.launch(ioDispatcher) {
        try {
            while (true) {
                val socket = serverSocket.accept()
                _connections.send(TcpTransport.wrap(socket, scope, ioDispatcher))
            }
        } catch (_: IOException) {
            // `stop()` closing the server socket is what unblocks `accept()` on shutdown.
        } finally {
            _connections.close()
        }
    }

    /** Idempotent: closing an already-closed server socket is a silent no-op. */
    public fun stop() {
        runCatching { serverSocket.close() }
    }

    public companion object {
        /** [port] defaults to `0` (OS-assigned) for tests; production lobby code binds [LOBBY_PORT] instead, so a manual-connect code needs only the peer's address. */
        public suspend fun bind(
            serverSocketFactory: ServerSocketFactory,
            scope: CoroutineScope,
            ioDispatcher: CoroutineDispatcher,
            port: Int = 0,
        ): TcpListener {
            val serverSocket = withContext(ioDispatcher) { serverSocketFactory.createServerSocket(port) }
            return TcpListener(serverSocket, scope, ioDispatcher)
        }
    }
}

/**
 * The lobby's well-known listening port. Fixed rather than OS-assigned so the manual-connect
 * fallback (story 10) needs only the peer's IP address — WBS slice 4 calls for showing "only the
 * last octet as a two-digit code, with a full-address affordance behind it", which only reads as
 * a *code* if the port is a constant both apps already know.
 */
public const val LOBBY_PORT: Int = 45632
