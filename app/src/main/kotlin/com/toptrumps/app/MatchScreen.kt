package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.toptrumps.rules.MetricKey
import com.toptrumps.rules.PlayerIntent
import com.toptrumps.rules.yearsSince
import com.toptrumps.session.MatchSession
import com.toptrumps.session.MatchView
import com.toptrumps.session.RemoteCardFace
import com.toptrumps.session.RemoteMetricSpec
import com.toptrumps.session.RemoteOpponentView
import com.toptrumps.session.RemoteRoundState
import kotlinx.datetime.Clock

/**
 * Deliberately plain — no theme, no animation. Slice 3 adds real card art via Coil; visual polish
 * beyond that is slice 7.
 *
 * Solo-only for now: [AppGraph.startSoloMatch] always seats the local human as [com.toptrumps.rules.Seat.HOST]
 * and the AI as guest, which is why this screen compares wire-level seat strings against the
 * literal `"HOST"` below rather than taking "which seat is local" as a parameter.
 */
@Composable
public fun MatchScreen(session: MatchSession, deckId: String, onRematch: () -> Unit, modifier: Modifier = Modifier) {
    val view by session.view.collectAsStateWithLifecycle()

    // Disk cache disabled — the source is already local asset storage (TDD §7) — and shared for
    // the lifetime of the screen rather than rebuilt per image.
    val context = LocalContext.current
    val imageLoader = remember(context) { ImageLoader.Builder(context).diskCache(null).build() }
    DisposableEffect(imageLoader) { onDispose { imageLoader.shutdown() } }

    when (val current = view) {
        null -> Column(modifier = modifier.fillMaxSize().padding(16.dp)) { Text("Connecting…") }
        is MatchView.Finished -> ResultScreen(current, deckId, imageLoader, onRematch, modifier)
        is MatchView.InProgress -> InProgressScreen(session, current, deckId, imageLoader, modifier)
    }
}

@Composable
private fun InProgressScreen(
    session: MatchSession,
    view: MatchView.InProgress,
    deckId: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    // The win-pile browser is a state within the match, not a navigation destination — returning
    // must restore the live round exactly as it was, and this local flag does that for free
    // since `view` keeps updating underneath it regardless of which branch is showing.
    var showingPile by remember { mutableStateOf(false) }

    if (showingPile) {
        WinPileGrid(pile = view.myPile, deckId = deckId, imageLoader = imageLoader, onBack = { showingPile = false }, modifier = modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScoreBar(view = view, onTapMyPile = { showingPile = true })
        Text("Your card: ${view.self.name}")
        CardImage(deckId, view.self.imageFile, view.self.name, imageLoader, size = 220.dp)

        when (val round = view.round) {
            is RemoteRoundState.AwaitingChoice -> AwaitingChoiceContent(session, view, round)
            is RemoteRoundState.Resolved -> ResolvedContent(session, view, round, deckId, imageLoader)
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
            Text("  ${spec.label}: you ${formatStat(spec, selfValue)} ${spec.unit} — opponent ${formatStat(spec, revealed.value)} ${spec.unit} (tied)")
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
            Text(
                "${spec.label}: ${formatStat(spec, value)} ${spec.unit} (${spec.winDirectionLabel()})" +
                    if (available) "" else " (tied — unavailable)",
            )
        }
    }
}

@Composable
private fun ResolvedContent(
    session: MatchSession,
    view: MatchView.InProgress,
    round: RemoteRoundState.Resolved,
    deckId: String,
    imageLoader: ImageLoader,
) {
    val spec = view.metrics.first { it.key == round.decidingMetric }
    val selfValue = view.self.stats.getValue(spec.key)
    val opponentCard = (view.opponent as? RemoteOpponentView.Revealed)?.card

    if (round.resolution == "ALL_METRICS_TIED_FALLBACK") {
        Text("Every stat tied on ${spec.label}!")
    } else {
        Text("Settled on ${spec.label}")
        Text("You: ${formatStat(spec, selfValue)} ${spec.unit}")
        val opponentValue = opponentCard?.stats?.get(spec.key)
        Text("Opponent: ${opponentValue?.let { formatStat(spec, it) } ?: "?"} ${spec.unit}")
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
        CardImage(deckId, opponentCard.imageFile, opponentCard.name, imageLoader, size = 220.dp)
        view.metrics.forEach { m -> Text("  ${m.label}: ${formatStat(m, opponentCard.stats.getValue(m.key))} ${m.unit}") }
    }

    Button(onClick = { session.submit(PlayerIntent.AdvanceRound) }) {
        Text("Continue")
    }
}

@Composable
private fun WinPileGrid(
    pile: List<RemoteCardFace>,
    deckId: String,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onBack) { Text("Back to match") }
        Text("Your pile (${pile.size} cards)")
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize()) {
            items(pile) { card ->
                Column(modifier = Modifier.padding(4.dp)) {
                    // A small, explicit thumbnail size — thirty full-resolution bitmaps in this
                    // grid would be ~3.5MB each in memory and OOM a mid-range phone (TDD §7).
                    CardImage(deckId, card.imageFile, card.name, imageLoader, size = 96.dp)
                    Text(card.name)
                }
            }
        }
    }
}

@Composable
private fun ResultScreen(
    view: MatchView.Finished,
    deckId: String,
    imageLoader: ImageLoader,
    onRematch: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

/**
 * Renders a card's photo from the deck's own asset folder — `file:///android_asset/<deckId>/<imageFile>`,
 * per the deck-storage ADR. [imageLoader] has its disk cache disabled (the source is already local
 * storage) and [size] gives Coil an explicit decode target for every call site, rather than ever
 * decoding a full-resolution bitmap into a thumbnail.
 */
@Composable
private fun CardImage(deckId: String, imageFile: String, contentDescription: String?, imageLoader: ImageLoader, size: Dp) {
    AsyncImage(
        model = "file:///android_asset/$deckId/$imageFile",
        contentDescription = contentDescription,
        imageLoader = imageLoader,
        modifier = Modifier.size(size),
    )
}

/**
 * A metric's stored value, as a player should read it. [RemoteMetricSpec.display] of
 * `YEARS_SINCE_VALUE` treats [raw] as a calendar year and shows "years since" it instead of the
 * raw year (story 70) — the derivation happens here, at the UI layer, never inside the engine.
 */
private fun formatStat(spec: RemoteMetricSpec, raw: Double): String = when (spec.display) {
    "YEARS_SINCE_VALUE" -> yearsSince(raw.toInt(), Clock.System).toString()
    else -> if (raw == raw.toInt().toDouble()) raw.toInt().toString() else raw.toString()
}

/**
 * Story 27: whether a stat is won by the higher or lower shown value. [RemoteMetricSpec.direction]
 * is already the *displayed* direction (the host computes it via `MetricSpec.displayDirection()`
 * before it ever reaches the wire), so this is a plain label, not a flip.
 */
private fun RemoteMetricSpec.winDirectionLabel(): String = if (direction == "HIGH_WINS") "higher wins" else "lower wins"
