package com.toptrumps.feature.history

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** A card that won a round for the recording player — [MatchHistoryRepository.recordMatch]'s input shape for [MatchCardWinEntity]. */
public data class CardWin(val cardId: String, val cardName: String)

/**
 * [MatchHistoryRepository.matches]' row shape — a flattened, UI-ready view of [MatchEntity]. Room
 * projects [HistoryDao]'s query straight into this (and the three row types below) rather than a
 * private intermediate type: the column names already match these constructors' parameter names,
 * so a second layer of types would exist only to be `map`ped away.
 */
public data class MatchRecord(
    val id: Long,
    val timestampEpochMillis: Long,
    val deckName: String,
    val opponentName: String,
    val outcome: Outcome,
    val myScore: Int,
    val opponentScore: Int,
)

/** [MatchHistoryRepository.headToHead]'s row shape. */
public data class HeadToHeadRecord(val opponentName: String, val wins: Int, val losses: Int, val draws: Int) {
    public val played: Int get() = wins + losses + draws
}

/** [MatchHistoryRepository.overallRecord]'s shape. `winRate` is `null` with no matches played yet, rather than a misleading 0%. */
public data class OverallRecord(val wins: Int, val losses: Int, val draws: Int) {
    public val played: Int get() = wins + losses + draws
    public val winRate: Double? get() = if (played == 0) null else wins.toDouble() / played
}

/** [MatchHistoryRepository.mostWonCards]'s row shape, pre-ordered by [winCount] descending. */
public data class CardWinCount(val cardId: String, val cardName: String, val winCount: Int)

/**
 * The whole of `:feature:history`'s public surface — deliberately no `:core:rules`/`:core:session`
 * type anywhere in this file's signatures, so this module stays deletable without touching
 * `:core:*` (the WBS's own test of the collector-not-dependency design). `:app` is the glue that
 * translates a `RecordedMatch` into the primitives [recordMatch] takes.
 */
public class MatchHistoryRepository(private val database: HistoryDatabase) {
    private val dao = database.historyDao()

    /** Past matches, most recent first (story: "the history list shows past matches, most recent first"). */
    public val matches: Flow<List<MatchRecord>> = dao.observeMatches()

    public val headToHead: Flow<List<HeadToHeadRecord>> = dao.observeHeadToHead()

    public val overallRecord: Flow<OverallRecord> = dao.observeOverallRecord()

    public val mostWonCards: Flow<List<CardWinCount>> = dao.observeMostWonCards()

    /**
     * Inserts the match row and its cards-won rows in one transaction — a match with a row but no
     * card rows (or vice versa) should never be observable mid-write. `cardsWon` empty is legal
     * (a match lost every round) and just skips the second insert.
     */
    public suspend fun recordMatch(
        timestampEpochMillis: Long,
        deckId: String,
        deckName: String,
        opponentName: String,
        outcome: Outcome,
        myScore: Int,
        opponentScore: Int,
        cardsWon: List<CardWin>,
    ) {
        database.withTransaction {
            val matchId = dao.insertMatch(
                MatchEntity(
                    timestampEpochMillis = timestampEpochMillis,
                    deckId = deckId,
                    deckName = deckName,
                    opponentName = opponentName,
                    outcome = outcome,
                    myScore = myScore,
                    opponentScore = opponentScore,
                ),
            )
            if (cardsWon.isNotEmpty()) {
                dao.insertCardWins(
                    cardsWon.map { MatchCardWinEntity(matchId = matchId, deckId = deckId, cardId = it.cardId, cardName = it.cardName) },
                )
            }
        }
    }
}
