package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.session.MatchSession
import com.toptrumps.session.RemoteOpponentView
import com.toptrumps.session.RemoteRoundState

/**
 * Deliberately ugly — slice 1 is a walking skeleton, not a designed screen. Plain
 * [Column]/[Text]/[Button], no theme, no images, no animation.
 */
@Composable
public fun MatchScreen(session: MatchSession, modifier: Modifier = Modifier) {
    val view by session.view.collectAsStateWithLifecycle()
    val current = view

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (current == null) {
            Text("Connecting…")
            return@Column
        }

        Text("Your card: ${current.self.name}")

        when (val round = current.round) {
            is RemoteRoundState.AwaitingChoice -> {
                if (round.chooser == "HOST") {
                    Text("Choose a stat:")
                    current.metrics.forEach { spec ->
                        val value = current.self.stats.getValue(spec.key)
                        Button(onClick = { session.submit(PlayerIntent.ChooseMetric(MetricKey(spec.key))) }) {
                            Text("${spec.label}: $value ${spec.unit}")
                        }
                    }
                } else {
                    Text("Opponent is choosing…")
                }
            }

            is RemoteRoundState.Resolved -> {
                val spec = current.metrics.first { it.key == round.decidingMetric }
                val selfValue = current.self.stats.getValue(spec.key)
                val opponentValue = (current.opponent as? RemoteOpponentView.Contested)
                    ?.revealed
                    ?.firstOrNull { it.metric == spec.key }
                    ?.value

                Text("Metric: ${spec.label}")
                Text("You: $selfValue ${spec.unit}")
                Text("Opponent: ${opponentValue ?: "?"} ${spec.unit}")

                Text(
                    when (round.winner) {
                        "HOST" -> "You win this round!"
                        "GUEST" -> "Opponent wins this round."
                        else -> "Tie — no winner this round."
                    },
                )
            }
        }
    }
}
