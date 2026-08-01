package com.toptrumps.feature.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// internal, not public: MatchHistoryRepository's own KDoc claims to be "the whole of
// :feature:history's public surface" — true only if this interface (reachable otherwise via
// HistoryDatabase.historyDao()) isn't independently public. room.generateKotlin=true makes an
// internal @Dao interface no different to Room's processor than a public one.
@Dao
internal interface HistoryDao {

    @Insert
    suspend fun insertMatch(match: MatchEntity): Long

    @Insert
    suspend fun insertCardWins(wins: List<MatchCardWinEntity>)

    // Room projects a query straight into any data class whose constructor parameters match the
    // selected columns/aliases — MatchRecord/HeadToHeadRecord/OverallRecord/CardWinCount (all
    // public, in MatchHistoryRepository.kt) are used directly below rather than duplicating them
    // as private row types the repository would just map away.

    // Explicit columns, not `SELECT *` — MatchRecord doesn't carry deckId (nothing reads it yet;
    // deckName already identifies the deck for display). `, id DESC` breaks a same-millisecond tie
    // deterministically (two matches finishing in the same clock-resolution instant is real on a
    // fast device).
    @Query(
        """
        SELECT id, timestampEpochMillis, deckName, opponentName, outcome, myScore, opponentScore
        FROM match_history
        ORDER BY timestampEpochMillis DESC, id DESC
        """,
    )
    fun observeMatches(): Flow<List<MatchRecord>>

    @Query(
        """
        SELECT opponentName,
               SUM(CASE WHEN outcome = 'WIN' THEN 1 ELSE 0 END) AS wins,
               SUM(CASE WHEN outcome = 'LOSS' THEN 1 ELSE 0 END) AS losses,
               SUM(CASE WHEN outcome = 'DRAW' THEN 1 ELSE 0 END) AS draws
        FROM match_history
        GROUP BY opponentName
        ORDER BY opponentName COLLATE NOCASE
        """,
    )
    fun observeHeadToHead(): Flow<List<HeadToHeadRecord>>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN outcome = 'WIN' THEN 1 ELSE 0 END), 0) AS wins,
               COALESCE(SUM(CASE WHEN outcome = 'LOSS' THEN 1 ELSE 0 END), 0) AS losses,
               COALESCE(SUM(CASE WHEN outcome = 'DRAW' THEN 1 ELSE 0 END), 0) AS draws
        FROM match_history
        """,
    )
    fun observeOverallRecord(): Flow<OverallRecord>

    // The ADR's whole reason to choose Room: this is the one query that would otherwise be a
    // hand-folded, in-memory GROUP BY over every match ever played. Grouped by (deckId, cardId),
    // not cardId alone — card ids are only unique within a deck (see MatchCardWinEntity's doc), so
    // a second deck reusing an id must not merge into an unrelated card's tally. MAX(cardName)
    // rather than a bare column keeps the displayed name deterministic if a manifest ever renames
    // a card between matches.
    @Query(
        """
        SELECT cardId, MAX(cardName) AS cardName, COUNT(*) AS winCount
        FROM match_card_win
        GROUP BY deckId, cardId
        ORDER BY winCount DESC, cardName COLLATE NOCASE
        """,
    )
    fun observeMostWonCards(): Flow<List<CardWinCount>>
}
