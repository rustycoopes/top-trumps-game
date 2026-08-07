package com.toptrumps.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * A card, face-down until it flips to [front] — used both for the opponent's card (auto-starts,
 * [started] left at its default `true`) and the local hero card (issue #47: face-down until the
 * player taps it, caller flips [started] `true` in response to that tap). Face-down until
 * [skipAnimation] is false and [started] is true — for the opponent, [InProgressScreen]'s
 * `when (round)` branch swap means a fresh mount *is* "just resolved" (Compose disposes the
 * awaiting-choice composition and starts this one from scratch every time a round resolves), so
 * `started = true` from the first composition is the whole trigger; no round-number bookkeeping
 * needed. Driven by [Animatable] rather than `animateFloatAsState` so a hard-cut is a `snapTo`,
 * not an animation to skip framing around (WBS design notes).
 *
 * Takes two content slots — [back]/[front] — instead of `RemoteCardFace`/`deckId`/`ImageLoader`/
 * `size: Dp` (card-visual-identity TDD decision 4): this composable no longer knows what a card
 * is, and the caller is responsible for building both faces from the *same* `CardGeometry`
 * instance so front/back are pixel-identical.
 *
 * Both faces are composed unconditionally, into the same `Box`, with visibility driven from
 * inside a `graphicsLayer {}` lambda rather than an `if (rotation.value <= 90f)` branch in the
 * composable body — the previous branch-swap re-triggered the whole subtree (~25 recompositions
 * per flip once the leaf became a five-row stat table instead of one `AsyncImage`). A bonus of
 * composing both faces from the start: the front's photo decodes during the back-facing half of
 * the flip, fixing the empty-window flash on the first reveal frames.
 *
 * [rotation] itself carries no key — it resets only because both current callers happen to be
 * fully disposed and remounted every time the card they show changes identity: for both the
 * opponent and the hero card, a genuinely new deal always passes through `RemoteRoundState
 * .Resolved` first, and `InProgressScreen`'s `when (round)` branch swap disposes whichever of
 * `HeroCard`/`RevealPair` was composing before starting the other fresh (`HeroCard`'s own
 * `remember(view.self.id)` for `started`/`revealed` is redundant-but-defensive on top of that, not
 * what causes the remount). If a future caller ever reused one `FlippableCard` instance across a
 * card change without a remount in between, `rotation` would need its own key to avoid rendering
 * the new content already-flipped from the previous card's finished animation.
 */
@Composable
internal fun FlippableCard(
    skipAnimation: Boolean,
    started: Boolean = true,
    modifier: Modifier = Modifier,
    onRevealed: () -> Unit = {},
    back: @Composable (Modifier) -> Unit,
    front: @Composable (Modifier) -> Unit,
) {
    val rotation = remember { Animatable(0f) }
    val density = LocalDensity.current

    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
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
        Box(modifier = Modifier.graphicsLayer { alpha = if (rotation.value <= 90f) 1f else 0f }) {
            back(Modifier)
        }
        Box(
            modifier = Modifier.graphicsLayer {
                // Past the crossing, this layer is being viewed from behind — without the extra
                // 180° the revealed face would render mirrored (WBS design notes).
                alpha = if (rotation.value > 90f) 1f else 0f
                rotationY = 180f
            },
        ) {
            front(Modifier)
        }
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

/**
 * [content] collapses what used to be four fields (`deckId`, `imageFile`, `name`, `imageLoader`)
 * into one lambda — the caller's card composable carries its own geometry, so [SlideOverlay] never
 * sizes it (card-visual-identity TDD decision 4). [start]'s size is the authoritative box size for
 * the whole flight: the source card was built from the same geometry [content] rebuilds, so it's
 * already exactly right, and re-measuring [content] here would be redundant.
 */
internal data class SlidingCard(
    val id: Long,
    val start: Rect,
    val end: Rect,
    val progress: Animatable<Float, *>,
    val content: @Composable (Modifier) -> Unit,
)

/** Owns the in-flight slide animations for one [InProgressScreen] — a round can win both cards at once, so this is a list, not a single slot. */
internal class SlideOverlayState {
    val items = mutableStateListOf<SlidingCard>()
    private var nextId = 0L

    /**
     * Animates a copy of the card from [start] to [end] and removes itself when done — the real
     * UI already reflects the new pile counts immediately, this is purely the visual travel. The
     * `finally` matters: this is cancelled mid-flight whenever the resolved round is disposed (a
     * player advancing rounds fast enough to outrun the ~450ms animation), and without it a
     * cancelled slide would leave a permanently frozen card in [items] for the rest of the match —
     * `slideOverlay` outlives any one round.
     */
    suspend fun slide(start: Rect, end: Rect, content: @Composable (Modifier) -> Unit) {
        val progress = Animatable(0f)
        val item = SlidingCard(nextId++, start, end, progress, content)
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
 *
 * `transformOrigin(0f, 0f)` scales each card toward its own top-left, which was invisible at
 * 220dp square but drifts badly at reveal size (~186dp+) if the *top-left* is what's lerped
 * between start/end — the visual centre would crawl toward the top-left as the card shrinks.
 * Lerping *centres* instead, then subtracting the scaled half-size inside the same `offset {}`
 * lambda, keeps the shrink visually centred without touching the origin itself.
 */
@Composable
internal fun SlideOverlay(state: SlideOverlayState, overlayOrigin: Rect?) {
    if (overlayOrigin == null) return
    state.items.forEach { item ->
        val startCenterLocal = item.start.center - overlayOrigin.topLeft
        val endCenterLocal = item.end.center - overlayOrigin.topLeft
        val halfSize = item.start.size.center
        Box(
            modifier = Modifier
                .offset {
                    val progress = item.progress.value
                    val scale = 1f - 0.65f * progress
                    val center = startCenterLocal + (endCenterLocal - startCenterLocal) * progress
                    val topLeft = center - Offset(halfSize.x * scale, halfSize.y * scale)
                    IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt())
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
            item.content(Modifier)
        }
    }
}

/** The centre of a size treated as a vector from (0,0) — i.e. half-width/half-height, named for its use in [SlideOverlay]'s centre-based lerp. */
private val Size.center: Offset
    get() = Offset(width / 2f, height / 2f)
