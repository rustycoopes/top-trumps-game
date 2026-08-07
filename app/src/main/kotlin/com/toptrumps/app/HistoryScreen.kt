package com.toptrumps.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.toptrumps.feature.history.MatchHistoryRepository
import com.toptrumps.feature.history.MatchRecord
import com.toptrumps.feature.history.Outcome
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Story 78: every completed match, most recent first. Themed as "The Pack" (second-round-updates#53),
 * reusing [LobbyScreen]'s [LobbyTextLink] and [LobbyHintBox] rather than restyling from scratch.
 */
@Composable
public fun HistoryScreen(
    repository: MatchHistoryRepository,
    onOpenStats: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches by repository.matches.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LobbyPalette.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LobbyTextLink("Back", onBack)
            LobbyTextLink("Stats", onOpenStats)
        }

        Text(
            "History",
            style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 34.sp),
            color = LobbyPalette.ink,
        )

        if (matches.isEmpty()) {
            LobbyHintBox("No matches played yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(matches, key = MatchRecord::id) { match -> MatchRow(match) }
            }
        }
    }
}

@Composable
private fun MatchRow(match: MatchRecord) {
    Column(modifier = Modifier.fillMaxWidth().lobbyCard()) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                "vs ${match.opponentName} — ${match.deckName}",
                modifier = Modifier.weight(1f),
                color = LobbyPalette.ink,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Box(
                Modifier
                    .background(LobbyPalette.ink, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    match.outcome.label(),
                    style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp),
                    color = LobbyPalette.accent,
                )
            }
        }
        Text(
            "${match.myScore} – ${match.opponentScore} · ${match.timestampEpochMillis.toDisplayDateTime()}",
            color = LobbyPalette.inkDim,
            fontSize = 12.sp,
        )
    }
}

private fun Outcome.label(): String = when (this) {
    Outcome.WIN -> "Won"
    Outcome.LOSS -> "Lost"
    Outcome.DRAW -> "Draw"
}

private fun Long.toDisplayDateTime(): String {
    val dateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())
    val minute = dateTime.minute.toString().padStart(2, '0')
    return "${dateTime.date} ${dateTime.hour}:$minute"
}
