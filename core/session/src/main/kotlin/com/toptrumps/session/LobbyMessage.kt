package com.toptrumps.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `BUSY` is what a third device gets instead of a silent timeout — TDD §12. */
@Serializable
public enum class DeclineReason { USER_DECLINED, BUSY }

/**
 * Lobby-phase messages, exchanged over a freshly dialled or accepted TCP connection before any
 * match — or host/guest role — exists. Unlike the in-match `GuestToHost`/`HostToGuest` split,
 * this hierarchy is symmetric: either end of a lobby socket may originate any of these.
 */
@Serializable
public sealed interface LobbyMessage {
    @Serializable
    @SerialName("invite")
    public data class Invite(val fromDisplayName: String, val fromInstanceId: String) : LobbyMessage

    @Serializable
    @SerialName("inviteAccept")
    public data object InviteAccept : LobbyMessage

    @Serializable
    @SerialName("inviteDecline")
    public data class InviteDecline(val reason: DeclineReason) : LobbyMessage

    @Serializable
    @SerialName("inviteCancel")
    public data object InviteCancel : LobbyMessage
}
