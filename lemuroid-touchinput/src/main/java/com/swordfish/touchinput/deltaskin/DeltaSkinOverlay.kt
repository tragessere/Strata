package com.swordfish.touchinput.deltaskin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import gg.padkit.config.HapticFeedbackType
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a Delta controller skin: draws the (letterboxed) skin artwork, drives all controls from a
 * single overlay-wide multitouch handler, and reports the rectangle where the emulator video should
 * be shown.
 *
 * [artworkOpacity] fades the artwork so more of the video behind it shows through. It only applies to
 * representations that declare themselves `translucent`: opaque artwork is drawn over the whole screen
 * (or over black in portrait), so fading it would just reveal what the skin means to hide.
 *
 * Coordinate semantics (matching Delta):
 * - When the skin defines `screens`, `mappingSize` is the full canvas: it is letterboxed to fit the
 *   screen and the emulator video is placed at the screen's `outputFrame`.
 * - Otherwise, in portrait the skin is a bottom control strip fitted to the screen width with the
 *   video filling the space above it; in landscape the skin fills the (letterboxed) screen and the
 *   video is shown behind the (often translucent) artwork.
 */
@Composable
fun DeltaSkinOverlay(
    skinDir: File,
    representation: DeltaSkinRepresentation,
    isLandscape: Boolean,
    assetLoader: DeltaSkinAssetLoader,
    onGameScreenRect: (Rect) -> Unit,
    onButton: (keyCodes: List<Int>, pressed: Boolean) -> Unit,
    onMenu: (pressed: Boolean) -> Unit,
    onMotion: (source: Int, x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
    hapticFeedbackType: HapticFeedbackType = HapticFeedbackType.NONE,
    artworkOpacity: Float = 1f,
) {
    val density = LocalDensity.current
    val haptics = rememberSkinHapticFeedback()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenW = this.constraints.maxWidth.toFloat()
        val screenH = this.constraints.maxHeight.toFloat()
        val mapW = representation.mappingSize.width
        val mapH = representation.mappingSize.height

        if (mapW <= 0f || mapH <= 0f || screenW <= 0f || screenH <= 0f) return@BoxWithConstraints

        val layout =
            remember(screenW, screenH, mapW, mapH, isLandscape, representation) {
                computeLayout(screenW, screenH, mapW, mapH, isLandscape, representation)
            }

        LaunchedEffect(layout.gameRect) { onGameScreenRect(layout.gameRect) }

        // Background artwork.
        val assetName = representation.assets.preferredAsset()
        val targetW = layout.drawnW.roundToInt()
        val targetH = layout.drawnH.roundToInt()
        if (assetName != null) {
            val bitmap by
                produceState<android.graphics.Bitmap?>(null, skinDir, assetName, targetW, targetH) {
                    value = assetLoader.loadBitmap(skinDir, assetName, targetW, targetH)
                }
            bitmap?.let { bmp ->
                val image = remember(bmp) { bmp.asImageBitmap() }
                Box(
                    modifier =
                        Modifier
                            .absoluteOffset {
                                IntOffset(layout.originX.roundToInt(), layout.originY.roundToInt())
                            }.size(
                                width = with(density) { layout.drawnW.toDp() },
                                height = with(density) { layout.drawnH.toDp() },
                            ),
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        alpha =
                            if (representation.translucent) {
                                artworkOpacity.coerceIn(0f, 1f)
                            } else {
                                1f
                            },
                    )
                }
            }
        }

        // Classified controls with their on-screen rects, driven by one overlay-wide pointer handler.
        val controls =
            remember(layout, representation) {
                representation.items.mapNotNull { item ->
                    val control = DeltaSkinInputMapping.classify(item)
                    if (control is DeltaSkinInputMapping.Control.Unsupported) {
                        null
                    } else {
                        SkinControl(layout.mapItemRect(item), control)
                    }
                }
            }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(controls, hapticFeedbackType) {
                        dispatchSkinInput(
                            controls = controls,
                            hapticFeedbackType = hapticFeedbackType,
                            haptics = haptics,
                            onButton = onButton,
                            onMenu = onMenu,
                            onMotion = onMotion,
                        )
                    },
        )
    }
}

/** A classified skin control together with its on-screen (extended-edge) touch rect. */
private class SkinControl(
    val rect: Rect,
    val control: DeltaSkinInputMapping.Control,
)

/**
 * Overlay-wide multitouch dispatch.
 *
 * - Buttons (and analog buttons) are pressed whenever a finger is inside them, so a finger can slide
 *   from one button onto another and roll across them; releasing happens when no finger is inside.
 * - Dpads also engage on slide-over: while a finger is inside, the direction follows its position, and
 *   the dpad releases once no finger is inside.
 * - The analog thumbstick instead captures the finger that pressed *down* within its bounds and keeps
 *   it until release — sliding a finger in from elsewhere never grabs it, and it keeps tracking the
 *   finger even past its edges.
 * - The menu buzzes on touch down / slide-over like a button, but only *opens* on touch up over its
 *   region (not on down), to avoid opening it by accident.
 *
 * Haptics match PadKit: buzz on every press; buzz on release only in [HapticFeedbackType.PRESS_RELEASE].
 */
private suspend fun PointerInputScope.dispatchSkinInput(
    controls: List<SkinControl>,
    hapticFeedbackType: HapticFeedbackType,
    haptics: SkinHapticFeedback,
    onButton: (List<Int>, Boolean) -> Unit,
    onMenu: (Boolean) -> Unit,
    onMotion: (Int, Float, Float) -> Unit,
) {
    val thumbsticks = controls.filter { it.control is DeltaSkinInputMapping.Control.Thumbstick }
    val dpads = controls.filter { it.control is DeltaSkinInputMapping.Control.Dpad }
    val buttons =
        controls.filter {
            it.control is DeltaSkinInputMapping.Control.Buttons ||
                it.control is DeltaSkinInputMapping.Control.AnalogButton
        }
    val menus = controls.filter { it.control is DeltaSkinInputMapping.Control.Menu }

    val onPressHaptic: () -> Unit = { if (hapticFeedbackType != HapticFeedbackType.NONE) haptics.press() }
    val onReleaseHaptic: () -> Unit = { if (hapticFeedbackType == HapticFeedbackType.PRESS_RELEASE) haptics.release() }

    fun pressButton(button: SkinControl) {
        when (val c = button.control) {
            is DeltaSkinInputMapping.Control.Buttons -> onButton(c.keyCodes, true)
            is DeltaSkinInputMapping.Control.AnalogButton -> onMotion(c.motionSource, c.x, c.y)
            else -> {}
        }
    }

    fun releaseButton(button: SkinControl) {
        when (val c = button.control) {
            is DeltaSkinInputMapping.Control.Buttons -> onButton(c.keyCodes, false)
            is DeltaSkinInputMapping.Control.AnalogButton -> onMotion(c.motionSource, 0f, 0f)
            else -> {}
        }
    }

    // Emits the control's motion and returns the direction it emitted (snapped to 8-way for a dpad).
    fun emitDirectional(
        directional: SkinControl,
        position: Offset,
    ): Offset {
        val local = position - directional.rect.topLeft
        val w = directional.rect.width
        val h = directional.rect.height
        return when (val c = directional.control) {
            is DeltaSkinInputMapping.Control.Dpad ->
                emitDirection(local.x, local.y, w, h, snapToEightWay = true) { x, y ->
                    onMotion(ComposeTouchLayouts.MOTION_SOURCE_DPAD, x, y)
                }

            is DeltaSkinInputMapping.Control.Thumbstick ->
                emitDirection(local.x, local.y, w, h, snapToEightWay = false) { x, y ->
                    onMotion(c.motionSource, x, y)
                }

            else -> Offset.Zero
        }
    }

    fun releaseDirectional(directional: SkinControl) {
        when (val c = directional.control) {
            is DeltaSkinInputMapping.Control.Dpad -> onMotion(ComposeTouchLayouts.MOTION_SOURCE_DPAD, 0f, 0f)
            is DeltaSkinInputMapping.Control.Thumbstick -> onMotion(c.motionSource, 0f, 0f)
            else -> {}
        }
    }

    val thumbstickTracking = mutableMapOf<PointerId, SkinControl>()
    val pressedButtons = mutableSetOf<SkinControl>()
    val pressedDpads = mutableSetOf<SkinControl>()
    val dpadDirections = mutableMapOf<SkinControl, Offset>()
    val pressedMenus = mutableSetOf<SkinControl>()

    awaitPointerEventScope {
        while (true) {
            val changes = awaitPointerEvent().changes

            // A finger going down inside the thumbstick captures it for the rest of that gesture.
            for (change in changes) {
                if (change.changedToDownIgnoreConsumed()) {
                    val thumbstick = thumbsticks.firstOrNull { it.rect.contains(change.position) }
                    if (thumbstick != null) {
                        thumbstickTracking[change.id] = thumbstick
                        onPressHaptic()
                        emitDirectional(thumbstick, change.position)
                    }
                }
            }

            // Menu opens on touch up over a menu region (thumbstick-captured fingers are ignored). The
            // press/release buzz is handled below in the slide-over pass, so it fires on touch down too.
            for (change in changes) {
                if (change.changedToUpIgnoreConsumed() && change.id !in thumbstickTracking) {
                    if (menus.any { it.rect.contains(change.position) }) {
                        onMenu(true)
                    }
                }
            }

            // Feed / release thumbstick-captured fingers (tracked even past the control's edges).
            val releasedThumbsticks = mutableListOf<PointerId>()
            for ((id, thumbstick) in thumbstickTracking) {
                val change = changes.firstOrNull { it.id == id } ?: continue
                if (change.pressed) {
                    emitDirectional(thumbstick, change.position)
                } else {
                    releaseDirectional(thumbstick)
                    onReleaseHaptic()
                    releasedThumbsticks += id
                }
            }
            releasedThumbsticks.forEach { thumbstickTracking.remove(it) }

            // Fingers that are not captured by the thumbstick drive dpads and buttons via slide-over.
            val freeChanges = changes.filter { it.pressed && it.id !in thumbstickTracking }

            // Dpads engage while a finger is inside; the direction follows that finger's position, and
            // a buzz also fires each time the snapped direction changes (e.g. sliding left to right).
            for (dpad in dpads) {
                val finger = freeChanges.firstOrNull { dpad.rect.contains(it.position) }
                val wasPressed = dpad in pressedDpads
                if (finger != null) {
                    val direction = emitDirectional(dpad, finger.position)
                    if (!wasPressed) {
                        pressedDpads += dpad
                        onPressHaptic()
                    } else if (direction != dpadDirections[dpad] && direction != Offset.Zero) {
                        onPressHaptic()
                    }
                    dpadDirections[dpad] = direction
                } else if (wasPressed) {
                    pressedDpads -= dpad
                    dpadDirections.remove(dpad)
                    releaseDirectional(dpad)
                    onReleaseHaptic()
                }
            }

            // Buttons are pressed whenever a free finger is inside them (slide-over).
            for (button in buttons) {
                val inside = freeChanges.any { button.rect.contains(it.position) }
                val wasPressed = button in pressedButtons
                if (inside && !wasPressed) {
                    pressedButtons += button
                    onPressHaptic()
                    pressButton(button)
                } else if (!inside && wasPressed) {
                    pressedButtons -= button
                    onReleaseHaptic()
                    releaseButton(button)
                }
            }

            // Menus buzz on enter/leave like buttons (their open action happens on touch up above).
            for (menu in menus) {
                val inside = freeChanges.any { menu.rect.contains(it.position) }
                val wasPressed = menu in pressedMenus
                if (inside && !wasPressed) {
                    pressedMenus += menu
                    onPressHaptic()
                } else if (!inside && wasPressed) {
                    pressedMenus -= menu
                    onReleaseHaptic()
                }
            }
        }
    }
}

/** Emits the motion for a finger position and returns the emitted (x, y) direction. */
private fun emitDirection(
    px: Float,
    py: Float,
    width: Float,
    height: Float,
    snapToEightWay: Boolean,
    onMotion: (Float, Float) -> Unit,
): Offset {
    val nx = ((px / width) * 2f - 1f).coerceIn(-1f, 1f)
    // Screen y grows downward; emulator "up" is negative y, so the sign already matches.
    val ny = ((py / height) * 2f - 1f).coerceIn(-1f, 1f)
    return if (snapToEightWay) {
        val threshold = 0.35f
        val dx = if (abs(nx) > threshold) (if (nx > 0) 1f else -1f) else 0f
        val dy = if (abs(ny) > threshold) (if (ny > 0) 1f else -1f) else 0f
        onMotion(dx, dy)
        Offset(dx, dy)
    } else {
        onMotion(nx, ny)
        Offset(nx, ny)
    }
}

private class SkinLayout(
    val scale: Float,
    val originX: Float,
    val originY: Float,
    val drawnW: Float,
    val drawnH: Float,
    val gameRect: Rect,
) {
    /** Maps an item's frame (plus its extended edges) from skin points to screen pixels. */
    fun mapItemRect(item: DeltaSkinItem): Rect {
        val f = item.frame
        val e = item.extendedEdges
        val left = originX + (f.x - e.left) * scale
        val top = originY + (f.y - e.top) * scale
        val right = originX + (f.x + f.width + e.right) * scale
        val bottom = originY + (f.y + f.height + e.bottom) * scale
        return Rect(left, top, right, bottom)
    }
}

private fun computeLayout(
    screenW: Float,
    screenH: Float,
    mapW: Float,
    mapH: Float,
    isLandscape: Boolean,
    representation: DeltaSkinRepresentation,
): SkinLayout {
    val hasScreens = representation.screens.isNotEmpty()

    if (hasScreens) {
        // Full-canvas semantics: letterbox mappingSize, place video at the output frame(s).
        val scale = min(screenW / mapW, screenH / mapH)
        val drawnW = mapW * scale
        val drawnH = mapH * scale
        val originX = (screenW - drawnW) / 2f
        val originY = (screenH - drawnH) / 2f
        val bounds = unionOutputFrames(representation)
        val gameRect =
            if (bounds != null) {
                Rect(
                    originX + bounds.x * scale,
                    originY + bounds.y * scale,
                    originX + (bounds.x + bounds.width) * scale,
                    originY + (bounds.y + bounds.height) * scale,
                )
            } else {
                Rect(originX, originY, originX + drawnW, originY + drawnH)
            }
        return SkinLayout(scale, originX, originY, drawnW, drawnH, gameRect)
    }

    if (!isLandscape) {
        // Portrait: skin is a bottom control strip fitted to the screen width; video fills the top.
        var scale = screenW / mapW
        if (mapH * scale > screenH) scale = screenH / mapH
        val drawnW = mapW * scale
        val drawnH = mapH * scale
        val originX = (screenW - drawnW) / 2f
        val originY = screenH - drawnH
        val gameRect = Rect(0f, 0f, screenW, originY)
        return SkinLayout(scale, originX, originY, drawnW, drawnH, gameRect)
    }

    // Landscape: skin fills the letterboxed screen; video shown behind the (usually translucent) art.
    val scale = min(screenW / mapW, screenH / mapH)
    val drawnW = mapW * scale
    val drawnH = mapH * scale
    val originX = (screenW - drawnW) / 2f
    val originY = (screenH - drawnH) / 2f
    val gameRect = Rect(originX, originY, originX + drawnW, originY + drawnH)
    return SkinLayout(scale, originX, originY, drawnW, drawnH, gameRect)
}

private fun unionOutputFrames(representation: DeltaSkinRepresentation): DeltaSkinFrame? {
    val frames = representation.screens.mapNotNull { it.outputFrame }
    if (frames.isEmpty()) return null
    val left = frames.minOf { it.x }
    val top = frames.minOf { it.y }
    val right = frames.maxOf { it.x + it.width }
    val bottom = frames.maxOf { it.y + it.height }
    return DeltaSkinFrame(left, top, right - left, bottom - top)
}
