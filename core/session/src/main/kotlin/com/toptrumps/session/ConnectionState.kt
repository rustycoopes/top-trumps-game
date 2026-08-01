package com.toptrumps.session

/**
 * A [MatchSession]'s view of its peer's liveness — orthogonal to [MatchView], since a drop can
 * happen mid-handshake, mid-round or after the match has already finished. See the
 * foreground-service and reconnect-resync ADRs.
 */
public sealed interface ConnectionState {
    /** The peer is heard from within the last three 2s heartbeat intervals. */
    public data object Connected : ConnectionState

    /** Heard nothing from the peer for ~6s; [secondsRemaining] counts down to 0 before [Abandoned]. */
    public data class PeerUnreachable(val secondsRemaining: Int) : ConnectionState

    /** The countdown ran out with no reconnect, or the peer quit before the match had a winner. */
    public data class Abandoned(val reason: AbandonReason) : ConnectionState

    /** The peer left deliberately — distinguished from [Abandoned] so the UI reads "X left the game", not a countdown that already expired. */
    public data object PeerLeft : ConnectionState
}
