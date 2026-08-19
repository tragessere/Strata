@file:RequiresApi(Build.VERSION_CODES.O)

package com.swordfish.lemuroid.common.graphics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Copies what the window is showing right now into a bitmap no longer than [maxDimension] on its
 * longest side, or null if nothing could be read.
 *
 * The copy is made by the compositor rather than by drawing the view hierarchy, so that it picks up
 * content the hierarchy never draws. That matters most for the one thing a view based copy always
 * misses: a SurfaceView's pixels do not pass through the hierarchy's canvas at all, and the window
 * buffer holds a transparent hole where the SurfaceView sits rather than a copy of it.
 *
 * Which is why [surfaceView] is copied separately and drawn underneath. The window copy goes on top
 * of it, so it shows through the hole while anything drawn over the SurfaceView, such as controls
 * laid across the game, still covers it. If the platform ever does include SurfaceView content in
 * the window copy, the two simply agree and the result is the same.
 *
 * Asking for small destinations keeps the readback cheap, since the scaling down happens on the GPU
 * as part of each copy.
 */
suspend fun Window.captureFrame(
    maxDimension: Int,
    surfaceView: SurfaceView?,
): Bitmap? =
    withContext(Dispatchers.Main) {
        val decorView = peekDecorView() ?: return@withContext null
        if (decorView.width <= 0 || decorView.height <= 0) return@withContext null

        val scale = maxDimension / maxOf(decorView.width, decorView.height).toFloat()
        val frame =
            createBitmap(
                scaledSize(decorView.width, scale),
                scaledSize(decorView.height, scale),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(frame)

        val surfaceCopied = surfaceView?.let { drawSurfaceView(canvas, it, scale) } ?: false
        val windowCopied = drawWindow(canvas, scale)

        frame.takeIf { surfaceCopied || windowCopied }
    }

/** Copies the surface view and draws it where it sits within the window. */
private suspend fun drawSurfaceView(
    canvas: Canvas,
    surfaceView: SurfaceView,
    scale: Float,
): Boolean {
    if (surfaceView.width <= 0 || surfaceView.height <= 0) return false

    val destination =
        createBitmap(
            scaledSize(surfaceView.width, scale),
            scaledSize(surfaceView.height, scale),
            Bitmap.Config.ARGB_8888,
        )

    val copy =
        awaitPixelCopy(destination) { bitmap, listener, handler ->
            PixelCopy.request(surfaceView, bitmap, listener, handler)
        } ?: return false

    val location = IntArray(2)
    surfaceView.getLocationInWindow(location)

    canvas.drawBitmap(
        copy,
        null,
        Rect(
            scaledOffset(location[0], scale),
            scaledOffset(location[1], scale),
            scaledOffset(location[0] + surfaceView.width, scale),
            scaledOffset(location[1] + surfaceView.height, scale),
        ),
        Paint(Paint.FILTER_BITMAP_FLAG),
    )
    return true
}

/** Copies the window's own buffer over whatever has already been drawn. */
private suspend fun Window.drawWindow(
    canvas: Canvas,
    scale: Float,
): Boolean {
    val decorView = peekDecorView() ?: return false
    val destination =
        createBitmap(
            scaledSize(decorView.width, scale),
            scaledSize(decorView.height, scale),
            Bitmap.Config.ARGB_8888,
        )

    val copy =
        awaitPixelCopy(destination) { bitmap, listener, handler ->
            PixelCopy.request(this, bitmap, listener, handler)
        } ?: return false

    canvas.drawBitmap(copy, 0f, 0f, null)
    return true
}

private suspend fun awaitPixelCopy(
    destination: Bitmap,
    request: (Bitmap, PixelCopy.OnPixelCopyFinishedListener, Handler) -> Unit,
): Bitmap? =
    suspendCancellableCoroutine { continuation ->
        val listener =
            PixelCopy.OnPixelCopyFinishedListener { result ->
                continuation.resume(destination.takeIf { result == PixelCopy.SUCCESS })
            }
        // A source without a valid surface throws rather than reporting a failed copy.
        runCatching {
            request(destination, listener, Handler(Looper.getMainLooper()))
        }.onFailure {
            Timber.w(it, "Pixel copy request rejected")
            continuation.resume(null)
        }
    }

/** Scales a size, holding it to the one pixel a bitmap has to have at the least. */
private fun scaledSize(
    value: Int,
    scale: Float,
): Int = (value * scale).roundToInt().coerceAtLeast(1)

/** Scales a coordinate, which unlike a size is free to land on zero. */
private fun scaledOffset(
    value: Int,
    scale: Float,
): Int = (value * scale).roundToInt()
