package com.toptrumps.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toptrumps.app.theme.DisplayFontFamily
import com.toptrumps.app.theme.LobbyPalette
import com.toptrumps.feature.history.CardWinCount
import com.toptrumps.feature.history.HeadToHeadRecord
import com.toptrumps.feature.history.MatchHistoryRepository
import com.toptrumps.feature.history.OverallRecord
import kotlin.math.roundToInt

/**
 * Stories 79–81: head-to-head per opponent, overall record and win rate, and the cards that have
 * won you the most rounds. Themed as "The Pack" (second-round-updates#53), reusing [LobbyScreen]'s
 * [LobbyTextLink], [DashedDivider] and [LobbyHintBox] rather than restyling from scratch.
 */
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
        modifier = modifier
            .fillMaxSize()
            .background(LobbyPalette.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LobbyTextLink("Back", onBack)

        Text(
            "Stats",
            style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 34.sp),
            color = LobbyPalette.ink,
        )

        Text(
            "Overall record: ${overall.wins}W ${overall.losses}L ${overall.draws}D",
            color = LobbyPalette.ink,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Text("Win rate: ${overall.winRate.toDisplayPercent()}", color = LobbyPalette.inkDim, fontSize = 13.sp)

        DashedDivider()
        StatsSectionHeader("Head-to-head")
        if (headToHead.isEmpty()) {
            LobbyHintBox("No matches played yet.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                headToHead.forEach { record -> HeadToHeadRow(record) }
            }
        }

        DashedDivider()
        StatsSectionHeader("Most-won cards")
        if (mostWonCards.isEmpty()) {
            LobbyHintBox("No cards won yet.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                mostWonCards.forEach { card -> CardWinRow(card) }
            }
        }
    }
}

@Composable
private fun StatsSectionHeader(label: String) {
    Text(
        label,
        style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp),
        color = LobbyPalette.ink,
    )
}

@Composable
private fun HeadToHeadRow(record: HeadToHeadRecord) {
    StatsRow(record.opponentName, "${record.wins}W ${record.losses}L ${record.draws}D")
}

@Composable
private fun CardWinRow(card: CardWinCount) {
    StatsRow(card.cardName, "${card.winCount} round${if (card.winCount == 1) "" else "s"} won")
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().lobbyCard(),
    ) {
        Text(label, color = LobbyPalette.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(value, color = LobbyPalette.inkDim, fontSize = 13.sp)
    }
}

private fun Double?.toDisplayPercent(): String = if (this == null) "—" else "${(this * 100).roundToInt()}%"
