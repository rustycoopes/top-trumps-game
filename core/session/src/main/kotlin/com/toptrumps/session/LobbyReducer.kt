package com.toptrumps.session

import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

/** A peer as it appears in the lobby list — everything `onServiceFound` supplies with zero resolves. */
public data class LobbyPeer(
    public val instanceId: String,
    public val displayName: String,
    public val lastSeenAt: Instant,
)

/** Translated `NsdManager` callbacks. The instance name is still raw — [LobbyReducer] parses it. */
public sealed interface DiscoveryEvent {
    public data class ServiceFound(public val instanceName: String, public val at: Instant) : DiscoveryEvent
    public data class ServiceLost(public val instanceName: String) : DiscoveryEvent

    /** Drives TTL pruning. `onServiceLost` is unreliable, so this is the primary removal path — TDD §8. */
    public data class Tick(public val now: Instant) : DiscoveryEvent
}

/**
 * Parses and renders the mDNS instance name and reduces [DiscoveryEvent]s into a lobby peer
 * list — pure functions, no [android.net.nsd.NsdManager] in sight, per the instance-name ADR.
 */
public object LobbyReducer {

    /** `·` (U+00B7 MIDDLE DOT) separates the display name from the instance id, e.g. `Russ·a1b2c3d4`. */
    public const val NAME_SEPARATOR: Char = '·'

    /** mDNS instance names are UTF-8 and may be up to 63 bytes. */
    public const val MAX_INSTANCE_NAME_BYTES: Int = 63
    private const val INSTANCE_ID_LENGTH = 8
    private val SEPARATOR_BYTES = NAME_SEPARATOR.toString().toByteArray(Charsets.UTF_8).size
    private val DISPLAY_NAME_BUDGET_BYTES = MAX_INSTANCE_NAME_BYTES - SEPARATOR_BYTES - INSTANCE_ID_LENGTH
    private val PEER_TTL = 30.seconds

    public data class ParsedInstanceName(public val displayName: String, public val instanceId: String)

    /**
     * Strips the mDNS label separator and control characters, then truncates to
     * [DISPLAY_NAME_BUDGET_BYTES] — never the raw string length, since UTF-8 display names may
     * take more than one byte per character — leaving room for the separator and instance id so
     * the combined instance name stays within [MAX_INSTANCE_NAME_BYTES].
     */
    public fun sanitiseDisplayName(raw: String): String {
        val cleaned = raw.filterNot { it == NAME_SEPARATOR || it == '.' || it.isISOControl() }
        return truncateToUtf8Bytes(cleaned, DISPLAY_NAME_BUDGET_BYTES)
    }

    /** Combines a sanitised display name and an 8-hex-char instance id into a registerable mDNS instance name. */
    public fun buildInstanceName(displayName: String, instanceId: String): String =
        "${sanitiseDisplayName(displayName)}$NAME_SEPARATOR$instanceId"

    /**
     * Reads the instance id from the 8 characters immediately following the *last* [NAME_SEPARATOR],
     * ignoring anything after them. That tolerance is deliberate: Android's auto-rename on
     * collision appends suffix text (` (2)`) to the whole service name, and the ADR is explicit
     * that self-filtering must survive that rather than break on it. Returns `null` for anything
     * that doesn't fit the shape — including our own service, still under its unrenamed name,
     * which is filtered separately by [reduce] comparing instance ids.
     */
    public fun parseInstanceName(raw: String): ParsedInstanceName? {
        val separatorIndex = raw.lastIndexOf(NAME_SEPARATOR)
        if (separatorIndex <= 0 || separatorIndex + 1 + INSTANCE_ID_LENGTH > raw.length) return null
        val candidateId = raw.substring(separatorIndex + 1, separatorIndex + 1 + INSTANCE_ID_LENGTH)
        if (candidateId.any { !it.isHexDigit() }) return null
        return ParsedInstanceName(
            displayName = raw.substring(0, separatorIndex),
            instanceId = candidateId.lowercase(),
        )
    }

    /**
     * Folds one [DiscoveryEvent] into the current peer list. Self-filtering compares the parsed
     * instance id against [selfInstanceId] rather than the raw name, so it survives auto-rename —
     * a peer never sees itself in its own list even after a collision.
     */
    public fun reduce(peers: List<LobbyPeer>, event: DiscoveryEvent, selfInstanceId: String): List<LobbyPeer> =
        when (event) {
            is DiscoveryEvent.ServiceFound -> {
                val parsed = parseInstanceName(event.instanceName)
                if (parsed == null || parsed.instanceId == selfInstanceId) {
                    peers
                } else {
                    peers.filterNot { it.instanceId == parsed.instanceId } +
                        LobbyPeer(parsed.instanceId, parsed.displayName, event.at)
                }
            }
            is DiscoveryEvent.ServiceLost -> {
                val parsed = parseInstanceName(event.instanceName)
                if (parsed == null) peers else peers.filterNot { it.instanceId == parsed.instanceId }
            }
            is DiscoveryEvent.Tick -> peers.filter { event.now - it.lastSeenAt < PEER_TTL }
        }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun truncateToUtf8Bytes(value: String, maxBytes: Int): String {
        var candidate = value
        while (candidate.isNotEmpty() && candidate.toByteArray(Charsets.UTF_8).size > maxBytes) {
            candidate = candidate.dropLast(1)
        }
        return candidate
    }
}
