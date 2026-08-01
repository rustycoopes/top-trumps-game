package com.toptrumps.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import com.toptrumps.session.RemoteCardFace
import kotlin.math.roundToInt

private const val FLIP_DURATION_MS = 420
private const val SLIDE_DURATION_MS = 450

/**
 * Decides whether the *next* reveal/slide/sound should hard-cut instead of animate, from two
 * independent signals — collapsing them into one latch (an earlier version of this class did)
 * caused a real bug: a boolean that only gets set back to "safe to animate" by a recomposition
 * stays wrongly stuck at "hard-cut" if backgrounding happens to coincide with a period where
 * nothing else changes to trigger one.
 *
 * - [hasSeenAwaitingChoice]: set once [InProgressScreen] observes the round actually
 *   `AwaitingChoice`, and never cleared again for this gate's lifetime. `false` means *this
 *   mount's first-ever content was already a resolved round or a finished match* — the signature
 *   of [MatchScreen] remounting straight into one, which only happens after a two-device
 *   reconnect (since [TwoDeviceMatchScreen] swaps away from [MatchScreen] entirely while the peer
 *   is unreachable) or a resync. A brand-new match always starts in `AwaitingChoice`, so this
 *   never produces a false hard-cut for one.
 * - [consumeBackgroundedFlag]: `true` for exactly one consumption after `ON_STOP` — re-armed by
 *   the lifecycle observer, independent of whatever else recomposes or doesn't. This is what makes
 *   the very next transition after resuming hard-cut and every transition after *that* animate
 *   normally again, regardless of whether anything happened to recompose in between.
 *
 * [ResolvedContent] and [ResultScreen] each call [shouldHardCut] exactly once, from a `remember`
 * or `LaunchedEffect(Unit)` that runs on that composable's first (and only, per round/match-end)
 * composition — see their call sites for why a fresh mount is reliably "the event" here.
 */
@Composable
internal fun rememberAnimationGate(): AnimationGate {
    val gate = remember { AnimationGate() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) gate.rearm()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return gate
}

internal class AnimationGate {
    var hasSeenAwaitingChoice: Boolean = false
    private var backgroundedSinceLastConsume: Boolean = false

    fun rearm() {
        backgroundedSinceLastConsume = true
    }

    fun shouldHardCut(): Boolean {
        val backgrounded = backgroundedSinceLastConsume
        backgroundedSinceLastConsume = false
        return !hasSeenAwaitingChoice || backgrounded
    }
}

/**
 * The opponent's card, face-down until [skipAnimation] is false and this composable is freshly
 * mounted — [InProgressScreen]'s `when (round)` branch swap means a fresh mount *is* "just
 * resolved" (Compose disposes [AwaitingChoiceContent]'s composition and starts this one from
 * scratch every time a round resolves), so a plain `LaunchedEffect(Unit)` is the whole trigger;
 * no round-number bookkeeping needed. Driven by [Animatable] rather than `animateFloatAsState` so
 * a hard-cut is a `snapTo`, not an animation to skip framing around (WBS design notes).
 */
@Composable
internal fun FlippableOpponentCard(
    card: RemoteCardFace,
    deckId: String,
    imageLoader: ImageLoader,
    size: Dp,
    skipAnimation: Boolean,
    modifier: Modifier = Modifier,
    onRevealed: () -> Unit = {},
) {
    val rotation = remember { Animatable(0f) }
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        if (skipAnimation) {
            rotation.snapTo(180f)
        } else {
            rotation.animateTo(180f, animationSpec = tween(FLIP_DURATION_MS, easing = FastOutSlowInEasing))
        }
        onRevealed()
    }

    Box(
        modifier = modifier.graphicsLayer {
            rotationY = rotation.value
            cameraDistance = 14f * density.density
        },
    ) {
        if (rotation.value <= 90f) {
            CardBack(size = size)
        } else {
            // Past the crossing, this layer is being viewed from behind — without the extra 180°
            // the revealed face would render mirrored (WBS design notes).
            Box(modifier = Modifier.graphicsLayer { rotationY = 180f }) {
                CardImage(deckId, card.imageFile, card.name, imageLoader, size = size)
            }
        }
    }
}

/** A face-down card — no back-of-card art exists yet (see `design/assets.csv`'s ungenerated `card_back`), so this is a plain placeholder standing in for one. */
@Composable
internal fun CardBack(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color(0xFF23395D), RoundedCornerShape(8.dp))
            .border(2.dp, Color(0xFFB08D57), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("?", color = Color.White)
    }
}

/**
 * Global (root-coordinate) positions the slide-to-pile overlay needs — one instance shared by
 * [InProgressScreen] and its children via parameter passing, not a `CompositionLocal`, since only
 * this one screen ever needs it.
 */
internal class CardSlotPositions {
    var selfCard: Rect? by mutableStateOf(null)
    var opponentCard: Rect? by mutableStateOf(null)
    var myPileLabel: Rect? by mutableStateOf(null)
    var opponentLabel: Rect? by mutableStateOf(null)
}

/** Records `this` composable's root-relative bounds into [onPositioned] on every layout pass — the capture side of the slide overlay's start/target rects. */
internal fun Modifier.reportGlobalPosition(onPositioned: (Rect) -> Unit): Modifier =
    onGloballyPositioned { onPositioned(it.boundsInRoot()) }

internal data class SlidingCard(
    val id: Long,
    val deckId: String,
    val imageFile: String,
    val name: String,
    val imageLoader: ImageLoader,
    val start: Rect,
    val end: Rect,
    val progress: Animatable<Float, *>,
)

/** Owns the in-flight slide animations for one [InProgressScreen] — a round can win both cards at once, so this is a list, not a single slot. */
internal class SlideOverlayState {
    val items = mutableStateListOf<SlidingCard>()
    private var nextId = 0L

    /**
     * Animates a copy of the card from [start] to [end] and removes itself when done — the real
     * UI already reflects the new pile counts immediately, this is purely the visual travel. The
     * `finally` matters: this is cancelled mid-flight whenever [ResolvedContent] is disposed (a
     * player advancing rounds fast enough to outrun the ~450ms animation), and without it a
     * cancelled slide would leave a permanently frozen card in [items] for the rest of the match —
     * `slideOverlay` outlives any one round.
     */
    suspend fun slide(deckId: String, imageFile: String, name: String, imageLoader: ImageLoader, start: Rect, end: Rect) {
        val progress = Animatable(0f)
        val item = SlidingCard(nextId++, deckId, imageFile, name, imageLoader, start, end, progress)
        items.add(item)
        try {
            progress.animateTo(1f, animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing))
        } finally {
            items.remove(item)
        }
    }
}

/**
 * Renders every in-flight [SlideOverlayState.items] entry — must be placed inside a `Box` that
 * also contains whatever [overlayOrigin] was captured from, so root-relative rects convert to that
 * box's local coordinate space. Every per-frame value ([SlidingCard.progress]) is read *inside*
 * the `offset {}`/`graphicsLayer {}` lambdas, not in this composable's own body — reading it here
 * instead would subscribe this whole `forEach` to recompose on every animation tick, the exact
 * mistake the WBS design notes call out.
 */
@Composable
internal fun SlideOverlay(state: SlideOverlayState, overlayOrigin: Rect?) {
    if (overlayOrigin == null) return
    val density = LocalDensity.current
    state.items.forEach { item ->
        val startLocal = item.start.topLeft - overlayOrigin.topLeft
        val endLocal = item.end.topLeft - overlayOrigin.topLeft
        Box(
            modifier = Modifier
                .offset {
                    val current = startLocal + (endLocal - startLocal) * item.progress.value
                    IntOffset(current.x.roundToInt(), current.y.roundToInt())
                }
                .graphicsLayer {
                    val progress = item.progress.value
                    // Shrinks toward the pile the whole way, but only starts fading in the final
                    // third — a card that's visibly still a card for most of the flight reads
                    // better than one that dissolves immediately.
                    val scale = 1f - 0.65f * progress
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - ((progress - 0.7f).coerceIn(0f, 0.3f) / 0.3f)
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            CardImage(item.deckId, item.imageFile, item.name, item.imageLoader, size = with(density) { item.start.width.toDp() })
        }
    }
}
