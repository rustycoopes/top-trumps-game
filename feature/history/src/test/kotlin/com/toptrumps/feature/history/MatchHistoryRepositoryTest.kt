package com.toptrumps.feature.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room's in-memory builder needs a real `Context`, which Robolectric supplies without an emulator
 * — see the WBS's "testable in Room's in-memory database on the JVM." Pinned to API 34 rather
 * than following `compileSdk` (37): Robolectric's shadow support trails the newest SDKs, and
 * nothing under test is compileSdk-sensitive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class MatchHistoryRepositoryTest {

    private lateinit var database: HistoryDatabase
    private lateinit var repository: MatchHistoryRepository

    @Before
    public fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MatchHistoryRepository(database)
    }

    @After
    public fun tearDown() {
        database.close()
    }

    private suspend fun record(
        opponentName: String,
        outcome: Outcome,
        myScore: Int = 15,
        opponentScore: Int = 15,
        cardsWon: List<CardWin> = emptyList(),
        timestampEpochMillis: Long = 0,
    ) = repository.recordMatch(
        timestampEpochMillis = timestampEpochMillis,
        deckId = "motorcycles",
        deckName = "Motorcycles",
        opponentName = opponentName,
        outcome = outcome,
        myScore = myScore,
        opponentScore = opponentScore,
        cardsWon = cardsWon,
    )

    @Test
    public fun `history list is most recent first`(): Unit = runTest {
        record("Alex", Outcome.WIN, timestampEpochMillis = 1_000)
        record("Bo", Outcome.LOSS, timestampEpochMillis = 3_000)
        record("Cass", Outcome.DRAW, timestampEpochMillis = 2_000)

        val names = repository.matches.first().map { it.opponentName }
        assertEquals(listOf("Bo", "Cass", "Alex"), names)
    }

    @Test
    public fun `head-to-head is correct across several opponents including one played many times`(): Unit = runTest {
        record("Alex", Outcome.WIN)
        record("Alex", Outcome.WIN)
        record("Alex", Outcome.LOSS)
        record("Bo", Outcome.DRAW)

        val byOpponent = repository.headToHead.first().associateBy { it.opponentName }
        assertEquals(HeadToHeadRecord("Alex", wins = 2, losses = 1, draws = 0), byOpponent.getValue("Alex"))
        assertEquals(HeadToHeadRecord("Bo", wins = 0, losses = 0, draws = 1), byOpponent.getValue("Bo"))
    }

    @Test
    public fun `overall record and win rate reflect a mixture of results`(): Unit = runTest {
        record("Alex", Outcome.WIN)
        record("Bo", Outcome.WIN)
        record("Cass", Outcome.LOSS)
        record("Dee", Outcome.DRAW)

        val overall = repository.overallRecord.first()
        assertEquals(OverallRecord(wins = 2, losses = 1, draws = 1), overall)
        assertEquals(0.5, overall.winRate!!, 0.0001)
    }

    @Test
    public fun `win rate is null with no matches played`(): Unit = runTest {
        assertNull(repository.overallRecord.first().winRate)
    }

    @Test
    public fun `most-won cards are ordered by win count descending`(): Unit = runTest {
        val triumph = CardWin("triumph-bonneville", "Triumph Bonneville")
        val ducati = CardWin("ducati-monster", "Ducati Monster")
        val honda = CardWin("honda-cb750", "Honda CB750")
        record("Alex", Outcome.WIN, cardsWon = listOf(triumph, ducati))
        record("Bo", Outcome.WIN, cardsWon = listOf(triumph, honda))
        record("Cass", Outcome.WIN, cardsWon = listOf(triumph))

        val mostWon = repository.mostWonCards.first()
        assertEquals(
            listOf(
                CardWinCount("triumph-bonneville", "Triumph Bonneville", 3),
                CardWinCount("ducati-monster", "Ducati Monster", 1),
                CardWinCount("honda-cb750", "Honda CB750", 1),
            ),
            mostWon,
        )
    }

    @Test
    public fun `two decks reusing the same card id are tallied separately, not merged`(): Unit = runTest {
        repository.recordMatch(
            timestampEpochMillis = 0,
            deckId = "motorcycles",
            deckName = "Motorcycles",
            opponentName = "Alex",
            outcome = Outcome.WIN,
            myScore = 15,
            opponentScore = 15,
            cardsWon = listOf(CardWin("card-01", "Triumph Bonneville")),
        )
        repository.recordMatch(
            timestampEpochMillis = 0,
            deckId = "cars",
            deckName = "Cars",
            opponentName = "Alex",
            outcome = Outcome.WIN,
            myScore = 15,
            opponentScore = 15,
            cardsWon = listOf(CardWin("card-01", "Ferrari Testarossa")),
        )

        val mostWon = repository.mostWonCards.first()
        assertEquals("same card id in two different decks must not merge into one row", 2, mostWon.size)
        assertEquals(listOf(1, 1), mostWon.map { it.winCount })
    }

    @Test
    public fun `a match with no cards won records with an empty card list and no orphan rows`(): Unit = runTest {
        record("Alex", Outcome.LOSS, cardsWon = emptyList())

        assertEquals(1, repository.matches.first().size)
        assertEquals(emptyList<CardWinCount>(), repository.mostWonCards.first())
    }
}
