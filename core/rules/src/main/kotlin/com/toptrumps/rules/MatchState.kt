package com.toptrumps.rules

/** The final tally, set once every card has been dealt into a pile. */
public data class MatchOutcome(
    /** `null` only reachable under [TieFallback.EACH_KEEPS_OWN] — see the all-metrics-tie ADR. */
    val winner: Seat?,
    val hostScore: Int,
    val guestScore: Int,
)

/**
 * The authoritative, never-serialized game state. [RulesEngine.deal] is its sole constructor;
 * every read — including the host's own UI — goes through [RulesEngine.project]. See the
 * structural-redaction ADR: this is what "the encoder for MatchState does not exist" means.
 */
@ConsistentCopyVisibility
public data class MatchState internal constructor(
    val deck: Deck,
    val config: MatchConfig,
    val hands: Map<Seat, List<Card>>,
    val piles: Map<Seat, List<Card>>,
    /**
     * A seat's own card from every round *it* won — distinct from [piles], which also holds the
     * opponent's card from that round (the round winner takes both, see [RulesEngine]'s
     * `applyAdvance`). This is what match history's "cards that have won you the most rounds"
     * (slice 8) actually means: the card that did the winning, not everything a seat ended up
     * holding. Empty for a round [TieFallback.EACH_KEEPS_OWN] draws, since nobody's card won it.
     */
    val cardsWonWith: Map<Seat, List<Card>>,
    val roundNumber: Int,
    /** Fixed at deal time: the number of cards each hand started with. */
    val totalRounds: Int,
    val round: RoundState,
    /** `null` while the match is in progress. */
    val outcome: MatchOutcome?,
    val revision: Long,
)
