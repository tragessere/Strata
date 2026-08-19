package com.swordfish.lemuroid.app.mobile.feature.gamemenu

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Longest side, in pixels, of the frame captured for the menu background.
 *
 * The frame is only ever shown blurred, so this is deliberately tiny: it keeps the readback cheap,
 * and stretching that few hundred pixels back over the screen is already most of the blur.
 */
internal const val BACKGROUND_CAPTURE_SIZE = 256

/** Ceiling on how long opening the menu waits for a capture before going ahead without one. */
internal const val BACKGROUND_CAPTURE_TIMEOUT_MS = 300L

/** Softens the edges the capture is stretched into, so it reads as a blur and not as low res. */
private val BLUR_RADIUS = 16.dp

/**
 * How far the blurred copy is scaled out past each edge of the screen.
 *
 * A blur reads pixels from around every one it produces, and at the edges of the layer it draws
 * into there are none to read, so the result fades out towards them whichever edge treatment is
 * asked for. The fade reaches further in than the nominal radius, so the margin is twice that to
 * clear it. What it costs is a slightly tighter crop of a background that is only ever a blur, and
 * the eye has nothing to measure that against.
 */
private val BLUR_OVERSCAN = BLUR_RADIUS * 2

/**
 * The frame captured from the game, handed from the game activity to the menu activity.
 *
 * The menu is an activity of its own, so the game is not part of the hierarchy the sheet draws into
 * and cannot be reached with a render effect. Cross window blurs could reach it, but they are a
 * platform feature the system is free to withhold, and plenty of devices do: Samsung phones report
 * them unsupported outright. Capturing a frame and blurring that works the same everywhere.
 *
 * Both activities run in the same process, so the bitmap is passed directly rather than through the
 * intent, which could not carry anything this size across a binder transaction anyway.
 */
internal object GameMenuBackground {
    @Volatile
    private var frame: Bitmap? = null

    fun set(bitmap: Bitmap?) {
        frame = bitmap
    }

    fun clear() {
        frame = null
    }

    fun peek(): Bitmap? = frame
}

/**
 * The frame captured as the menu opened, or null if there was none to capture.
 *
 * Read once and held for as long as the menu is up, so that clearing the handover on the game side
 * cannot pull the background out from under a menu that is still on screen.
 */
@Composable
internal fun rememberGameMenuBackground(): ImageBitmap? = remember { GameMenuBackground.peek()?.asImageBitmap() }

/**
 * Draws the captured frame blurred over the whole screen, fading in as [alpha] goes from 0 to 1.
 *
 * The game is still live behind this translucent window, so the fade cross dissolves the real game
 * into its blurred copy rather than dropping an image on top of it. The game is paused while the
 * menu is up, which is what lets a still frame stand in for it.
 *
 * Cropping rather than fitting matters on rotation: the menu absorbs configuration changes and
 * keeps the frame it opened with, and cropping re-frames it to the new aspect instead of stretching
 * it out of shape.
 */
@Composable
internal fun BackgroundBlur(
    image: ImageBitmap,
    alpha: () -> Float,
) {
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        this.alpha = alpha()
                        // Scaling the blurred result up carries its faded edges off the screen,
                        // where the box above clips them away. The shorter side decides the scale,
                        // since it is the one the margin is the larger fraction of.
                        val scale = 1f + 2f * BLUR_OVERSCAN.toPx() / minOf(size.width, size.height)
                        scaleX = scale
                        scaleY = scale
                    }.blur(BLUR_RADIUS, BlurredEdgeTreatment.Rectangle),
        )
    }
}
