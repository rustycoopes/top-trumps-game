package com.toptrumps.feature.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** A completed match's outcome, from the recording device's own perspective — see [MatchEntity.outcome]. */
public enum class Outcome { WIN, LOSS, DRAW }

/**
 * One row per completed match. Never written for an abandoned or quit match — the collector in
 * `:app` only calls [MatchHistoryRepository.recordMatch] when [com.toptrumps.session.MatchSession.completedMatch]
 * actually emits, which it structurally cannot do for anything but a genuine finish.
 */
// "match_history", not "match" — the latter collides with SQLite's own MATCH operator keyword.
@Entity(tableName = "match_history")
public data class MatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMillis: Long,
    val deckId: String,
    val deckName: String,
    val opponentName: String,
    val outcome: Outcome,
    val myScore: Int,
    val opponentScore: Int,
)

/**
 * One row per card that won a round for the recording player — [MatchHistoryRepository.CardWin],
 * not every card that ended up in the winning pile (the round winner also keeps the *opponent's*
 * card, per the PRD: "cards won" means the card that did the winning). [cardName] is denormalized
 * rather than looked up from `:core:decks` at query time — this module shares nothing with the
 * engine or deck storage (the WBS's "independent vertical slice"). [deckId] is denormalized too,
 * onto every row rather than joined from the parent — [cardId] is only unique *within* a deck (see
 * `:core:decks`' manifest format), so a future second deck reusing an id like `"card-01"` must not
 * silently merge its tally into an unrelated card's.
 */
@Entity(
    tableName = "match_card_win",
    primaryKeys = ["matchId", "cardId"],
    foreignKeys = [
        ForeignKey(entity = MatchEntity::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE),
    ],
    // No extra index on `matchId` — the composite primary key above already is one, `matchId`-leading.
)
public data class MatchCardWinEntity(
    val matchId: Long,
    val deckId: String,
    val cardId: String,
    val cardName: String,
)
