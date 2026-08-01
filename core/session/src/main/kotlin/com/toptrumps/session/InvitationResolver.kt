package com.toptrumps.session

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Assigned once an invitation resolves. Whoever dialled is not necessarily host — TDD §5. */
public enum class Role { HOST, GUEST }

/** State of the local device's one invitation in flight. Only one at a time — a second inbound invite while busy gets [DeclineReason.BUSY]. */
public sealed interface InvitationState {
    public data object Idle : InvitationState
    public data class OutboundPending(public val peer: LobbyPeer, public val transport: Transport, public val sentAt: Instant) :
        InvitationState
    public data class InboundPrompt(public val peer: LobbyPeer, public val transport: Transport) : InvitationState
    public data class Connected(public val peer: LobbyPeer, public val role: Role, public val transport: Transport) :
        InvitationState
}

public sealed interface InvitationEvent {
    /** The user tapped a peer to invite; [transport] is already dialled and connected. */
    public data class TapInvite(public val peer: LobbyPeer, public val transport: Transport, public val now: Instant) :
        InvitationEvent

    /** The user cancelled their own pending outbound invite. */
    public data object CancelOutbound : InvitationEvent

    /** A periodic tick, used only to expire an [InvitationState.OutboundPending] older than the timeout. */
    public data class Tick(public val now: Instant) : InvitationEvent

    /** An `Invite` arrived on [transport] — a fresh inbound connection, or the peer of a mutual tap. */
    public data class InboundInviteArrived(public val peer: LobbyPeer, public val transport: Transport) : InvitationEvent

    /** The user accepted the current [InvitationState.InboundPrompt]. */
    public data object AcceptInbound : InvitationEvent

    /** The user declined the current [InvitationState.InboundPrompt]. */
    public data object DeclineInbound : InvitationEvent

    /** `InviteAccept` arrived on our own [InvitationState.OutboundPending] transport. */
    public data object OutboundAccepted : InvitationEvent

    /** `InviteDecline` arrived on our own [InvitationState.OutboundPending] transport. */
    public data class OutboundDeclined(public val reason: DeclineReason) : InvitationEvent

    /** `InviteCancel` arrived — or the transport closed — while showing an [InvitationState.InboundPrompt]. */
    public data object InboundCancelled : InvitationEvent

    /** The user left a connected session (this slice's "connected socket and a screen that says so" has no match yet to leave instead). */
    public data object Leave : InvitationEvent
}

/** Side effects [InvitationResolver] asks the caller to perform; the resolver itself never touches a [Transport]. */
public sealed interface InvitationEffect {
    public data class Send(public val transport: Transport, public val message: LobbyMessage) : InvitationEffect
    public data class CloseTransport(public val transport: Transport) : InvitationEffect
}

public data class InvitationResult(public val state: InvitationState, public val effects: List<InvitationEffect>)

/**
 * Pure lobby invitation state machine — TDD §12. The PRD's UUID-comparison rule for simultaneous
 * mutual invitations is sound but incomplete on its own: this resolves the three corollaries
 * explicitly — the losing socket is closed with a courtesy `InviteCancel` rather than a bare
 * close, resolution is receive-side (triggered by an *inbound* invite arriving while an outbound
 * one is pending, since true simultaneity is rare), and a third caller gets [DeclineReason.BUSY]
 * instead of a silent timeout.
 */
public object InvitationResolver {

    public val PENDING_TIMEOUT: Duration = 30.seconds

    /**
     * [selfInstanceId] and [selfDisplayName] identify the local device — the former for the
     * mutual-invitation tiebreak, the latter to populate an outbound [LobbyMessage.Invite].
     */
    public fun resolve(
        state: InvitationState,
        event: InvitationEvent,
        selfInstanceId: String,
        selfDisplayName: String,
    ): InvitationResult =
        when (event) {
            is InvitationEvent.TapInvite -> onTapInvite(state, event, selfInstanceId, selfDisplayName)
            is InvitationEvent.CancelOutbound -> onCancelOutbound(state)
            is InvitationEvent.Tick -> onTick(state, event)
            is InvitationEvent.InboundInviteArrived -> onInboundInviteArrived(state, event, selfInstanceId)
            is InvitationEvent.AcceptInbound -> onAcceptInbound(state)
            is InvitationEvent.DeclineInbound -> onDeclineInbound(state)
            is InvitationEvent.OutboundAccepted -> onOutboundAccepted(state)
            is InvitationEvent.OutboundDeclined -> onOutboundDeclined(state)
            is InvitationEvent.InboundCancelled -> onInboundCancelled(state)
            is InvitationEvent.Leave -> onLeave(state)
        }

    private fun onTapInvite(
        state: InvitationState,
        event: InvitationEvent.TapInvite,
        selfInstanceId: String,
        selfDisplayName: String,
    ): InvitationResult =
        if (state is InvitationState.Idle) {
            InvitationResult(
                state = InvitationState.OutboundPending(event.peer, event.transport, event.now),
                effects = listOf(
                    InvitationEffect.Send(event.transport, LobbyMessage.Invite(selfDisplayName, selfInstanceId)),
                ),
            )
        } else {
            unchanged(state)
        }

    private fun onCancelOutbound(state: InvitationState): InvitationResult =
        if (state is InvitationState.OutboundPending) {
            InvitationResult(
                state = InvitationState.Idle,
                effects = listOf(
                    InvitationEffect.Send(state.transport, LobbyMessage.InviteCancel),
                    InvitationEffect.CloseTransport(state.transport),
                ),
            )
        } else {
            unchanged(state)
        }

    private fun onTick(state: InvitationState, event: InvitationEvent.Tick): InvitationResult =
        if (state is InvitationState.OutboundPending && event.now - state.sentAt >= PENDING_TIMEOUT) {
            InvitationResult(
                state = InvitationState.Idle,
                effects = listOf(
                    InvitationEffect.Send(state.transport, LobbyMessage.InviteCancel),
                    InvitationEffect.CloseTransport(state.transport),
                ),
            )
        } else {
            unchanged(state)
        }

    /**
     * The one case the TDD requires a test named for: an outbound pending invite to peer X, then
     * an inbound invite from X arrives — resolve by UUID and transition straight to
     * [InvitationState.Connected], skipping [InvitationState.InboundPrompt] entirely. Lower
     * instance id keeps its own outbound socket and becomes host; the higher id concedes its
     * outbound attempt and keeps the inbound socket as guest. Both sides compute this
     * independently and reach a consistent answer with no extra message.
     */
    private fun onInboundInviteArrived(
        state: InvitationState,
        event: InvitationEvent.InboundInviteArrived,
        selfInstanceId: String,
    ): InvitationResult = when {
        state is InvitationState.OutboundPending && state.peer.instanceId == event.peer.instanceId ->
            if (selfInstanceId < event.peer.instanceId) {
                InvitationResult(
                    state = InvitationState.Connected(event.peer, Role.HOST, state.transport),
                    effects = listOf(
                        InvitationEffect.Send(event.transport, LobbyMessage.InviteCancel),
                        InvitationEffect.CloseTransport(event.transport),
                    ),
                )
            } else {
                InvitationResult(
                    state = InvitationState.Connected(event.peer, Role.GUEST, event.transport),
                    effects = listOf(
                        InvitationEffect.Send(state.transport, LobbyMessage.InviteCancel),
                        InvitationEffect.CloseTransport(state.transport),
                    ),
                )
            }
        state is InvitationState.Idle ->
            InvitationResult(InvitationState.InboundPrompt(event.peer, event.transport), emptyList())
        else ->
            InvitationResult(
                state = state,
                effects = listOf(
                    InvitationEffect.Send(event.transport, LobbyMessage.InviteDecline(DeclineReason.BUSY)),
                    InvitationEffect.CloseTransport(event.transport),
                ),
            )
    }

    private fun onAcceptInbound(state: InvitationState): InvitationResult =
        if (state is InvitationState.InboundPrompt) {
            InvitationResult(
                state = InvitationState.Connected(state.peer, Role.GUEST, state.transport),
                effects = listOf(InvitationEffect.Send(state.transport, LobbyMessage.InviteAccept)),
            )
        } else {
            unchanged(state)
        }

    private fun onDeclineInbound(state: InvitationState): InvitationResult =
        if (state is InvitationState.InboundPrompt) {
            InvitationResult(
                state = InvitationState.Idle,
                effects = listOf(
                    InvitationEffect.Send(state.transport, LobbyMessage.InviteDecline(DeclineReason.USER_DECLINED)),
                    InvitationEffect.CloseTransport(state.transport),
                ),
            )
        } else {
            unchanged(state)
        }

    private fun onOutboundAccepted(state: InvitationState): InvitationResult =
        if (state is InvitationState.OutboundPending) {
            InvitationResult(InvitationState.Connected(state.peer, Role.HOST, state.transport), emptyList())
        } else {
            unchanged(state)
        }

    private fun onOutboundDeclined(state: InvitationState): InvitationResult =
        if (state is InvitationState.OutboundPending) {
            InvitationResult(InvitationState.Idle, listOf(InvitationEffect.CloseTransport(state.transport)))
        } else {
            unchanged(state)
        }

    private fun onInboundCancelled(state: InvitationState): InvitationResult =
        if (state is InvitationState.InboundPrompt) {
            InvitationResult(InvitationState.Idle, listOf(InvitationEffect.CloseTransport(state.transport)))
        } else {
            unchanged(state)
        }

    private fun onLeave(state: InvitationState): InvitationResult =
        if (state is InvitationState.Connected) {
            InvitationResult(InvitationState.Idle, listOf(InvitationEffect.CloseTransport(state.transport)))
        } else {
            unchanged(state)
        }

    private fun unchanged(state: InvitationState): InvitationResult = InvitationResult(state, emptyList())
}
