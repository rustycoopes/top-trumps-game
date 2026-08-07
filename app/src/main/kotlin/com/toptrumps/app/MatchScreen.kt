package com.toptrumps.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.toptrumps.app.card.AssetTrumpCard
import com.toptrumps.app.card.CardCornerRadius
import com.toptrumps.app.card.CardVariant
import com.toptrumps.app.card.RowOutcome
import com.toptrumps.app.card.TrumpCardBack
import com.toptrumps.app.card.cardContentOf
import com.toptrumps.app.card.cardGeometry
import com.toptrumps.app.card.solveCardWidth
import com.toptrumps.app.theme.CardPalette
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

/** Row floor for the full-size hero card — see card-visual-identity TDD decision 5. */
private val HeroMinRowHeight = 48.dp

/** Row floor for the side-by-side reveal — smaller than the hero's, since two ~190dp-wide cards don't fit a strict 48dp floor plus an outcome glyph (WBS design notes). */
private val RevealMinRowHeight = 28.dp

/** Gap between the two reveal cards. */
private val RevealSpacing = 12.dp

/** How much the hero card dims when it's not [MatchScreen]'s own player's turn — the whole card is inert then, not just individual rows. */
private const val HeroInertAlpha = 0.6f

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
 * shuts down its own (slice 2's ImageLoader-ownership move). [palette] is the deck's resolved
 * [CardPalette] (card-visual-identity TDD decision 1) — every card colour on this screen comes
 * from it, never from `MaterialTheme.colorScheme`.
 */
@Composable
public fun MatchScreen(
    session: MatchSession,
    deckId: String,
    imageLoader: ImageLoader,
    palette: CardPalette,
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
            InProgressScreen(session, current, deckId, localSeat, imageLoader, palette, onLeaveMatch, soundEffects, animationGate, modifier)
    }
}

/**
 * Three fixed regions, never scrolling (card-visual-identity TDD decision 5): [MatchTopBar]
 * (intrinsic height), a `Modifier.weight(1f)` card region holding either the choosable hero card
 * or the side-by-side reveal, and a bottom action strip (intrinsic height). Rects are still
 * `boundsInRoot()`, so moving the score labels out of a scrolling column into the top bar changes
 * nothing about [SlideOverlay]'s coordinate maths.
 */
@Composable
private fun InProgressScreen(
    session: MatchSession,
    view: MatchView.InProgress,
    deckId: String,
    localSeat: String,
    imageLoader: ImageLoader,
    palette: CardPalette,
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
        WinPileGrid(
            pile = view.myPile,
            deckId = deckId,
            metrics = view.metrics,
            palette = palette,
            imageLoader = imageLoader,
            onBack = { showingPile = false },
            modifier = modifier,
        )
        return
    }

    // Shared by MatchTopBar (targets) and the reveal region (sources) so the slide overlay can
    // animate between them — hoisted here since they're siblings, not ancestor/descendant.
    val positions = remember { CardSlotPositions() }
    val slideOverlay = remember { SlideOverlayState() }
    var overlayOrigin by remember { mutableStateOf<Rect?>(null) }

    // Disabled while a choice/advance is already in flight — a second tap wouldn't be resent
    // anyway (the reconnect-resync ADR's at-most-one-intent invariant), but this is what makes
    // every row/button on the card and the strip say so.
    val pending by session.hasPendingIntent.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().reportGlobalPosition { overlayOrigin = it }) {
        Column(modifier = Modifier.fillMaxSize()) {
            MatchTopBar(view = view, onTapMyPile = { showingPile = true }, onLeaveMatch = onLeaveMatch, positions = positions)

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Never inside the card composable itself — that would mean a subcomposition per
                // grid cell elsewhere (WBS design notes). One BoxWithConstraints, right here.
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    when (val round = view.round) {
                        is RemoteRoundState.AwaitingChoice ->
                            HeroCard(
                                session = session,
                                view = view,
                                round = round,
                                localSeat = localSeat,
                                deckId = deckId,
                                imageLoader = imageLoader,
                                palette = palette,
                                pending = pending,
                                soundEffects = soundEffects,
                                maxWidth = maxWidth,
                                maxHeight = maxHeight,
                                positions = positions,
                            )
                        is RemoteRoundState.Resolved ->
                            RevealPair(
                                session = session,
                                view = view,
                                round = round,
                                deckId = deckId,
                                localSeat = localSeat,
                                imageLoader = imageLoader,
                                palette = palette,
                                soundEffects = soundEffects,
                                gate = gate,
                                positions = positions,
                                slideOverlay = slideOverlay,
                                maxWidth = maxWidth,
                                maxHeight = maxHeight,
                            )
                    }
                }
            }

            MatchActionStrip(session = session, view = view, localSeat = localSeat, pending = pending)
        }

        SlideOverlay(state = slideOverlay, overlayOrigin = overlayOrigin)
    }
}

@Composable
private fun MatchTopBar(
    view: MatchView.InProgress,
    onTapMyPile: () -> Unit,
    onLeaveMatch: (() -> Unit)?,
    positions: CardSlotPositions,
) {
    // Insets (status bar, nav bar, IME) are handled once, at the NavHost root — see MainActivity's
    // `safeDrawingPadding()` comment — so this bar only needs its own visual padding.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onTapMyPile, modifier = Modifier.reportGlobalPosition { positions.myPileLabel = it }) {
            Text("Your pile: ${view.myScore}")
        }
        Text("Round ${view.roundNumber} / ${view.totalRounds}")
        Text("Opponent: ${view.opponentScore}", modifier = Modifier.reportGlobalPosition { positions.opponentLabel = it })
        // Shortened from the old "Leave match" — this bar already has three other elements
        // competing for a fixed, non-scrolling width.
        if (onLeaveMatch != null) {
            TextButton(onClick = onLeaveMatch) { Text("Leave") }
        }
    }
}

/**
 * The full-screen hero card — the five Material `Button`s this replaces are gone; the card's own
 * stat rows are the tap targets. Read-only (no `onChooseStat`) whenever it isn't [localSeat]'s
 * turn to choose; every row disables itself while [pending], folding
 * `spec.key in round.remainingMetrics` and `session.hasPendingIntent` into one `availableMetrics`
 * set rather than a per-row `enabled && !pending` check (card-visual-identity TDD decision 5).
 *
 * Face-down until tapped (issue #47) — mimics being dealt a real card instead of it landing
 * face-up. [started]/[revealed] are `remember`ed keyed on [RemoteCardFace.id], not just `Unit`:
 * this composable isn't remounted between successive `AwaitingChoice` rounds (same `when`-branch
 * in [InProgressScreen] — see [FlippableCard]'s doc), so a plain unkeyed `remember` would stay
 * flipped forever after the player's first card. Keying on the dealt card's own id resets both
 * flags the instant a new card arrives.
 */
@Composable
private fun HeroCard(
    session: MatchSession,
    view: MatchView.InProgress,
    round: RemoteRoundState.AwaitingChoice,
    deckId: String,
    localSeat: String,
    imageLoader: ImageLoader,
    palette: CardPalette,
    pending: Boolean,
    soundEffects: SoundEffects,
    maxWidth: Dp,
    maxHeight: Dp,
    positions: CardSlotPositions,
) {
    val isMyTurn = round.chooser == localSeat
    val geometry = remember(maxWidth, maxHeight) {
        cardGeometry(solveCardWidth(maxWidth, maxHeight, HeroMinRowHeight), CardVariant.HERO, HeroMinRowHeight)
    }
    // A guest resync can remount this composable straight onto an AwaitingChoice round the player
    // had already flipped before disconnecting (TwoDeviceMatchScreen tears MatchScreen down while
    // the peer is unreachable) — session.lastResync is the guest-only signal ("Set before `_view`"
    // — MatchSession.kt) that distinguishes *that exact view arrived via a resume* from an ordinary
    // new deal. Deliberately not gate.shouldHardCut(): that's also true on every match's very first
    // composition (AnimationGate.hasSeenAwaitingChoice starts false regardless of cause), which
    // would wrongly skip the required flip on round one.
    val resynced = remember(view.self.id) { session.lastResync.value == view.revision }
    var started by remember(view.self.id) { mutableStateOf(resynced) }
    var revealed by remember(view.self.id) { mutableStateOf(false) }
    val availableMetrics = if (isMyTurn && revealed && !pending) round.remainingMetrics.toSet() else emptySet()
    val content = remember(view.self, view.metrics, availableMetrics) {
        cardContentOf(card = view.self, metrics = view.metrics, availableMetrics = availableMetrics)
    }

    FlippableCard(
        skipAnimation = resynced,
        started = started,
        onRevealed = { revealed = true },
        // Dims the whole card, not just its rows, whenever it isn't interactive at all (the
        // opponent's turn) — StatRowView's own per-row dimming only ever runs when `onChooseStat`
        // is non-null, so without this the card would otherwise look identically tappable whether
        // or not it actually is. The flip itself stays tappable regardless of whose turn it is —
        // it's always your own card, so peeking at it doesn't depend on whether you can act on it.
        modifier = Modifier
            .reportGlobalPosition { positions.selfCard = it }
            .alpha(if (isMyTurn) 1f else HeroInertAlpha)
            .clickable(enabled = !started, onClickLabel = "Reveal your card") { started = true },
        back = { mod ->
            val backModifier = if (revealed) mod.clearAndSetSemantics {} else mod
            TrumpCardBack(deckName = deckId, palette = palette, geometry = geometry, caption = "Tap to reveal", modifier = backModifier)
        },
        front = { mod ->
            AssetTrumpCard(
                deckId = deckId,
                imageFile = view.self.imageFile,
                content = content,
                palette = palette,
                geometry = geometry,
                imageLoader = imageLoader,
                // Matches the flip-animation default of no shadow (themed-card-backgrounds ADR)
                // while mid-flip; once settled this is functionally the old static hero card again.
                withShadow = revealed,
                modifier = mod,
                onChooseStat = if (!isMyTurn || !revealed) null else { metricKey ->
                    soundEffects.play(SoundEffects.Cue.SELECT)
                    session.submit(PlayerIntent.ChooseMetric(MetricKey(metricKey)))
                },
            )
        },
    )
}

/**
 * Both full cards side by side at reveal, stat rows aligned for free because [geometry] is a
 * single instance shared by both (card-visual-identity TDD decision 5) — the opponent's flips in
 * via [FlippableCard]; the player's own card is already on screen and simply hard-cuts
 * down to reveal size (the hero-to-reveal size jump isn't animated this slice — WBS design notes).
 */
@Composable
private fun RevealPair(
    session: MatchSession,
    view: MatchView.InProgress,
    round: RemoteRoundState.Resolved,
    deckId: String,
    localSeat: String,
    imageLoader: ImageLoader,
    palette: CardPalette,
    soundEffects: SoundEffects,
    gate: AnimationGate,
    positions: CardSlotPositions,
    slideOverlay: SlideOverlayState,
    maxWidth: Dp,
    maxHeight: Dp,
) {
    val opponentCard = (view.opponent as? RemoteOpponentView.Revealed)?.card ?: return

    val geometry = remember(maxWidth, maxHeight) {
        val perCardMaxWidth = (maxWidth - RevealSpacing) / 2
        cardGeometry(solveCardWidth(perCardMaxWidth, maxHeight, RevealMinRowHeight), CardVariant.REVEAL, RevealMinRowHeight)
    }

    val myOutcome = when (round.winner) {
        null -> RowOutcome.TIE
        localSeat -> RowOutcome.WIN
        else -> RowOutcome.LOSE
    }
    val opponentOutcome = when (round.winner) {
        null -> RowOutcome.TIE
        localSeat -> RowOutcome.LOSE
        else -> RowOutcome.WIN
    }

    val myContent = remember(view.self, opponentCard, view.metrics, round.decidingMetric, myOutcome) {
        cardContentOf(view.self, view.metrics, comparison = opponentCard.stats, decidingMetric = round.decidingMetric, decidedOutcome = myOutcome)
    }
    val opponentContent = remember(opponentCard, view.self, view.metrics, round.decidingMetric, opponentOutcome) {
        cardContentOf(opponentCard, view.metrics, comparison = view.self.stats, decidingMetric = round.decidingMetric, decidedOutcome = opponentOutcome)
    }

    // A fresh mount of this composable *is* "just resolved" — the `when (round)` above disposes
    // the awaiting-choice composition and starts this one from scratch every time a round
    // resolves, so a plain `remember` capturing the decision once, at that moment, is the whole
    // trigger; no round-number bookkeeping needed. `gate.shouldHardCut()` covers both a resync
    // landing straight on an already-resolved round and a backgrounded-and-resumed match (see
    // AnimationGate's doc); `session.lastResync` covers the same resync case defensively should a
    // future change let one reach this composable without a remount in between (see
    // [MatchSession.lastResync]'s doc). `shouldHardCut()` mutates the gate's one-shot backgrounded
    // flag, so this must be evaluated exactly once — `remember` with no key guarantees that here,
    // same as every other "fresh mount is the event" decision in this file.
    val skipAnimation = remember { gate.shouldHardCut() || session.lastResync.value == view.revision }
    var revealed by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(RevealSpacing), verticalAlignment = Alignment.CenterVertically) {
        AssetTrumpCard(
            deckId = deckId,
            imageFile = view.self.imageFile,
            content = myContent,
            palette = palette,
            geometry = geometry,
            imageLoader = imageLoader,
            onChooseStat = null,
            modifier = Modifier.reportGlobalPosition { positions.selfCard = it },
        )
        FlippableCard(
            skipAnimation = skipAnimation,
            modifier = Modifier.reportGlobalPosition { positions.opponentCard = it },
            onRevealed = {
                if (!skipAnimation) soundEffects.play(SoundEffects.Cue.FLIP)
                revealed = true
            },
            back = { mod ->
                // MatchScreen only ever has `deckId`, not the deck's display name — the guest side
                // of a two-device match never loads a local `Deck` at all (TDD decision 6), so
                // there is no display name to show here without new wire plumbing. The id is a
                // reasonable stand-in; a prettier label is a follow-up, not a blocker for this slice.
                //
                // Both faces stay composed for the whole flip (that's the recomposition fix — see
                // CardAnimations.kt), so once `revealed` the back face is permanently invisible but
                // would otherwise stay in the merged semantics tree forever, next to the front
                // face's own content. `clearAndSetSemantics {}` only kicks in once settled, not
                // read from the animating `rotation` value itself, so this costs one recomposition
                // at the moment of reveal, not one per frame.
                val backModifier = if (revealed) mod.clearAndSetSemantics {} else mod
                TrumpCardBack(deckName = deckId, palette = palette, geometry = geometry, modifier = backModifier)
            },
            front = { mod ->
                AssetTrumpCard(
                    deckId = deckId,
                    imageFile = opponentCard.imageFile,
                    content = opponentContent,
                    palette = palette,
                    geometry = geometry,
                    imageLoader = imageLoader,
                    modifier = mod,
                    onChooseStat = null,
                )
            },
        )
    }

    // Waits for the flip to finish (or, on a hard-cut, for the single instant snapTo settles)
    // before the win/loss cue and the slide-to-pile — a card announcing its own win before it's
    // even visibly turned over reads as broken, not snappy.
    LaunchedEffect(revealed) {
        if (!revealed || skipAnimation || round.winner == null) return@LaunchedEffect
        soundEffects.play(if (round.winner == localSeat) SoundEffects.Cue.ROUND_WIN else SoundEffects.Cue.ROUND_LOSS)
        val target = if (round.winner == localSeat) positions.myPileLabel else positions.opponentLabel
        val selfRect = positions.selfCard
        val opponentRect = positions.opponentCard
        // Both cards travel together, not one after the other — `slide` suspends for the
        // animation's full duration, so awaiting the first before starting the second would make
        // the opponent's card wait for the player's to finish landing.
        if (target != null) {
            coroutineScope {
                if (selfRect != null) {
                    launch {
                        slideOverlay.slide(selfRect, target) { mod ->
                            AssetTrumpCard(
                                deckId = deckId,
                                imageFile = view.self.imageFile,
                                content = myContent,
                                palette = palette,
                                geometry = geometry,
                                imageLoader = imageLoader,
                                modifier = mod,
                                onChooseStat = null,
                            )
                        }
                    }
                }
                if (opponentRect != null) {
                    launch {
                        slideOverlay.slide(opponentRect, target) { mod ->
                            AssetTrumpCard(
                                deckId = deckId,
                                imageFile = opponentCard.imageFile,
                                content = opponentContent,
                                palette = palette,
                                geometry = geometry,
                                imageLoader = imageLoader,
                                modifier = mod,
                                onChooseStat = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The bottom action strip — prompt text plus, once a round is resolved, the Continue button.
 * Intrinsic height, not fixed: the contested-tie case's revealed-metric detail can run to a few
 * lines. The `SELECT` sound cue lives in [HeroCard]'s `onChooseStat`, not here — this strip has no
 * `SoundEffects` dependency of its own beyond `Continue`'s implicit silence.
 */
@Composable
private fun MatchActionStrip(session: MatchSession, view: MatchView.InProgress, localSeat: String, pending: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (val round = view.round) {
            is RemoteRoundState.AwaitingChoice -> {
                if (round.chooser != localSeat) {
                    Text("Opponent is choosing…")
                    return@Column
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
            }
            is RemoteRoundState.Resolved -> {
                val spec = view.metrics.first { it.key == round.decidingMetric }
                if (round.resolution == "ALL_METRICS_TIED_FALLBACK") {
                    Text("Every stat tied on ${spec.label}!")
                } else {
                    val selfValue = view.self.stats.getValue(spec.key)
                    val opponentCard = (view.opponent as? RemoteOpponentView.Revealed)?.card
                    val opponentValue = opponentCard?.stats?.get(spec.key)
                    Text("Settled on ${spec.label}")
                    Text("You: ${formatStat(spec, selfValue)} ${spec.unit} — Opponent: ${opponentValue?.let { formatStat(spec, it) } ?: "?"} ${spec.unit}")
                }
                Text(
                    when (round.winner) {
                        null -> "You each keep your own card."
                        localSeat -> "You win this round!"
                        else -> "Opponent wins this round."
                    },
                )
                Button(onClick = { session.submit(PlayerIntent.AdvanceRound) }, enabled = !pending) {
                    Text("Continue")
                }
            }
        }
    }
}

/**
 * Mini cards ([CardVariant.MINI]), not bare thumbnails (card-visual-identity WBS slice 5, closing
 * PRD story 49). Tapping one opens it full-size, read-only ([CardVariant.HERO], `onChooseStat =
 * null`) — the same rendering the reveal already uses — via a local [openedCard], not a navigation
 * destination: [onBack] (and therefore the live round underneath) is untouched by opening or
 * closing a pile card.
 */
@Composable
private fun WinPileGrid(
    pile: List<RemoteCardFace>,
    deckId: String,
    metrics: List<RemoteMetricSpec>,
    palette: CardPalette,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openedCard by remember { mutableStateOf<RemoteCardFace?>(null) }

    val opened = openedCard
    if (opened != null) {
        Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openedCard = null }) { Text("Back to pile") }
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val geometry = remember(maxWidth, maxHeight) {
                    cardGeometry(solveCardWidth(maxWidth, maxHeight, HeroMinRowHeight), CardVariant.HERO, HeroMinRowHeight)
                }
                val content = remember(opened, metrics) { cardContentOf(opened, metrics) }
                AssetTrumpCard(deckId, opened.imageFile, content, palette, geometry, imageLoader, withShadow = true, onChooseStat = null)
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onBack) { Text("Back to match") }
        Text("Your pile (${pile.size} cards)")
        // One instance shared by every tile, not recomputed per cell — same discipline as the
        // card gallery's own mini grid (CardGalleryScreen.kt).
        val miniGeometry = remember { cardGeometry(width = 96.dp, variant = CardVariant.MINI) }
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize()) {
            items(pile, key = { it.id }) { card ->
                val content = remember(card, metrics) { cardContentOf(card, metrics) }
                AssetTrumpCard(
                    deckId = deckId,
                    imageFile = card.imageFile,
                    content = content,
                    palette = palette,
                    geometry = miniGeometry,
                    imageLoader = imageLoader,
                    // Clipped *before* `clickable` (outer, not inner, in the composed chain) so the
                    // tap ripple is bounded by the card's own rounded corners instead of bleeding
                    // into the square layout box behind it — CardChrome's own `clip` only reaches
                    // content drawn inside it, not this caller-supplied modifier.
                    modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(CardCornerRadius)).clickable(onClickLabel = card.name) { openedCard = card },
                    withShadow = true,
                    onChooseStat = null,
                )
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
    // Same "a fresh mount is the event" reasoning as the reveal region — MatchScreen's `when
    // (view)` only reaches this branch once, the moment the match actually finishes (or
    // immediately, if a resync/resume landed straight on an already-finished match, in which case
    // `shouldHardCut()` is true and the fanfare/dirge correctly does not play).
    LaunchedEffect(Unit) {
        if (!gate.shouldHardCut() && view.winner != null) {
            soundEffects.play(if (view.winner == localSeat) SoundEffects.Cue.MATCH_VICTORY else SoundEffects.Cue.MATCH_DEFEAT)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = when (view.winner) {
                null -> "It's a draw."
                localSeat -> "Victory!"
                else -> "Defeat."
            },
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Final score — you: ${view.myScore}, opponent: ${view.opponentScore}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Button(onClick = onRematch) { Text(rematchLabel) }
    }
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
