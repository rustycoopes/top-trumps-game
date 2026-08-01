package com.toptrumps.rules

/** How a completed match looks from the perspective of the seat that's recording it. */
public enum class MatchResult { WIN, LOSS, DRAW }

/**
 * A completed match, viewer-relative — the value `:core:rules` "emits" at match end (TDD §11).
 * Built (by `:core:session`, from a `MatchView.Finished` either seat has by construction — a
 * host's own view or a guest's wire copy) rather than from [MatchState] directly, since the
 * guest never holds one. [cardsWon] is [MatchState.cardsWonWith] — the cards that *won a round*
 * for this seat, not [PlayerView.Finished.myPile] (which also holds every card captured off the
 * opponent by winning with something else). That distinction is the whole point of "most-won
 * cards": crediting the pile instead would count a weak card every time a *different* card of
 * yours beats it, which runs backwards from the PRD's "so that I learn the deck".
 */
public data class MatchSummary(
    val result: MatchResult,
    val myScore: Int,
    val opponentScore: Int,
    val cardsWon: List<CardFace>,
)
