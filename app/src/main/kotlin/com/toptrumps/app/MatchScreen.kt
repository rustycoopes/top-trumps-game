package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.session.MatchSession
import com.toptrumps.session.MatchView
import com.toptrumps.session.RemoteCardFace
import com.toptrumps.session.RemoteOpponentView
import com.toptrumps.session.RemoteRoundState

/**
 * Deliberately plain — no theme, no images, no animation. Slice 2's job is the full match loop
 * being correct (turn alternation, tiebreaks, piles, reveal, result); visual polish is slice 7.
 *
 * Solo-only for now: [AppGraph.startSoloMatch] always seats the local human as [com.toptrumps.rules.Seat.HOST]
 * and the AI as guest, which is why this screen compares wire-level seat strings against the
 * literal `"HOST"` below rather than taking "which seat is local" as a parameter.
 */
@Composable
public fun MatchScreen(session: MatchSession, onRematch: () -> Unit, modifier: Modifier = Modifier) {
    val view by session.view.collectAsStateWithLifecycle()

    when (val current = view) {
        null -> Column(modifier = modifier.fillMaxSize().padding(16.dp)) { Text("Connecting…") }
        is MatchView.Finished -> ResultScreen(current, onRematch, modifier)
        is MatchView.InProgress -> InProgressScreen(session, current, modifier)
    }
}

@Composable
private fun InProgressScreen(session: MatchSession, view: MatchView.InProgress, modifier: Modifier = Modifier) {
    // The win-pile browser is a state within the match, not a navigation destination — returning
    // must restore the live round exactly as it was, and this local flag does that for free
    // since `view` keeps updating underneath it regardless of which branch is showing.
    var showingPile by remember { mutableStateOf(false) }

    if (showingPile) {
        WinPileGrid(pile = view.myPile, onBack = { showingPile = false }, modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScoreBar(view = view, onTapMyPile = { showingPile = true })
        Text("Your card: ${view.self.name}")

        when (val round = view.round) {
            is RemoteRoundState.AwaitingChoice -> AwaitingChoiceContent(session, view, round)
            is RemoteRoundState.Resolved -> ResolvedContent(session, view, round)
        }
    }
}

@Composable
private fun ScoreBar(view: MatchView.InProgress, onTapMyPile: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onTapMyPile) { Text("Your pile: ${view.myScore}") }
        Text("Round ${view.roundNumber} / ${view.totalRounds}")
        Text("Opponent: ${view.opponentScore}")
    }
}

@Composable
private fun AwaitingChoiceContent(
    session: MatchSession,
    view: MatchView.InProgress,
    round: RemoteRoundState.AwaitingChoice,
) {
    if (round.chooser != "HOST") {
        Text("Opponent is choosing…")
        return
    }

    val opponent = view.opponent
    if (opponent is RemoteOpponentView.Contested && opponent.revealed.isNotEmpty()) {
        Text("Tied so far — the same player chooses again:")
        opponent.revealed.forEach { revealed ->
            val spec = view.metrics.first { it.key == revealed.metric }
            val selfValue = view.self.stats.getValue(revealed.metric)
            Text("  ${spec.label}: you $selfValue ${spec.unit} — opponent ${revealed.value} ${spec.unit} (tied)")
        }
    } else {
        Text("Choose a stat:")
    }

    view.metrics.forEach { spec ->
        val value = view.self.stats.getValue(spec.key)
        val available = spec.key in round.remainingMetrics
        Button(
            onClick = { session.submit(PlayerIntent.ChooseMetric(MetricKey(spec.key))) },
            enabled = available,
        ) {
            Text("${spec.label}: $value ${spec.unit}" + if (available) "" else " (tied — unavailable)")
        }
    }
}

@Composable
private fun ResolvedContent(session: MatchSession, view: MatchView.InProgress, round: RemoteRoundState.Resolved) {
    val spec = view.metrics.first { it.key == round.decidingMetric }
    val selfValue = view.self.stats.getValue(spec.key)
    val opponentCard = (view.opponent as? RemoteOpponentView.Revealed)?.card

    if (round.resolution == "ALL_METRICS_TIED_FALLBACK") {
        Text("Every stat tied on ${spec.label}!")
    } else {
        Text("Settled on ${spec.label}")
        Text("You: $selfValue ${spec.unit}")
        Text("Opponent: ${opponentCard?.stats?.get(spec.key) ?: "?"} ${spec.unit}")
    }

    Text(
        when (round.winner) {
            "HOST" -> "You win this round!"
            "GUEST" -> "Opponent wins this round."
            else -> "You each keep your own card."
        },
    )

    if (opponentCard != null) {
        Text("Opponent's card, fully revealed: ${opponentCard.name}")
        view.metrics.forEach { m -> Text("  ${m.label}: ${opponentCard.stats.getValue(m.key)} ${m.unit}") }
    }

    Button(onClick = { session.submit(PlayerIntent.AdvanceRound) }) {
        Text("Continue")
    }
}

@Composable
private fun WinPileGrid(pile: List<RemoteCardFace>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onBack) { Text("Back to match") }
        Text("Your pile (${pile.size} cards)")
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize()) {
            items(pile) { card -> Text(card.name, modifier = Modifier.padding(4.dp)) }
        }
    }
}

@Composable
private fun ResultScreen(view: MatchView.Finished, onRematch: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (view.winner) {
                "HOST" -> "Victory!"
                "GUEST" -> "Defeat."
                else -> "It's a draw."
            },
        )
        Text("Final score — you: ${view.myScore}, opponent: ${view.opponentScore}")
        Button(onClick = onRematch) { Text("Rematch") }
    }
}
