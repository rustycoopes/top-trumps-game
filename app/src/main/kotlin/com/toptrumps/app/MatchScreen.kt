package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * [localSeat] is the wire-level seat string ("HOST"/"GUEST") the local player occupies — solo
 * mode always seats the human as [com.toptrumps.rules.Seat.HOST] (the default), so its call site
 * is unchanged; a two-device guest passes `"GUEST"` so `round.chooser`/`round.winner` are read
 * relative to the right side. [rematchLabel] lets a two-device match relabel the result screen's
 * button, since a real two-device rematch flow doesn't exist yet (tracked as a follow-up).
 * [onLeaveMatch], when non-null, renders a deliberate-quit affordance during play — two-device
 * only; solo has no peer to notify and no drop to distinguish from a quit. [soundEffects] fires the
 * six cues WBS slice-7-polish calls for; [rememberAnimationGate] (shared across the whole match,
 * not per-round) decides whether a given reveal/slide/sound plays or hard-cuts. [imageLoader] is
 * the app's single shared instance ([AppGraph.imageLoader]) — this screen no longer builds or
 * shuts down its own (slice 2's ImageLoader-ownership move).
 */
@Composable
public fun MatchScreen(
    session: MatchSession,
    deckId: String,
    imageLoader: ImageLoader,
    onRematch: () -> Unit,
    soundEffects: SoundEffects,
    modifier: Modifier = Modifier,
    localSeat: String = "HOST",
    rematchLabel: String = "Rematch",
    onLeaveMatch: (() -> Unit)? = null,
) {
    val view by session.view.collectAsStateWithLifecycle()

    // Scoped to this screen via the ADR's `DisposableEffect` on `LocalView` rather than the whole
    // Activity — released the moment the player leaves the match screen, not just the app.
    val localView = LocalView.current
    DisposableEffect(localView) {
        localView.keepScreenOn = true
        onDispose { localView.keepScreenOn = false }
    }

    val animationGate = rememberAnimationGate()

    when (val current = view) {
        null -> Column(modifier = modifier.fillMaxSize().padding(16.dp)) { Text("Connecting…") }
        is MatchView.Finished ->
            ResultScreen(current, deckId, imageLoader, localSeat, rematchLabel, onRematch, soundEffects, animationGate, modifier)
        is MatchView.InProgress ->
            InProgressScreen(session, current, deckId, localSeat, imageLoader, onLeaveMatch, soundEffects, animationGate, modifier)
    }
}

@Composable
private fun InProgressScreen(
    session: MatchSession,
    view: MatchView.InProgress,
    deckId: String,
    localSeat: String,
    imageLoader: ImageLoader,
    onLeaveMatch: (() -> Unit)?,
    soundEffects: SoundEffects,
    gate: AnimationGate,
    modifier: Modifier = Modifier,
) {
    LogRecomposition("InProgressScreen")

    // See AnimationGate's doc: only set once the round is genuinely observed AwaitingChoice, and a
    // SideEffect (runs after commit) rather than a plain assignment, so a child composing within
    // this same first pass (e.g. a resync landing straight on an already-resolved round) still
    // reads the pre-existing value rather than one this same pass just produced.
    SideEffect { if (view.round is RemoteRoundState.AwaitingChoice) gate.hasSeenAwaitingChoice = true }

    // The win-pile browser is a state within the match, not a navigation destination — returning
    // must restore the live round exactly as it was, and this local flag does that for free
    // since `view` keeps updating underneath it regardless of which branch is showing.
    var showingPile by remember { mutableStateOf(false) }

    if (showingPile) {
        WinPileGrid(pile = view.myPile, deckId = deckId, imageLoader = imageLoader, onBack = { showingPile = false }, modifier = modifier)
        return
    }

    // Shared by ScoreBar (targets) and ResolvedContent (sources) so the slide overlay can animate
    // between them — hoisted here since they're siblings, not ancestor/descendant.
    val positions = remember { CardSlotPositions() }
    val slideOverlay = remember { SlideOverlayState() }
    var overlayOrigin by remember { mutableStateOf<Rect?>(null) }

    Box(modifier = modifier.fillMaxSize().reportGlobalPosition { overlayOrigin = it }) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScoreBar(view = view, onTapMyPile = { showingPile = true }, positions = positions)
            Text("Your card: ${view.self.name}")
            CardImage(
                deckId,
                view.self.imageFile,
                view.self.name,
                imageLoader,
                size = 220.dp,
                modifier = Modifier.reportGlobalPosition { positions.selfCard = it },
            )

            when (val round = view.round) {
                is RemoteRoundState.AwaitingChoice -> AwaitingChoiceContent(session, view, round, localSeat, soundEffects)
                is RemoteRoundState.Resolved ->
                    ResolvedContent(session, view, round, deckId, localSeat, imageLoader, soundEffects, gate, positions, slideOverlay)
            }

            if (onLeaveMatch != null) {
                TextButton(onClick = onLeaveMatch) { Text("Leave match") }
            }
        }

        SlideOverlay(state = slideOverlay, overlayOrigin = overlayOrigin)
    }
}

@Composable
private fun ScoreBar(view: MatchView.InProgress, onTapMyPile: () -> Unit, positions: CardSlotPositions) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onTapMyPile, modifier = Modifier.reportGlobalPosition { positions.myPileLabel = it }) {
            Text("Your pile: ${view.myScore}")
        }
        Text("Round ${view.roundNumber} / ${view.totalRounds}")
        Text("Opponent: ${view.opponentScore}", modifier = Modifier.reportGlobalPosition { positions.opponentLabel = it })
    }
}

@Composable
private fun AwaitingChoiceContent(
    session: MatchSession,
    view: MatchView.InProgress,
    round: RemoteRoundState.AwaitingChoice,
    localSeat: String,
    soundEffects: SoundEffects,
) {
    if (round.chooser != localSeat) {
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

    // Disabled while a choice is already in flight — a second tap wouldn't be resent anyway (the
    // reconnect-resync ADR's at-most-one-intent invariant), but a disabled button says so.
    val pending by session.hasPendingIntent.collectAsStateWithLifecycle()

    view.metrics.forEach { spec ->
        val value = view.self.stats.getValue(spec.key)
        val available = spec.key in round.remainingMetrics
        Button(
            onClick = {
                soundEffects.play(SoundEffects.Cue.SELECT)
                session.submit(PlayerIntent.ChooseMetric(MetricKey(spec.key)))
            },
            enabled = available && !pending,
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
    localSeat: String,
    imageLoader: ImageLoader,
    soundEffects: SoundEffects,
    gate: AnimationGate,
    positions: CardSlotPositions,
    slideOverlay: SlideOverlayState,
) {
    val spec = view.metrics.first { it.key == round.decidingMetric }
    val selfValue = view.self.stats.getValue(spec.key)
    val opponentCard = (view.opponent as? RemoteOpponentView.Revealed)?.card
    val pending by session.hasPendingIntent.collectAsStateWithLifecycle()

    // A fresh mount of this composable *is* "just resolved" — the `when (round)` above disposes
    // AwaitingChoiceContent's composition and starts this one from scratch every time a round
    // resolves, so a plain `remember` capturing the decision once, at that moment, is the whole
    // trigger; no round-number bookkeeping needed. `gate.shouldHardCut()` covers both a resync
    // landing straight on an already-resolved round and a backgrounded-and-resumed match (see
    // AnimationGate's doc); `session.lastResync` covers the same resync case defensively should a
    // future change let one reach this composable without a remount in between (see
    // [MatchSession.lastResync]'s doc). `shouldHardCut()` mutates the gate's one-shot backgrounded
    // flag, so this must be evaluated exactly once — `remember` with no key guarantees that here,
    // same as every other "fresh mount is the event" decision in this file.
    val skipAnimation = remember { gate.shouldHardCut() || session.lastResync.value == view.revision }

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
            null -> "You each keep your own card."
            localSeat -> "You win this round!"
            else -> "Opponent wins this round."
        },
    )

    if (opponentCard != null) {
        Text("Opponent's card, fully revealed: ${opponentCard.name}")
        var revealed by remember { mutableStateOf(false) }
        FlippableOpponentCard(
            card = opponentCard,
            deckId = deckId,
            imageLoader = imageLoader,
            size = 220.dp,
            skipAnimation = skipAnimation,
            modifier = Modifier.reportGlobalPosition { positions.opponentCard = it },
            onRevealed = {
                if (!skipAnimation) soundEffects.play(SoundEffects.Cue.FLIP)
                revealed = true
            },
        )
        view.metrics.forEach { m -> Text("  ${m.label}: ${formatStat(m, opponentCard.stats.getValue(m.key))} ${m.unit}") }

        // Waits for the flip to finish (or, on a hard-cut, for the single instant snapTo settles)
        // before the win/loss cue and the slide-to-pile — a card announcing its own win before
        // it's even visibly turned over reads as broken, not snappy.
        LaunchedEffect(revealed) {
            if (!revealed || skipAnimation || round.winner == null) return@LaunchedEffect
            soundEffects.play(if (round.winner == localSeat) SoundEffects.Cue.ROUND_WIN else SoundEffects.Cue.ROUND_LOSS)
            val target = if (round.winner == localSeat) positions.myPileLabel else positions.opponentLabel
            val selfRect = positions.selfCard
            val opponentRect = positions.opponentCard
            // Both cards travel together, not one after the other — `slide` suspends for the
            // animation's full duration, so awaiting the first before starting the second would
            // make the opponent's card wait for the player's to finish landing.
            if (target != null) {
                coroutineScope {
                    if (selfRect != null) {
                        launch { slideOverlay.slide(deckId, view.self.imageFile, view.self.name, imageLoader, selfRect, target) }
                    }
                    if (opponentRect != null) {
                        launch { slideOverlay.slide(deckId, opponentCard.imageFile, opponentCard.name, imageLoader, opponentRect, target) }
                    }
                }
            }
        }
    }

    Button(onClick = { session.submit(PlayerIntent.AdvanceRound) }, enabled = !pending) {
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
    localSeat: String,
    rematchLabel: String,
    onRematch: () -> Unit,
    soundEffects: SoundEffects,
    gate: AnimationGate,
    modifier: Modifier = Modifier,
) {
    // Same "a fresh mount is the event" reasoning as ResolvedContent — MatchScreen's `when (view)`
    // only reaches this branch once, the moment the match actually finishes (or immediately, if a
    // resync/resume landed straight on an already-finished match, in which case `shouldHardCut()`
    // is true and the fanfare/dirge correctly does not play).
    LaunchedEffect(Unit) {
        if (!gate.shouldHardCut() && view.winner != null) {
            soundEffects.play(if (view.winner == localSeat) SoundEffects.Cue.MATCH_VICTORY else SoundEffects.Cue.MATCH_DEFEAT)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (view.winner) {
                null -> "It's a draw."
                localSeat -> "Victory!"
                else -> "Defeat."
            },
        )
        Text("Final score — you: ${view.myScore}, opponent: ${view.opponentScore}")
        Button(onClick = onRematch) { Text(rematchLabel) }
    }
}

/**
 * Renders a card's photo from the deck's own asset folder — `file:///android_asset/<deckId>/<imageFile>`,
 * per the deck-storage ADR. [imageLoader] has its disk cache disabled (the source is already local
 * storage) and [size] gives Coil an explicit decode target for every call site, rather than ever
 * decoding a full-resolution bitmap into a thumbnail.
 */
@Composable
internal fun CardImage(
    deckId: String,
    imageFile: String,
    contentDescription: String?,
    imageLoader: ImageLoader,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = "file:///android_asset/$deckId/$imageFile",
        contentDescription = contentDescription,
        imageLoader = imageLoader,
        modifier = modifier.size(size),
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
