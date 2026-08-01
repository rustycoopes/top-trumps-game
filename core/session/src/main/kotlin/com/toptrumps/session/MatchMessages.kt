package com.toptrumps.session

import com.toptrumps.rules.MatchConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Bumped whenever a wire-incompatible change lands. Checked with strict equality and a hard
 * refusal — there is no server to be out of step with, and the real scenario is "one phone
 * auto-updated before the other" (TDD §5).
 *
 * 2: slice 6 — heartbeats, sequence-numbered guest intents and the resume handshake.
 */
public const val PROTOCOL_VERSION: Int = 2

/** A wire-serializable mirror of [MatchConfig] — travels in [HostToGuest.DeckChosen] so the guest can render the all-metrics-tie explanation without ever running [com.toptrumps.rules.RulesEngine]. */
@Serializable
public data class WireMatchConfig(val deckId: String, val allMetricsTieFallback: String)

public fun MatchConfig.toWire(): WireMatchConfig = WireMatchConfig(deckId, allMetricsTieFallback.name)

/** Why a match ended without a winner being decided — see the reconnect-resync ADR's host/guest-drop asymmetry note. */
@Serializable
public enum class AbandonReason {
    /** The 60s countdown (heartbeat.md's "3 misses ≈ 6s, then 60s window") ran out with no reconnect. */
    GRACE_EXPIRED,

    /** The peer sent a deliberate [GuestToHost.Leave]/host-side quit — surfaced as "abandoned" only when
     * that happens before a match has a winner; a mid-round quit has nowhere else to go. */
    PEER_QUIT,
}

/**
 * Every message the guest may ever send, and the *only* ones — this hierarchy, not convention, is
 * what makes the guest a thin client (TDD §5). [ChooseMetric] and [AdvanceRound] are the guest's
 * only gameplay messages, each carrying a monotonic [seq] per the reconnect-resync ADR's
 * at-most-one-intent-in-flight invariant; [Hello] and [DeckMismatch] are the two handshake gates;
 * [Leave] is a deliberate quit's courtesy notice; [Heartbeat] is sent only when [seq]-bearing
 * traffic hasn't gone out in the last heartbeat interval; [Resume] re-establishes a session on a
 * freshly dialled [Transport] after a drop.
 */
@Serializable
public sealed interface GuestToHost {
    @Serializable
    @SerialName("hello")
    public data class Hello(val protocolVersion: Int, val displayName: String, val instanceId: String) : GuestToHost

    @Serializable
    @SerialName("deckMismatch")
    public data class DeckMismatch(val deckId: String) : GuestToHost

    @Serializable
    @SerialName("chooseMetric")
    public data class ChooseMetric(val metricKey: String, val seq: Long) : GuestToHost

    @Serializable
    @SerialName("advanceRound")
    public data class AdvanceRound(val seq: Long) : GuestToHost

    @Serializable
    @SerialName("leave")
    public data class Leave(val deliberate: Boolean) : GuestToHost

    @Serializable
    @SerialName("heartbeat")
    public data object Heartbeat : GuestToHost

    /**
     * Re-establishes a session on a freshly dialled [Transport], carrying the token from the
     * original [HostToGuest.HelloAck] and, if the guest never learned whether its last
     * [ChooseMetric]/[AdvanceRound] was received, that same message again — [pendingIntent] is
     * always either `null`, a [ChooseMetric] or an [AdvanceRound]; nothing else is a legitimate
     * value, mirroring this file's existing informal per-field invariants (e.g. [MatchStart]'s
     * "discrete trigger" note) rather than a third sealed layer for a two-case restriction.
     */
    @Serializable
    @SerialName("resume")
    public data class Resume(val sessionToken: String, val pendingIntent: GuestToHost?) : GuestToHost
}

/**
 * Every message the host may ever send. Separate from [GuestToHost] so it is a compile error for
 * either side to construct the other's messages (TDD §5).
 */
@Serializable
public sealed interface HostToGuest {
    /** Issues the token [GuestToHost.Resume] reads back after a drop. */
    @Serializable
    @SerialName("helloAck")
    public data class HelloAck(val sessionToken: String) : HostToGuest

    @Serializable
    @SerialName("versionMismatch")
    public data class VersionMismatch(val hostVersion: Int) : HostToGuest

    @Serializable
    @SerialName("deckChosen")
    public data class DeckChosen(val deckId: String, val deckHash: String, val config: WireMatchConfig) : HostToGuest

    /** Discrete, so the deal animation (slice 7) has an unambiguous trigger — a resolved [View] alone can't distinguish "just dealt" from "just resynced". */
    @Serializable
    @SerialName("matchStart")
    public data class MatchStart(val yourHand: List<RemoteCardFace>, val roundCount: Int) : HostToGuest

    /** Subsumes every in-round transition — a resolved round's projected view already carries every UX moment the PRD lists separately. */
    @Serializable
    @SerialName("view")
    public data class View(val view: MatchView) : HostToGuest

    @Serializable
    @SerialName("heartbeat")
    public data object Heartbeat : HostToGuest

    /**
     * Replies to a [GuestToHost.Resume]. [resync] is always `true` today (a hard-cut render
     * rather than an animated replay — slice 7's job to make use of) but is carried on the wire
     * now rather than added as a breaking change later, the same forward-looking-field pattern as
     * [HelloAck]'s token was before this slice read it back.
     */
    @Serializable
    @SerialName("resumeAck")
    public data class ResumeAck(val view: MatchView, val resync: Boolean) : HostToGuest

    @Serializable
    @SerialName("resumeRejected")
    public data class ResumeRejected(val reason: String) : HostToGuest

    @Serializable
    @SerialName("abandoned")
    public data class Abandoned(val reason: AbandonReason) : HostToGuest
}
