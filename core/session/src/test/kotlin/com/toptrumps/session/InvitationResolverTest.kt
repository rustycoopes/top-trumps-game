package com.toptrumps.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

private class FakeTransport(private val label: String) : Transport {
    override val incoming: Flow<ByteArray> = emptyFlow()
    override suspend fun send(bytes: ByteArray) = Unit
    override fun close() = Unit
    override fun toString(): String = "FakeTransport($label)"
}

private val epoch = Instant.fromEpochSeconds(1_700_000_000)
private const val SELF_ID = "10000000"
private const val LOWER_PEER_ID = "05000000"
private const val HIGHER_PEER_ID = "90000000"
private val peerLower = LobbyPeer(LOWER_PEER_ID, "Lower", epoch)
private val peerHigher = LobbyPeer(HIGHER_PEER_ID, "Higher", epoch)

class InvitationResolverTest {

    @Test
    fun `tapping invite from idle sends an Invite and becomes outbound pending`() {
        val transport = FakeTransport("out")
        val result = InvitationResolver.resolve(
            InvitationState.Idle,
            InvitationEvent.TapInvite(peerLower, transport, epoch),
            SELF_ID,
            "Me",
        )
        assertEquals(InvitationState.OutboundPending(peerLower, transport, epoch), result.state)
        assertEquals(listOf(InvitationEffect.Send(transport, LobbyMessage.Invite("Me", SELF_ID))), result.effects)
    }

    @Test
    fun `cancelling an outbound pending invite sends InviteCancel and closes the socket`() {
        val transport = FakeTransport("out")
        val pending = InvitationState.OutboundPending(peerLower, transport, epoch)
        val result = InvitationResolver.resolve(pending, InvitationEvent.CancelOutbound, SELF_ID, "Me")
        assertEquals(InvitationState.Idle, result.state)
        assertEquals(
            listOf(InvitationEffect.Send(transport, LobbyMessage.InviteCancel), InvitationEffect.CloseTransport(transport)),
            result.effects,
        )
    }

    @Test
    fun `an outbound pending invite survives a tick before its timeout`() {
        val transport = FakeTransport("out")
        val pending = InvitationState.OutboundPending(peerLower, transport, epoch)
        val result = InvitationResolver.resolve(pending, InvitationEvent.Tick(epoch + 29.seconds), SELF_ID, "Me")
        assertEquals(pending, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `a pending invitation times out and is cancelled`() {
        val transport = FakeTransport("out")
        val pending = InvitationState.OutboundPending(peerLower, transport, epoch)
        val result = InvitationResolver.resolve(pending, InvitationEvent.Tick(epoch + 31.seconds), SELF_ID, "Me")
        assertEquals(InvitationState.Idle, result.state)
        assertEquals(
            listOf(InvitationEffect.Send(transport, LobbyMessage.InviteCancel), InvitationEffect.CloseTransport(transport)),
            result.effects,
        )
    }

    @Test
    fun `an inbound invite while idle shows a prompt with no effects`() {
        val transport = FakeTransport("in")
        val result = InvitationResolver.resolve(
            InvitationState.Idle,
            InvitationEvent.InboundInviteArrived(peerLower, transport),
            SELF_ID,
            "Me",
        )
        assertEquals(InvitationState.InboundPrompt(peerLower, transport), result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `accepting an inbound prompt sends InviteAccept and becomes the connected guest`() {
        val transport = FakeTransport("in")
        val prompt = InvitationState.InboundPrompt(peerLower, transport)
        val result = InvitationResolver.resolve(prompt, InvitationEvent.AcceptInbound, SELF_ID, "Me")
        assertEquals(InvitationState.Connected(peerLower, Role.GUEST, transport), result.state)
        assertEquals(listOf(InvitationEffect.Send(transport, LobbyMessage.InviteAccept)), result.effects)
    }

    @Test
    fun `declining an inbound prompt reports USER_DECLINED and closes the socket`() {
        val transport = FakeTransport("in")
        val prompt = InvitationState.InboundPrompt(peerLower, transport)
        val result = InvitationResolver.resolve(prompt, InvitationEvent.DeclineInbound, SELF_ID, "Me")
        assertEquals(InvitationState.Idle, result.state)
        assertEquals(
            listOf(
                InvitationEffect.Send(transport, LobbyMessage.InviteDecline(DeclineReason.USER_DECLINED)),
                InvitationEffect.CloseTransport(transport),
            ),
            result.effects,
        )
    }

    @Test
    fun `an inbound InviteCancel while prompting returns to idle`() {
        val transport = FakeTransport("in")
        val prompt = InvitationState.InboundPrompt(peerLower, transport)
        val result = InvitationResolver.resolve(prompt, InvitationEvent.InboundCancelled, SELF_ID, "Me")
        assertEquals(InvitationState.Idle, result.state)
        assertEquals(listOf(InvitationEffect.CloseTransport(transport)), result.effects)
    }

    @Test
    fun `the peer accepting our outbound invite makes us the host`() {
        val transport = FakeTransport("out")
        val pending = InvitationState.OutboundPending(peerLower, transport, epoch)
        val result = InvitationResolver.resolve(pending, InvitationEvent.OutboundAccepted, SELF_ID, "Me")
        assertEquals(InvitationState.Connected(peerLower, Role.HOST, transport), result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `the peer declining our outbound invite closes the socket and returns to idle`() {
        val transport = FakeTransport("out")
        val pending = InvitationState.OutboundPending(peerLower, transport, epoch)
        val result = InvitationResolver.resolve(
            pending,
            InvitationEvent.OutboundDeclined(DeclineReason.USER_DECLINED),
            SELF_ID,
            "Me",
        )
        assertEquals(InvitationState.Idle, result.state)
        assertEquals(listOf(InvitationEffect.CloseTransport(transport)), result.effects)
    }

    @Test
    fun `leaving a connected session closes the socket and returns to idle`() {
        val transport = FakeTransport("connected")
        val connected = InvitationState.Connected(peerLower, Role.HOST, transport)
        val result = InvitationResolver.resolve(connected, InvitationEvent.Leave, SELF_ID, "Me")
        assertEquals(InvitationState.Idle, result.state)
        assertEquals(listOf(InvitationEffect.CloseTransport(transport)), result.effects)
    }

    @Test
    fun `leaving while not connected is a no-op`() {
        val result = InvitationResolver.resolve(InvitationState.Idle, InvitationEvent.Leave, SELF_ID, "Me")
        assertEquals(InvitationState.Idle, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `a third device inviting a busy player receives BUSY and the busy state is untouched`() {
        val connectedTransport = FakeTransport("connected")
        val connected = InvitationState.Connected(peerLower, Role.HOST, connectedTransport)
        val thirdPartyTransport = FakeTransport("third")
        val thirdPeer = LobbyPeer("cccccccc", "Cara", epoch)

        val result = InvitationResolver.resolve(
            connected,
            InvitationEvent.InboundInviteArrived(thirdPeer, thirdPartyTransport),
            SELF_ID,
            "Me",
        )

        assertEquals(connected, result.state)
        assertEquals(
            listOf(
                InvitationEffect.Send(thirdPartyTransport, LobbyMessage.InviteDecline(DeclineReason.BUSY)),
                InvitationEffect.CloseTransport(thirdPartyTransport),
            ),
            result.effects,
        )
    }

    @Test
    fun `given an outbound pending invite to X, an inbound invite from X arrives, resolves by UUID and skips the prompt - we are host`() {
        // Our id (SELF_ID) is lower than the peer's, so we keep our own outbound socket and win host.
        val outbound = FakeTransport("outbound-to-higher-peer")
        val inbound = FakeTransport("inbound-from-higher-peer")
        val pending = InvitationState.OutboundPending(peerHigher, outbound, epoch)

        val result = InvitationResolver.resolve(
            pending,
            InvitationEvent.InboundInviteArrived(peerHigher, inbound),
            SELF_ID,
            "Me",
        )

        assertEquals(InvitationState.Connected(peerHigher, Role.HOST, outbound), result.state)
        assertEquals(
            listOf(InvitationEffect.Send(inbound, LobbyMessage.InviteCancel), InvitationEffect.CloseTransport(inbound)),
            result.effects,
        )
    }

    @Test
    fun `given an outbound pending invite to X, an inbound invite from X arrives, resolves by UUID and skips the prompt - we are guest`() {
        // Our id (SELF_ID) is higher than the peer's, so we concede our own outbound socket and become guest.
        val outbound = FakeTransport("outbound-to-lower-peer")
        val inbound = FakeTransport("inbound-from-lower-peer")
        val pending = InvitationState.OutboundPending(peerLower, outbound, epoch)

        val result = InvitationResolver.resolve(
            pending,
            InvitationEvent.InboundInviteArrived(peerLower, inbound),
            SELF_ID,
            "Me",
        )

        assertEquals(InvitationState.Connected(peerLower, Role.GUEST, inbound), result.state)
        assertEquals(
            listOf(InvitationEffect.Send(outbound, LobbyMessage.InviteCancel), InvitationEffect.CloseTransport(outbound)),
            result.effects,
        )
    }
}
