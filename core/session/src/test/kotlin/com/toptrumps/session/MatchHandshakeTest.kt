package com.toptrumps.session

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Both handshake gates, driven from both ends over a real [LoopbackTransport] pair — the same
 * contract-testing style as [MatchSessionTest], since [HostHandshake]/[GuestHandshake] are the
 * only things standing between a freshly connected [Transport] and a real [HostMatchSession].
 */
class MatchHandshakeTest {

    @Test
    fun `mismatched protocol version is refused on both sides before any deck message`() = runTest {
        val (host, guest) = LoopbackTransport.createPair()

        val hostResult = async { HostHandshake.awaitHello(host) }
        val guestResult = async {
            GuestHandshake.run(guest, "Guest", "instanceId", localDeckHash = { error("must not be reached") }, protocolVersion = 999)
        }

        assertEquals(HostHandshake.HelloResult.Refused(guestVersion = 999), hostResult.await())
        assertEquals(GuestHandshake.Result.VersionRefused(hostVersion = PROTOCOL_VERSION), guestResult.await())

        host.close()
        guest.close()
    }

    @Test
    fun `matching protocol version reaches Ready and gates the deck pick behind it`() = runTest {
        val (host, guest) = LoopbackTransport.createPair()

        val hostResult = async { HostHandshake.awaitHello(host) }
        guest.send(ProtocolCodec.encodeGuestToHost(GuestToHost.Hello(PROTOCOL_VERSION, "Guest", "id")))

        assertEquals(HostHandshake.HelloResult.Ready, hostResult.await())

        host.close()
        guest.close()
    }

    @Test
    fun `a deck hash mismatch is refused before any card is dealt`() = runTest {
        val (host, guest) = LoopbackTransport.createPair()
        val config = WireMatchConfig("test-deck", "CHOOSER_WINS")

        val guestResult = async {
            GuestHandshake.run(guest, "Guest", "instance-id", localDeckHash = { "different-hash" })
        }

        assertEquals(HostHandshake.HelloResult.Ready, HostHandshake.awaitHello(host))
        val hostResult = HostHandshake.chooseDeck(host, "test-deck", "host-hash", config, confirmWindow = 50.milliseconds)

        assertEquals(HostHandshake.DeckResult.Refused, hostResult)
        assertEquals(GuestHandshake.Result.DeckRefused("test-deck"), guestResult.await())

        host.close()
        guest.close()
    }

    @Test
    fun `the full handshake reaches Ready on both sides for a matching deck`() = runTest {
        val (host, guest) = LoopbackTransport.createPair()
        val config = WireMatchConfig("test-deck", "CHOOSER_WINS")
        val hand = listOf(RemoteCardFace("a", "Alpha", mapOf("topSpeed" to 120.0), "alpha.webp"))

        val guestResult = async {
            GuestHandshake.run(guest, "Guest", "instance-id", localDeckHash = { "same-hash" })
        }

        assertEquals(HostHandshake.HelloResult.Ready, HostHandshake.awaitHello(host))
        assertEquals(
            HostHandshake.DeckResult.Accepted,
            HostHandshake.chooseDeck(host, "test-deck", "same-hash", config, confirmWindow = 50.milliseconds),
        )
        // What a real HostMatchSession's `init` sends next (MatchSession.kt) — simulated here
        // since this test exercises the handshake gates in isolation from dealing.
        host.send(ProtocolCodec.encodeHostToGuest(HostToGuest.MatchStart(hand, 3)))

        assertEquals(GuestHandshake.Result.Ready("test-deck", config, hand, 3), guestResult.await())

        host.close()
        guest.close()
    }
}
