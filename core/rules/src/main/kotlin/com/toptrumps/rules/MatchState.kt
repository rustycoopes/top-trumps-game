package com.toptrumps.rules

/**
 * The authoritative, never-serialized game state. [RulesEngine.deal] is its sole constructor;
 * every read — including the host's own UI — goes through [RulesEngine.project]. See the
 * structural-redaction ADR: this is what "the encoder for MatchState does not exist" means.
 */
@ConsistentCopyVisibility
public data class MatchState internal constructor(
    val deck: Deck,
    val hands: Map<Seat, List<Card>>,
    val round: RoundState,
    val revision: Long,
)
