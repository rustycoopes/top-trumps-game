@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.toptrumps.session

import com.toptrumps.rules.Card
import com.toptrumps.rules.Deck
import com.toptrumps.rules.Direction
import com.toptrumps.rules.MatchConfig
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.MetricSpec
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.rules.StatValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val TOKEN = "resilience-test-token"
private val speed = MetricKey("speed")

/** Six distinct-speed cards (three rounds), so every round resolves on the first pick — this file tests connection behaviour, not tiebreak walking. */
private fun sixCardDeck(): Deck = Deck(
    id = "resilience-deck",
    metrics = listOf(MetricSpec(speed, "Speed", "mph", Direction.HIGH_WINS)),
    cards = listOf(
        Card("a", "Alpha", mapOf(speed to StatValue(120.0))),
        Card("b", "Bravo", mapOf(speed to StatValue(150.0))),
        Card("c", "Charlie", mapOf(speed to StatValue(95.0))),
        Card("d", "Delta", mapOf(speed to StatValue(175.0))),
        Card("e", "Echo", mapOf(speed to StatValue(200.0))),
        Card("f", "Foxtrot", mapOf(speed to StatValue(110.0))),
    ),
)

/** Submits [PlayerIntent.ChooseMetric] from whichever side is actually this round's chooser — resolves it in one pick, since every card's speed is distinct. */
private fun resolveOneRound(host: MatchSession, guest: MatchSession) {
    val view = host.view.value as MatchView.InProgress
    val chooser = (view.round as RemoteRoundState.AwaitingChoice).chooser
    val intent = PlayerIntent.ChooseMetric(MetricKey(view.metrics.first().key))
    if (chooser == "HOST") host.submit(intent) else guest.submit(intent)
}

private fun roundNumber(session: MatchSession): Int = (session.view.value as MatchView.InProgress).roundNumber

/**
 * The heartbeat/unreachable/countdown/resume machinery — the primary seam extended for slice 6,
 * still two [MatchSession]s over an in-memory [Transport] pair. [UnconfinedTestDispatcher] runs
 * each session's internal coroutines eagerly (matching [MatchSessionTest]'s convention), so only
 * the heartbeat's own `delay()`-driven timing needs explicit [advanceTimeBy] — everything else
 * (submits, resumes, quits) settles synchronously.
 */
class ConnectionResilienceTest {

    @Test
    fun `three missed heartbeats declare the peer unreachable, and an un-recovered window abandons the match`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(sixCardDeck(), MatchConfig("resilience-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Opponent")
            GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Opponent")

            // Host stops hearing anything the guest sends (including its heartbeats) — simulates
            // the guest freezing mid-background, per the foreground-service ADR.
            hostTransport.dropConnection()

            assertEquals(ConnectionState.Connected, host.connectionState.value)
            advanceTimeBy(6.seconds) // 3 * 2s heartbeat interval
            runCurrent() // advanceTimeBy stops just short of a task scheduled exactly at the target instant
            assertTrue(host.connectionState.value is ConnectionState.PeerUnreachable, "expected PeerUnreachable after ~6s of silence, was ${host.connectionState.value}")

            advanceTimeBy(60.seconds) // the rest of the 60s window
            runCurrent()
            assertEquals(ConnectionState.Abandoned(AbandonReason.GRACE_EXPIRED), host.connectionState.value)
        }

    @Test
    fun `traffic resuming mid-countdown restores Connected without a full resume handshake`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(sixCardDeck(), MatchConfig("resilience-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Opponent")
            GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Opponent")

            hostTransport.dropConnection()
            advanceTimeBy(6.seconds)
            runCurrent()
            assertTrue(host.connectionState.value is ConnectionState.PeerUnreachable)

            // The same blip recovering — no new socket, so no Resume/ResumeAck needed; the very
            // next heartbeat the guest sends is enough for the host to notice.
            hostTransport.reconnect()
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(ConnectionState.Connected, host.connectionState.value)
        }

    @Test
    fun `a deliberate guest quit is seen by the host as PeerLeft, not a countdown`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(sixCardDeck(), MatchConfig("resilience-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Opponent")
        val guest = GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Opponent")

        guest.leave()

        assertEquals(ConnectionState.PeerLeft, host.connectionState.value)
    }

    @Test
    fun `a deliberate host quit is seen by the guest as PeerLeft, not a countdown`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(sixCardDeck(), MatchConfig("resilience-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Opponent")
        val guest = GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Opponent")

        host.leave()

        assertEquals(ConnectionState.PeerLeft, guest.connectionState.value)
    }

    @Test
    fun `a guest intent the host never received is applied exactly once after a resume`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(sixCardDeck(), MatchConfig("resilience-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Opponent")
            val guest = GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Opponent")
            resolveOneRound(host, guest)
            val roundBefore = roundNumber(host)

            // The host never sees this at all — simulates the drop happening before the frame arrived.
            hostTransport.dropConnection()
            guest.submit(PlayerIntent.AdvanceRound) // AdvanceRound isn't seat-gated — either side may send it.
            assertEquals(roundBefore, roundNumber(host), "the host must not have advanced yet — it never received the frame")

            val (newHostTransport, newGuestTransport) = LoopbackTransport.createPair()
            guest.reconnect(newGuestTransport)
            val resume = ProtocolCodec.decodeGuestToHost(newHostTransport.incoming.first()) as GuestToHost.Resume
            host.acceptResume(newHostTransport, resume)

            assertEquals(roundBefore + 1, roundNumber(host), "the pending AdvanceRound should have been applied exactly once")
            assertEquals(roundBefore + 1, roundNumber(guest))
            assertEquals(ConnectionState.Connected, guest.connectionState.value)
        }

    @Test
    fun `the guest's lastResync flags exactly the view a resume delivered, never an organic one, and the host is never flagged`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(sixCardDeck(), MatchConfig("resilience-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Opponent")
            val guest = GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Opponent")
            assertEquals(null, guest.lastResync.value, "no resume has happened yet")

            // An organic transition — resolving a round the ordinary way — must never set it.
            resolveOneRound(host, guest)
            assertEquals(null, guest.lastResync.value, "an organic view update is not a resync")
            assertEquals(null, host.lastResync.value, "the host is never resynced")

            hostTransport.dropConnection()
            val (newHostTransport, newGuestTransport) = LoopbackTransport.createPair()
            guest.reconnect(newGuestTransport)
            val resume = ProtocolCodec.decodeGuestToHost(newHostTransport.incoming.first()) as GuestToHost.Resume
            host.acceptResume(newHostTransport, resume)

            val resumedView = guest.view.value as MatchView.InProgress
            assertEquals(resumedView.revision, guest.lastResync.value, "lastResync must name the exact revision the resume delivered")
            assertEquals(null, host.lastResync.value, "the host is still never resynced — it's the guest that dropped")

            // The next organic transition after the resume must not still read as a resync —
            // advance past the already-resolved round first, then resolve the next one.
            host.submit(PlayerIntent.AdvanceRound)
            resolveOneRound(host, guest)
            val nextView = guest.view.value as MatchView.InProgress
            assertTrue(nextView.revision > resumedView.revision)
            assertEquals(resumedView.revision, guest.lastResync.value, "lastResync stays pinned to the resumed revision, not the one after it")
        }

    @Test
    fun `a guest intent the host already applied is not re-applied after a resume`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hostTransport, guestTransport) = LoopbackTransport.createPair()
            val host = HostMatchSession(sixCardDeck(), MatchConfig("resilience-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Opponent")
            val guest = GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Opponent")
            resolveOneRound(host, guest)
            val roundBefore = roundNumber(host)

            // The guest stops *hearing* the host, but can still send — so the host receives and
            // applies AdvanceRound normally, and only the guest's copy of the acknowledging View is lost.
            guestTransport.dropConnection()
            guest.submit(PlayerIntent.AdvanceRound)
            assertEquals(roundBefore + 1, roundNumber(host), "the host should have applied it despite the guest never seeing the ack")

            val (newHostTransport, newGuestTransport) = LoopbackTransport.createPair()
            guest.reconnect(newGuestTransport)
            val resume = ProtocolCodec.decodeGuestToHost(newHostTransport.incoming.first()) as GuestToHost.Resume
            host.acceptResume(newHostTransport, resume)

            assertEquals(roundBefore + 1, roundNumber(host), "a stale pending seq must not be re-applied")
            assertEquals(roundBefore + 1, roundNumber(guest))
        }

    @Test
    fun `hasPendingIntent tracks a submitted guest intent, and a second submit while pending sends no second frame`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hostTransportRaw, guestTransport) = LoopbackTransport.createPair()
            val recordingHostTransport = RecordingTransport(hostTransportRaw)
            val host = HostMatchSession(sixCardDeck(), MatchConfig("resilience-deck"), Random(1), recordingHostTransport, backgroundScope, TOKEN, "Opponent")
            val guest = GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Opponent")
            resolveOneRound(host, guest)
            val roundBefore = roundNumber(host)
            assertFalse(guest.hasPendingIntent.value)

            // Blocks the guest from hearing the resolving view, which would otherwise clear the
            // flag — with UnconfinedTestDispatcher the whole round trip would settle synchronously
            // inside submit() and there'd be nothing to observe as "pending".
            guestTransport.dropConnection()
            guest.submit(PlayerIntent.AdvanceRound)
            assertTrue(guest.hasPendingIntent.value, "submit() should mark an intent pending immediately")

            fun advanceRoundFramesSent() = recordingHostTransport.frames.count { ProtocolCodec.decodeGuestToHost(it) is GuestToHost.AdvanceRound }
            val sentAfterFirst = advanceRoundFramesSent()
            guest.submit(PlayerIntent.AdvanceRound) // an intent is already in flight — must be a no-op
            assertEquals(sentAfterFirst, advanceRoundFramesSent(), "a second submit while pending must not send a second frame")
            assertEquals(roundBefore + 1, roundNumber(host), "the host should already have applied the one AdvanceRound it did receive")
        }

    @Test
    fun `a resume with an unrecognised session token is rejected`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(sixCardDeck(), MatchConfig("resilience-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Opponent")
        GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Opponent")

        val (newHostTransport, newGuestTransport) = LoopbackTransport.createPair()
        newGuestTransport.send(ProtocolCodec.encodeGuestToHost(GuestToHost.Resume("not-the-real-token", null)))
        val resume = ProtocolCodec.decodeGuestToHost(newHostTransport.incoming.first()) as GuestToHost.Resume
        host.acceptResume(newHostTransport, resume)

        val rejected = ProtocolCodec.decodeHostToGuest(newGuestTransport.incoming.first())
        assertTrue(rejected is HostToGuest.ResumeRejected)
        assertEquals(ConnectionState.Connected, host.connectionState.value, "the original session is untouched by a rejected resume attempt")
    }

    @Test
    fun `a resume arriving after the window has already expired is rejected`() = runTest(UnconfinedTestDispatcher()) {
        val (hostTransport, guestTransport) = LoopbackTransport.createPair()
        val host = HostMatchSession(sixCardDeck(), MatchConfig("resilience-deck"), Random(1), hostTransport, backgroundScope, TOKEN, "Opponent")
        GuestMatchSession(guestTransport, backgroundScope, TOKEN, "Opponent")

        hostTransport.dropConnection()
        advanceTimeBy(6.seconds)
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(ConnectionState.Abandoned(AbandonReason.GRACE_EXPIRED), host.connectionState.value)

        val (newHostTransport, newGuestTransport) = LoopbackTransport.createPair()
        newGuestTransport.send(ProtocolCodec.encodeGuestToHost(GuestToHost.Resume(TOKEN, null)))
        val resume = ProtocolCodec.decodeGuestToHost(newHostTransport.incoming.first()) as GuestToHost.Resume
        host.acceptResume(newHostTransport, resume)

        val rejected = ProtocolCodec.decodeHostToGuest(newGuestTransport.incoming.first())
        assertTrue(rejected is HostToGuest.ResumeRejected)
        assertEquals(ConnectionState.Abandoned(AbandonReason.GRACE_EXPIRED), host.connectionState.value)
    }
}
