package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toptrumps.feature.history.MatchHistoryRepository
import com.toptrumps.feature.history.MatchRecord
import com.toptrumps.feature.history.Outcome
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Story 78: every completed match, most recent first. */
@Composable
public fun HistoryScreen(
    repository: MatchHistoryRepository,
    onOpenStats: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches by repository.matches.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            TextButton(onClick = onOpenStats) { Text("Stats") }
        }
        Text("History")

        if (matches.isEmpty()) {
            Text("No matches played yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(matches, key = MatchRecord::id) { match ->
                    MatchRow(match)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MatchRow(match: MatchRecord) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("vs ${match.opponentName} — ${match.deckName}")
            Text(match.outcome.label())
        }
        Text("${match.myScore} – ${match.opponentScore} · ${match.timestampEpochMillis.toDisplayDateTime()}")
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
