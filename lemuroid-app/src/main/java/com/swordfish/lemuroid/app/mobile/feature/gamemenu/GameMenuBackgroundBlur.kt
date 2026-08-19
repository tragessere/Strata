package com.swordfish.lemuroid.app.mobile.feature.gamemenu

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Longest side, in pixels, of the frame captured for the menu background.
 *
 * The frame is only ever shown blurred, so this is deliberately tiny: it keeps the readback cheap,
 * and stretching that few hundred pixels back over the screen is already most of the blur.
 */
internal const val BACKGROUND_CAPTURE_SIZE = 256

/**
 * The platform version from which the menu blurs the game behind it rather than dimming it.
 *
 * Compose draws the blur with a render effect, which the platform only offers from Android 12, and
 * on anything older the modifier is quietly ignored. Capturing there would lay an unblurred still
 * of the game over the live one, under the lighter scrim that only a blur earns, so the capture is
 * never asked for and the menu takes the plain dim it already falls back to.
 */
internal const val BACKGROUND_BLUR_MIN_SDK = Build.VERSION_CODES.S

/** Ceiling on how long opening the menu waits for a capture before going ahead without one. */
internal const val BACKGROUND_CAPTURE_TIMEOUT_MS = 300L

/** Softens the edges the capture is stretched into, so it reads as a blur and not as low res. */
private val BLUR_RADIUS = 16.dp

/**
 * How far past every edge of the screen the blurred copy is drawn.
 *
 * A blur reads pixels from around every one it produces, and at the edges of the layer it draws
 * into there are none to read, so the result fades out towards them whichever edge treatment is
 * asked for. Drawing into a layer this much larger than the screen leaves that fade outside it,
 * and the fade reaches further in than the nominal radius, so the margin is twice that to clear
 * it. What fills the margin is the capture's edge pixels held outwards, not the capture scaled up:
 * the game is still running underneath, and a blur drawn any larger than it would sit visibly out
 * of register with it.
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
    // Clamping is what gives the margin its content: sampled past its edges the capture keeps
    // returning the colour of the nearest edge pixel, so the blur always has something to read.
    val capture =
        remember(image) {
            ShaderBrush(ImageShader(image, TileMode.Clamp, TileMode.Clamp))
        }

    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .inflatedBy(BLUR_OVERSCAN)
                    .graphicsLayer { this.alpha = alpha() }
                    .blur(BLUR_RADIUS, BlurredEdgeTreatment.Rectangle),
        ) {
            val margin = BLUR_OVERSCAN.toPx()
            val screen = Size(size.width - margin * 2f, size.height - margin * 2f)

            // The capture lands on the screen exactly where ContentScale.Crop would have put it,
            // which is what keeps it registered with the game running underneath. Only the margin
            // around it is new, and it is clipped away before it is ever seen.
            val scale = maxOf(screen.width / image.width, screen.height / image.height)
            val left = margin + (screen.width - image.width * scale) / 2f
            val top = margin + (screen.height - image.height * scale) / 2f

            withTransform({
                translate(left, top)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                // Drawn in the capture's own pixels, out to wherever the layer's corners land.
                drawRect(
                    brush = capture,
                    topLeft = Offset(-left, -top) / scale,
                    size = size / scale,
                )
            }
        }
    }
}

/**
 * Measures the content [margin] larger than the space it was given on every side and draws it from
 * behind the top left corner, while still reporting the original size to whatever is above.
 */
private fun Modifier.inflatedBy(margin: Dp) =
    layout { measurable, constraints ->
        val inset = margin.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeable = measurable.measure(Constraints.fixed(width + inset * 2, height + inset * 2))
        layout(width, height) { placeable.place(-inset, -inset) }
    }
