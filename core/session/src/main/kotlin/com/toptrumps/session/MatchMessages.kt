package com.toptrumps.session

import com.toptrumps.rules.MatchConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Bumped whenever a wire-incompatible change lands. Checked with strict equality and a hard
 * refusal — there is no server to be out of step with, and the real scenario is "one phone
 * auto-updated before the other" (TDD §5).
 */
public const val PROTOCOL_VERSION: Int = 1

/** A wire-serializable mirror of [MatchConfig] — travels in [HostToGuest.DeckChosen] so the guest can render the all-metrics-tie explanation without ever running [com.toptrumps.rules.RulesEngine]. */
@Serializable
public data class WireMatchConfig(val deckId: String, val allMetricsTieFallback: String)

public fun MatchConfig.toWire(): WireMatchConfig = WireMatchConfig(deckId, allMetricsTieFallback.name)

/**
 * Every message the guest may ever send, and the *only* ones — this hierarchy, not convention, is
 * what makes the guest a thin client (TDD §5). [ChooseMetric] and [AdvanceRound] are the guest's
 * only gameplay messages; [Hello] and [DeckMismatch] are the two handshake gates; [Leave] is the
 * one lifecycle message defined so far — a deliberate quit's courtesy notice is slice 6's job to
 * wire up (foreground-service teardown and the heartbeat need to land together with it).
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
    public data class ChooseMetric(val metricKey: String) : GuestToHost

    @Serializable
    @SerialName("advanceRound")
    public data object AdvanceRound : GuestToHost

    @Serializable
    @SerialName("leave")
    public data class Leave(val deliberate: Boolean) : GuestToHost
}

/**
 * Every message the host may ever send. Separate from [GuestToHost] so it is a compile error for
 * either side to construct the other's messages (TDD §5).
 */
@Serializable
public sealed interface HostToGuest {
    /** Issues a resume token — unused until slice 6's reconnection resync reads it back via `Resume`. */
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

    /** `reason` is a string rather than an enum for now — slice 6 owns distinguishing `GRACE_EXPIRED` from `PEER_QUIT`. */
    @Serializable
    @SerialName("abandoned")
    public data class Abandoned(val reason: String) : HostToGuest
}
