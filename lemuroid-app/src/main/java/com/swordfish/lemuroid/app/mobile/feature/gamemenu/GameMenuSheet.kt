package com.swordfish.lemuroid.app.mobile.feature.gamemenu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val ENTER_ANIMATION = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)
private val EXIT_ANIMATION = tween<Float>(durationMillis = 200, easing = FastOutLinearInEasing)

/** How far down the sheet has to be dragged before letting go dismisses it. */
private const val DRAG_DISMISS_FRACTION = 0.4f

/** Downwards fling speed, in pixels per second, that dismisses regardless of how far it moved. */
private const val DRAG_DISMISS_VELOCITY = 1000f

/** A frame gap under this is taken as the pipeline keeping up, so animations will look smooth. */
private const val STEADY_FRAME_NANOS = 32_000_000L

/** Upper bound on the wait, so a device that never reaches cadence still opens the menu. */
private const val MAX_WARMUP_NANOS = 500_000_000L

/** How dark the game behind the sheet gets when it cannot be blurred. */
private const val DIM_SCRIM_ALPHA = 0.75f

/**
 * How dark the game gets when it is blurred as well. The blur alone is not enough separation from a
 * bright game, but it does most of the work, so this only has to knock the brightness back.
 */
private const val BLUR_SCRIM_ALPHA = 0.3f

@Stable
class GameMenuSheetState {
    /** How far the sheet currently sits below its resting position, in pixels. */
    internal val offset = Animatable(0f)

    /** The scrim fades on its own so that the sheet itself can slide without any cross fade. */
    internal val scrimAlpha = Animatable(0f)

    internal var heightPx by mutableFloatStateOf(0f)

    internal var hasEntered by mutableStateOf(false)

    /** Slides the sheet back down below the bottom edge and suspends until it is gone. */
    suspend fun hide() =
        coroutineScope {
            launch { scrimAlpha.animateTo(0f, EXIT_ANIMATION) }
            if (heightPx > 0f) {
                offset.animateTo(heightPx, EXIT_ANIMATION)
            }
        }

    /** Fades the scrim up while the sheet slides in, and suspends until both have finished. */
    internal suspend fun enter() =
        coroutineScope {
            launch { scrimAlpha.animateTo(1f, ENTER_ANIMATION) }
            settle()
        }

    internal suspend fun settle() {
        offset.animateTo(0f, ENTER_ANIMATION)
    }
}

@Composable
fun rememberGameMenuSheetState(): GameMenuSheetState = remember { GameMenuSheetState() }

/**
 * Suspends until frames are arriving at a steady cadence, or until the wait has gone on long
 * enough that it is not worth holding the menu back any further.
 *
 * The first time the menu opens, the process still has to load this activity, spin up Compose for
 * its window and lay out the whole sheet, which can stall the main thread for a few hundred
 * milliseconds. Animations advance against wall clock frame times, so starting the slide during
 * that stall spends the entire tween inside one dropped frame and the sheet simply appears at its
 * resting position. Later openings find everything warm, which is why only the first one looks
 * wrong. Waiting for the pipeline to catch up costs nothing visually, because the sheet is parked
 * off screen until the slide begins.
 */
private suspend fun awaitSteadyFrames() {
    val start = withFrameNanos { it }
    var previous = start
    while (true) {
        val current = withFrameNanos { it }
        if (current - previous < STEADY_FRAME_NANOS || current - start > MAX_WARMUP_NANOS) return
        previous = current
    }
}

/**
 * A bottom sheet that slides in and out with no cross fade.
 *
 * Material's ModalBottomSheet renders into its own dialog window, and Material themes that window
 * with the platform dialog animation. The window fades in while the sheet slides up, and because
 * the dialog is shown from a DisposableEffect before any content composes there is no point at
 * which the animation can be cleared in time. Drawing in the activity's own window instead leaves
 * the slide as the only motion.
 *
 * The game behind is covered by a blurred capture of itself, with a light scrim over the blur for
 * contrast, and falls back to a plain dim if there was no frame to capture. Both follow the same
 * animation, so the background settles as the sheet arrives.
 *
 * The scrim is drawn here rather than asked for with the window's backgroundDim, because the
 * activity opens with no transition at all: a window animation is the only thing that would fade
 * the dim in, and it fades the sheet along with it. Fading a scrim of our own keeps the dim
 * gradual while the sheet does nothing but slide. Taps on it dismiss the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameMenuSheet(
    state: GameMenuSheetState,
    onDismissRequest: () -> Unit,
    sheetMaxWidth: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    val background = rememberGameMenuBackground()

    // The sheet is content sized, so its travel is only known once it has been measured. It is
    // parked off screen until then and slides up from there. This must not be keyed on the height:
    // window insets land a few frames in and resize the sheet, which would cancel the animation
    // partway and leave the sheet stranded off screen.
    LaunchedEffect(Unit) {
        snapshotFlow { state.heightPx }.first { it > 0f }
        awaitSteadyFrames()
        state.offset.snapTo(state.heightPx)
        state.hasEntered = true
        state.enter()
    }

    val scrimAlpha = if (background != null) BLUR_SCRIM_ALPHA else DIM_SCRIM_ALPHA

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (background != null) {
            BackgroundBlur(background) { state.scrimAlpha.value }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = state.scrimAlpha.value }
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest,
                    ),
        )

        Surface(
            modifier =
                Modifier
                    .widthIn(max = sheetMaxWidth)
                    .fillMaxWidth()
                    .onSizeChanged { state.heightPx = it.height.toFloat() }
                    .graphicsLayer {
                        translationY = if (state.hasEntered) state.offset.value else state.heightPx
                        alpha = if (state.heightPx > 0f) 1f else 0f
                    },
            shape = BottomSheetDefaults.ExpandedShape,
            color = BottomSheetDefaults.ContainerColor,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .draggable(
                                orientation = Orientation.Vertical,
                                state =
                                    rememberDraggableState { delta ->
                                        coroutineScope.launch {
                                            val target = state.offset.value + delta
                                            state.offset.snapTo(target.coerceIn(0f, state.heightPx))
                                        }
                                    },
                                onDragStopped = { velocity ->
                                    val draggedFarEnough =
                                        state.offset.value > state.heightPx * DRAG_DISMISS_FRACTION
                                    if (draggedFarEnough || velocity > DRAG_DISMISS_VELOCITY) {
                                        onDismissRequest()
                                    } else {
                                        state.settle()
                                    }
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    BottomSheetDefaults.DragHandle()
                }
                content()
            }
        }
    }
}
