package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toptrumps.feature.history.CardWinCount
import com.toptrumps.feature.history.HeadToHeadRecord
import com.toptrumps.feature.history.MatchHistoryRepository
import com.toptrumps.feature.history.OverallRecord
import kotlin.math.roundToInt

/** Stories 79–81: head-to-head per opponent, overall record and win rate, and the cards that have won you the most rounds. */
@Composable
public fun StatsScreen(
    repository: MatchHistoryRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overall by repository.overallRecord.collectAsStateWithLifecycle(initialValue = OverallRecord(0, 0, 0))
    val headToHead by repository.headToHead.collectAsStateWithLifecycle(initialValue = emptyList())
    val mostWonCards by repository.mostWonCards.collectAsStateWithLifecycle(initialValue = emptyList())

    // A plain scrollable Column, not a LazyColumn — head-to-head and most-won-cards are two
    // separate unbounded lists sharing one screen alongside static header content, and Compose
    // doesn't support nesting two lazy lists in one scroll direction without explicit height
    // constraints. Both lists are small in absolute terms (one row per opponent/card, not per
    // match), so the lazy-loading Column buys nothing a plain one wouldn't already give for free.
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Stats")

        Text("Overall record: ${overall.wins}W ${overall.losses}L ${overall.draws}D")
        Text("Win rate: ${overall.winRate.toDisplayPercent()}")

        HorizontalDivider()
        Text("Head-to-head")
        if (headToHead.isEmpty()) {
            Text("No matches played yet.")
        } else {
            headToHead.forEach { record -> HeadToHeadRow(record) }
        }

        HorizontalDivider()
        Text("Most-won cards")
        if (mostWonCards.isEmpty()) {
            Text("No cards won yet.")
        } else {
            mostWonCards.forEach { card -> CardWinRow(card) }
        }
    }
}

@Composable
private fun HeadToHeadRow(record: HeadToHeadRecord) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(record.opponentName)
        Text("${record.wins}W ${record.losses}L ${record.draws}D")
    }
}

@Composable
private fun CardWinRow(card: CardWinCount) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(card.cardName)
        Text("${card.winCount} round${if (card.winCount == 1) "" else "s"} won")
    }
}

private fun Double?.toDisplayPercent(): String = if (this == null) "—" else "${(this * 100).roundToInt()}%"
