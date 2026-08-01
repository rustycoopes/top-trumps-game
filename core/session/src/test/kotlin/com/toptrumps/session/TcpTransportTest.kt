package com.toptrumps.session

import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.Direction
import com.toptrumps.rules.MatchConfig
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.MetricSpec
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.rules.StatValue
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataOutputStream
import java.net.Socket
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

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

    /**
     * The WBS's own testing note: "extend the loopback TCP tests from slice 4 to cover a full
     * match over a real socket on 127.0.0.1." One metric and two cards with distinct values —
     * the round decides on the first pick with no tiebreak — so this stays a real end-to-end
     * exercise of the handshake and a full match, rather than reimplementing [MatchDriver]'s
     * tiebreak-walking against real network latency.
     */
    @Test
    fun `the full handshake and a full match play out correctly over a real socket`() = runBlocking {
        withConnectedPair { hostTransport, guestTransport ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val deck = tinyDeck()
                val hash = "test-hash"

                val guestHandshake = async {
                    GuestHandshake.run(guestTransport, "Guest", "instance-id", localDeckHash = { hash })
                }

                val helloResult = withTimeout(5_000) { HostHandshake.awaitHello(hostTransport) }
                assertTrue(helloResult is HostHandshake.HelloResult.Ready)
                val sessionToken = (helloResult as HostHandshake.HelloResult.Ready).sessionToken
                assertEquals(
                    HostHandshake.DeckResult.Accepted,
                    withTimeout(5_000) {
                        HostHandshake.chooseDeck(hostTransport, deck.id, hash, WireMatchConfig(deck.id, "CHOOSER_WINS"), confirmWindow = 200.milliseconds)
                    },
                )

                val hostSession = HostMatchSession(deck, MatchConfig(deck.id), Random(1), hostTransport, scope, sessionToken, "Opponent")
                val handshakeResult = withTimeout(5_000) { guestHandshake.await() }
                assertNotNull(handshakeResult as? GuestHandshake.Result.Ready)
                val guestSession = GuestMatchSession(guestTransport, scope, sessionToken, "Opponent")

                // Real sockets, unlike the in-memory seam tests, genuinely suspend between a
                // submit() and its resolving View — the deal itself is the first such round trip.
                val dealtView = withTimeout(5_000) { guestSession.view.first { it != null } } as MatchView.InProgress
                val chooser = (dealtView.round as RemoteRoundState.AwaitingChoice).chooser
                val chooserSession = if (chooser == "HOST") hostSession else guestSession
                chooserSession.submit(PlayerIntent.ChooseMetric(MetricKey("speed")))

                withTimeout(5_000) {
                    hostSession.view.first { (it as? MatchView.InProgress)?.round is RemoteRoundState.Resolved }
                }
                hostSession.submit(PlayerIntent.AdvanceRound)

                val hostFinal = withTimeout(5_000) { hostSession.view.first { it is MatchView.Finished } } as MatchView.Finished
                val guestFinal = withTimeout(5_000) { guestSession.view.first { it is MatchView.Finished } } as MatchView.Finished

                assertEquals(hostFinal.winner, guestFinal.winner)
                assertEquals(2, hostFinal.myScore + hostFinal.opponentScore)

                hostSession.close()
                guestSession.close()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun tinyDeck(): Deck = Deck(
        id = "tiny-deck",
        metrics = listOf(MetricSpec(MetricKey("speed"), "Speed", "mph", Direction.HIGH_WINS)),
        cards = listOf(
            Card("a", "Alpha", mapOf(MetricKey("speed") to StatValue(100.0))),
            Card("b", "Bravo", mapOf(MetricKey("speed") to StatValue(50.0))),
        ),
    )

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
