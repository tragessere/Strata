package com.swordfish.lemuroid.common.graphics

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.view.PixelCopy
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.swordfish.lemuroid.common.kotlin.runCatchingWithRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Copies part of what the view is showing into a bitmap no longer than [maxResolution] on its
 * longest side, or null if the surface could not be read.
 *
 * [sourceBounds] is the region to copy, as fractions of the surface's width and height. A view that
 * fills the window while drawing its content into a box within it wants that box: the rest is
 * whatever the view clears to, and copying it would spend the bitmap on that and leave the content
 * sitting off center in the result.
 *
 * Restricted to [android.os.Build.VERSION_CODES.O] or higher to more efficiently grab a smaller bitmap.
 */
suspend fun GLSurfaceView.takeScreenshot(
    maxResolution: Int,
    sourceBounds: RectF,
    retries: Int = 1,
): Bitmap? =
    withContext(Dispatchers.Main) {
        runCatchingWithRetry(retries) {
            takeScreenshot(maxResolution, sourceBounds)
        }.getOrNull()
    }

private suspend fun GLSurfaceView.takeScreenshot(
    maxResolution: Int,
    sourceBounds: RectF,
): Bitmap? =
    suspendCancellableCoroutine { cont ->
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        queueEvent {
            try {
                // Resolved against the surface rather than the view: the two normally agree, and
                // where they do not it is the surface the copy is made from.
                val source = sourceBounds.toPixels(holder.surfaceFrame)
                if (source.isEmpty) {
                    cont.resume(null)
                    return@queueEvent
                }

                val outputScaling = maxResolution / maxOf(source.width(), source.height()).toFloat()
                val inputScaling = outputScaling * 2

                val inputBitmap =
                    createBitmap(
                        scaled(source.width(), inputScaling),
                        scaled(source.height(), inputScaling),
                    )

                val onCompleted = { result: Int ->
                    if (result == PixelCopy.SUCCESS) {
                        // This rescaling limits the artifacts introduced by shaders.
                        val outputBitmap =
                            inputBitmap.scale(
                                scaled(source.width(), outputScaling),
                                scaled(source.height(), outputScaling),
                            )

                        cont.resume(outputBitmap)
                    } else {
                        cont.resumeWithException(RuntimeException("Cannot take screenshot. Error code: $result"))
                    }
                }
                PixelCopy.request(this, source, inputBitmap, onCompleted, handler)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

/** Resolves fractions of a surface into the pixels of that surface, clamped to it. */
private fun RectF.toPixels(surface: Rect): Rect {
    val width = surface.width()
    val height = surface.height()
    return Rect(
        (left * width).roundToInt().coerceIn(0, width),
        (top * height).roundToInt().coerceIn(0, height),
        (right * width).roundToInt().coerceIn(0, width),
        (bottom * height).roundToInt().coerceIn(0, height),
    )
}

/** Scales one side, holding it to the one pixel a bitmap has to have at the least. */
private fun scaled(
    value: Int,
    scaling: Float,
): Int = (value * scaling).roundToInt().coerceAtLeast(1)
