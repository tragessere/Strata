package com.swordfish.lemuroid.app.shared.game.viewmodel

import android.view.KeyEvent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.shared.settings.HapticFeedbackMode
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.coroutines.safeCollect
import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.skin.ControllerSkinPreferences
import com.swordfish.lemuroid.lib.library.skin.DeltaSkinManager
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_ANALOG_LEFT
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_ANALOG_RIGHT
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_DPAD
import com.swordfish.touchinput.deltaskin.DeltaSkinInputMapping
import com.swordfish.touchinput.deltaskin.DeltaSkinRepresentation
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.settings.TouchControllerID
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import gg.padkit.inputevents.InputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTouchControls(
    private val settingsManager: SettingsManager,
    private val touchControllerSettingsManager: TouchControllerSettingsManager,
    private val retroGameView: GameViewModelRetroGameView,
    private val inputs: GameViewModelInput,
    private val tilt: GameViewModelTilt,
    private val sideEffects: GameViewModelSideEffects,
    private val scope: CoroutineScope,
    private val systemID: SystemID,
    private val deltaSkinManager: DeltaSkinManager,
    private val controllerSkinPreferences: ControllerSkinPreferences,
    private val isTablet: Boolean,
) : DefaultLifecycleObserver {
    /** A resolved skin ready to render for the current orientation. */
    data class ActiveSkin(
        val directory: File,
        val representation: DeltaSkinRepresentation,
        val isLandscape: Boolean,
    )

    private val touchControlId = MutableStateFlow(TouchControllerID.GB)
    private val screenOrientation = MutableStateFlow(TouchControllerSettingsManager.Orientation.PORTRAIT)
    private val menuPressed = MutableStateFlow(false)
    private val showEditControls = MutableStateFlow(false)
    private val hapticFeedbackMode = MutableStateFlow(HapticFeedbackMode.NONE)

    private var loadingMenuJob: Job? = null

    override fun onCreate(owner: LifecycleOwner) {
        owner.launchOnState(Lifecycle.State.CREATED) {
            getTouchControllerConfig().safeCollect {
                touchControlId.value = it.touchControllerID
            }
        }
        owner.launchOnState(Lifecycle.State.CREATED) {
            withContext(Dispatchers.IO) {
                hapticFeedbackMode.value = HapticFeedbackMode.parse(settingsManager.hapticFeedbackMode())
            }
        }
    }

    fun getTouchControlsSettings(
        density: Density,
        insets: WindowInsets,
    ): Flow<TouchControllerSettingsManager.Settings?> =
        combine(
            touchControlId,
            screenOrientation,
        ) { touchControlId, orientation -> touchControlId to orientation }
            .flatMapLatest { (touchControlId, orientation) ->
                touchControllerSettingsManager.observeSettings(touchControlId, orientation, density, insets)
            }

    fun getTouchHapticFeedbackMode(): Flow<HapticFeedbackMode> = hapticFeedbackMode

    fun updateTouchControllerSettings(touchControllerSettings: TouchControllerSettingsManager.Settings) {
        scope.launch {
            touchControllerSettingsManager.storeSettings(
                touchControlId.value,
                screenOrientation.value,
                touchControllerSettings,
            )
        }
    }

    fun resetTouchControls() {
        scope.launch {
            touchControllerSettingsManager.resetSettings(
                touchControlId.value,
                screenOrientation.value,
            )
        }
    }

    fun updateScreenOrientation(orientation: TouchControllerSettingsManager.Orientation) {
        screenOrientation.value = orientation
    }

    fun isTouchControllerVisible(): Flow<Boolean> =
        inputs
            .getEnabledInputDevices()
            .map { it.isEmpty() }

    fun getTouchControllerConfig(): Flow<ControllerConfig> =
        inputs
            .getControllerConfigState()
            .map { it[0] }
            .filterNotNull()
            .distinctUntilChanged()

    /**
     * Rendering state for the current (system, orientation) slot. [Loading] is emitted as soon as we
     * know a skin is selected but before it has been parsed, so the default (PadKit) controls can be
     * hidden immediately instead of flashing while the skin loads.
     */
    sealed interface SkinUiState {
        data object None : SkinUiState

        data object Loading : SkinUiState

        data class Active(
            val skin: ActiveSkin,
        ) : SkinUiState
    }

    private val skinState: StateFlow<SkinUiState> =
        screenOrientation
            .flatMapLatest { orientation ->
                controllerSkinPreferences
                    .observeSelectedSkinId(systemID, orientation)
                    .map { skinId -> skinId to orientation }
            }.distinctUntilChanged()
            .flatMapLatest { (skinId, orientation) ->
                flow {
                    if (skinId == null) {
                        emit(SkinUiState.None)
                        return@flow
                    }
                    emit(SkinUiState.Loading)
                    emit(resolveSkinState(skinId, orientation))
                }
            }.stateIn(scope, SharingStarted.Eagerly, computeInitialSkinState())

    fun getSkinState(): StateFlow<SkinUiState> = skinState

    /**
     * Opacity of the skin artwork in 0..1, shared by every system. Kept as its own flow so dragging the
     * slider in settings is reflected without reloading the skin.
     */
    private val skinOpacity: StateFlow<Float> =
        controllerSkinPreferences
            .observeOpacity()
            .map { it / 100f }
            .stateIn(scope, SharingStarted.Eagerly, controllerSkinPreferences.getOpacity() / 100f)

    fun getSkinOpacity(): StateFlow<Float> = skinOpacity

    /** True when a controller skin is selected (loading or active), replacing the default touch controls. */
    fun isSkinActive(): Boolean = skinState.value !is SkinUiState.None

    private suspend fun resolveSkinState(
        skinId: String,
        orientation: TouchControllerSettingsManager.Orientation,
    ): SkinUiState {
        val handle = deltaSkinManager.loadHandle(skinId) ?: return SkinUiState.None
        val orientationName =
            when (orientation) {
                TouchControllerSettingsManager.Orientation.LANDSCAPE -> "landscape"
                TouchControllerSettingsManager.Orientation.PORTRAIT -> "portrait"
            }
        val representation =
            deltaSkinManager.resolveRepresentation(handle.info, isTablet, orientationName)
                ?: return SkinUiState.None
        return SkinUiState.Active(
            ActiveSkin(
                directory = handle.directory,
                representation = representation,
                isLandscape = orientation == TouchControllerSettingsManager.Orientation.LANDSCAPE,
            ),
        )
    }

    private fun computeInitialSkinState(): SkinUiState {
        val skinId = controllerSkinPreferences.getSelectedSkinId(systemID, screenOrientation.value)
        return if (skinId != null) SkinUiState.Loading else SkinUiState.None
    }

    /** Presses/releases regular skin buttons (menu keycode is routed to the in-game menu). */
    fun sendSkinButton(
        keyCodes: List<Int>,
        pressed: Boolean,
    ) {
        keyCodes.forEach { keyCode ->
            if (keyCode == DeltaSkinInputMapping.MENU_KEYCODE) {
                sendSkinMenu(pressed)
            } else {
                handleButton(keyCode, pressed)
            }
        }
    }

    /** Opens the in-game menu immediately on press (skins don't require the press-and-hold delay). */
    fun sendSkinMenu(pressed: Boolean) {
        menuPressed.value = pressed
        if (pressed) {
            sideEffects.showMenu(tilt, inputs)
        }
    }

    fun sendSkinMotion(
        source: Int,
        xAxis: Float,
        yAxis: Float,
    ) {
        handleVirtualInputDirection(source, xAxis, yAxis)
    }

    fun handleVirtualInputEvent(events: List<InputEvent>) {
        val menuEvent = events.firstOrNull { it is InputEvent.Button && it.id == KeyEvent.KEYCODE_BUTTON_MODE }
        if (menuEvent != null) {
            onMenuPressed((menuEvent as InputEvent.Button).pressed)
        }

        events.forEach { event ->
            when (event) {
                is InputEvent.Button -> {
                    handleVirtualInputButton(event)
                }

                is InputEvent.DiscreteDirection -> {
                    handleVirtualInputDirection(event.id, event.direction.x, -event.direction.y)
                }

                is InputEvent.ContinuousDirection -> {
                    handleVirtualInputDirection(event.id, event.direction.x, -event.direction.y)
                }
            }
        }
    }

    private fun onMenuPressed(pressed: Boolean) {
        menuPressed.value = pressed

        if (pressed) {
            loadingMenuJob?.cancel()
            loadingMenuJob =
                scope.launch {
                    delay(MENU_LOADING_ANIMATION_MILLIS.toLong())
                    sideEffects.showMenu(tilt, inputs)
                }
        } else {
            loadingMenuJob?.cancel()
            loadingMenuJob = null
        }
    }

    fun isMenuPressed(): Flow<Boolean> = menuPressed

    fun isEditControlsShown(): Flow<Boolean> = showEditControls

    fun showEditControls(show: Boolean) {
        showEditControls.value = show
    }

    private fun handleVirtualInputButton(event: InputEvent.Button) {
        handleButton(event.id, event.pressed)
    }

    private fun handleButton(
        id: Int,
        pressed: Boolean,
    ) {
        val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        retroGameView.retroGameView?.sendKeyEvent(action, id)
    }

    private fun handleVirtualInputDirection(
        id: Int,
        xAxis: Float,
        yAxis: Float,
    ) {
        when (id) {
            ComposeTouchLayouts.MOTION_SOURCE_DPAD -> {
                retroGameView.retroGameView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, xAxis, yAxis)
            }

            ComposeTouchLayouts.MOTION_SOURCE_LEFT_STICK -> {
                retroGameView.retroGameView?.sendMotionEvent(
                    MOTION_SOURCE_ANALOG_LEFT,
                    xAxis,
                    yAxis,
                )
            }

            ComposeTouchLayouts.MOTION_SOURCE_RIGHT_STICK -> {
                retroGameView.retroGameView?.sendMotionEvent(
                    MOTION_SOURCE_ANALOG_RIGHT,
                    xAxis,
                    yAxis,
                )
            }

            ComposeTouchLayouts.MOTION_SOURCE_DPAD_AND_LEFT_STICK -> {
                retroGameView.retroGameView?.sendMotionEvent(
                    MOTION_SOURCE_ANALOG_LEFT,
                    xAxis,
                    yAxis,
                )
                retroGameView.retroGameView?.sendMotionEvent(MOTION_SOURCE_DPAD, xAxis, yAxis)
            }

            ComposeTouchLayouts.MOTION_SOURCE_RIGHT_DPAD -> {
                retroGameView.retroGameView?.sendMotionEvent(
                    MOTION_SOURCE_ANALOG_RIGHT,
                    xAxis,
                    yAxis,
                )
            }
        }
    }

    companion object {
        const val MENU_LOADING_ANIMATION_MILLIS = 500
    }
}
