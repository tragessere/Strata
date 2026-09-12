package com.swordfish.lemuroid.app.shared.game

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.mobile.feature.game.GameService
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelInput
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelRetroGameView
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSaves
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSideEffects
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelTilt
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelTouchControls
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.rumble.RumbleManager
import com.swordfish.lemuroid.app.shared.settings.ControllerConfigsManager
import com.swordfish.lemuroid.app.shared.settings.HapticFeedbackMode
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.longAnimationDuration
import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.game.GameLoader
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.skin.ControllerSkinPreferences
import com.swordfish.lemuroid.lib.library.skin.DeltaSkinManager
import com.swordfish.lemuroid.lib.saves.SavesCoherencyEngine
import com.swordfish.lemuroid.lib.saves.SavesManager
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import gg.padkit.inputevents.InputEvent
import gg.padkit.inputstate.InputState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

class BaseGameScreenViewModel(
    private val appContext: Context,
    private val game: Game,
    settingsManager: SettingsManager,
    inputDeviceManager: InputDeviceManager,
    controllerConfigsManager: ControllerConfigsManager,
    system: GameSystem,
    private val systemCoreConfig: SystemCoreConfig,
    sharedPreferences: SharedPreferences,
    savesManager: SavesManager,
    statesManager: StatesManager,
    statesPreviewManager: StatesPreviewManager,
    private val savesCoherencyEngine: SavesCoherencyEngine,
    coreVariablesManager: CoreVariablesManager,
    rumbleManager: RumbleManager,
) : ViewModel(),
    DefaultLifecycleObserver {
    class Factory(
        private val appContext: Context,
        private val game: Game,
        private val settingsManager: SettingsManager,
        private val inputDeviceManager: InputDeviceManager,
        private val controllerConfigsManager: ControllerConfigsManager,
        private val system: GameSystem,
        private val systemCoreConfig: SystemCoreConfig,
        private val sharedPreferences: SharedPreferences,
        private val savesManager: SavesManager,
        private val statesManager: StatesManager,
        private val statesPreviewManager: StatesPreviewManager,
        private val savesCoherencyEngine: SavesCoherencyEngine,
        private val coreVariablesManager: CoreVariablesManager,
        private val rumbleManager: RumbleManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BaseGameScreenViewModel(
                appContext,
                game,
                settingsManager,
                inputDeviceManager,
                controllerConfigsManager,
                system,
                systemCoreConfig,
                sharedPreferences,
                savesManager,
                statesManager,
                statesPreviewManager,
                savesCoherencyEngine,
                coreVariablesManager,
                rumbleManager,
            ) as T
    }

    private val sideEffects = GameViewModelSideEffects(viewModelScope)
    val retroGameView =
        GameViewModelRetroGameView(
            appContext,
            system,
            systemCoreConfig,
            settingsManager,
            coreVariablesManager,
            sideEffects,
            rumbleManager,
            viewModelScope,
        )
    private val tilt = GameViewModelTilt(appContext, settingsManager)
    private val inputs =
        GameViewModelInput(
            appContext,
            system,
            systemCoreConfig,
            inputDeviceManager,
            controllerConfigsManager,
            retroGameView,
            tilt,
            sideEffects,
            viewModelScope,
        )
    private val touchControls =
        GameViewModelTouchControls(
            settingsManager,
            TouchControllerSettingsManager(sharedPreferences),
            retroGameView,
            inputs,
            tilt,
            sideEffects,
            viewModelScope,
            system.id,
            DeltaSkinManager(appContext, DirectoriesManager(appContext)),
            ControllerSkinPreferences(sharedPreferences),
            appContext.resources.configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP,
        )
    private val saves =
        GameViewModelSaves(
            appContext,
            system,
            game,
            systemCoreConfig,
            retroGameView,
            settingsManager,
            savesManager,
            statesManager,
            statesPreviewManager,
            sideEffects,
        )

    val loadingState = MutableStateFlow(false)

    /** Whether the next start is the launch's own, rather than a return from the background. */
    private var isFirstStart = true

    private inline fun withLoading(block: () -> Unit) {
        loadingState.value = true
        block()
        loadingState.value = false
    }

    fun getGameState(): Flow<GameViewModelRetroGameView.GameState> = retroGameView.getGameState()

    fun getSideEffects(): Flow<GameViewModelSideEffects.UiEffect> = sideEffects.getUiEffects()

    fun getTiltConfiguration(): Flow<TiltConfiguration> = tilt.getTiltConfiguration()

    fun getSimulatedTiltEvents(): Flow<InputState> = tilt.getSimulatedTiltEvents()

    fun getTouchControlsSettings(
        density: Density,
        insets: WindowInsets,
    ): Flow<TouchControllerSettingsManager.Settings?> = touchControls.getTouchControlsSettings(density, insets)

    fun getTouchHapticFeedbackMode(): Flow<HapticFeedbackMode> = touchControls.getTouchHapticFeedbackMode()

    fun createRetroView(
        context: Context,
        lifecycle: LifecycleOwner,
    ): GLRetroView {
        val (gameData, result) = retroGameView.createRetroView(context, lifecycle)
        saves.onSaveRAMLoaded(gameData.saveRAMData)
        viewModelScope.launch {
            gameData.quickSaveData?.let {
                saves.restoreAutoSaveAsync(it)
            }
        }
        return result
    }

    suspend fun loadGame(
        applicationContext: Context,
        game: Game,
        systemCoreConfig: SystemCoreConfig,
        gameLoader: GameLoader,
        requestLoadSave: Boolean,
    ) {
        Timber.i("Calling load game: $game")
        retroGameView.initialize(applicationContext, game, systemCoreConfig, gameLoader, requestLoadSave)
    }

    fun showEditControls(show: Boolean) {
        touchControls.showEditControls(show)
    }

    fun isEditControlShown(): Flow<Boolean> = touchControls.isEditControlsShown()

    fun updateTouchControllerSettings(touchControllerSettings: TouchControllerSettingsManager.Settings) {
        touchControls.updateTouchControllerSettings(touchControllerSettings)
    }

    fun resetTouchControls() {
        touchControls.resetTouchControls()
    }

    fun onScreenOrientationChanged(orientation: TouchControllerSettingsManager.Orientation) {
        touchControls.updateScreenOrientation(orientation)
    }

    fun isTouchControllerVisible(): Flow<Boolean> = touchControls.isTouchControllerVisible()

    fun getTouchControllerConfig(): Flow<ControllerConfig> = touchControls.getTouchControllerConfig()

    fun getSkinState(): Flow<GameViewModelTouchControls.SkinUiState> = touchControls.getSkinState()

    fun getSkinOpacity(): Flow<Float> = touchControls.getSkinOpacity()

    fun isControllerSkinActive(): Boolean = touchControls.isSkinActive()

    fun sendSkinButton(
        keyCodes: List<Int>,
        pressed: Boolean,
    ) {
        touchControls.sendSkinButton(keyCodes, pressed)
    }

    fun sendSkinMenu(pressed: Boolean) {
        touchControls.sendSkinMenu(pressed)
    }

    fun sendSkinMotion(
        source: Int,
        xAxis: Float,
        yAxis: Float,
    ) {
        touchControls.sendSkinMotion(source, xAxis, yAxis)
    }

    fun changeTiltConfiguration(tiltConfig: TiltConfiguration) {
        tilt.changeTiltConfiguration(tiltConfig)
    }

    fun isMenuPressed(): Flow<Boolean> = touchControls.isMenuPressed()

    suspend fun saveSlot(index: Int) {
        if (loadingState.value) return
        withLoading {
            saves.saveSlot(index)
        }
    }

    suspend fun loadSlot(index: Int) {
        if (loadingState.value) return
        withLoading {
            saves.loadSlot(index)
        }
    }

    fun saveQuickSave() {
        Timber.d("Saving quick save")
        if (loadingState.value) return
        withLoading {
            saves.saveQuickSave()
        }
    }

    fun loadQuickSave() {
        Timber.d("Loading quick save")
        if (loadingState.value) return
        withLoading {
            saves.loadQuickSave()
        }
    }

    fun toggleFastForward() {
        Timber.d("Loading quick save")
        retroGameView.retroGameView?.apply {
            frameSpeed = if (frameSpeed == 1) 2 else 1
        }
    }

    suspend fun reset() =
        withLoading {
            try {
                delay(appContext.longAnimationDuration().toLong().milliseconds)
                retroGameView.retroGameViewFlow().reset()
            } catch (e: Throwable) {
                Timber.e(e, "Error in reset")
            }
        }

    fun requestFinish() {
        if (loadingState.value) return
        viewModelScope.launch {
            withLoading {
                // Quitting pauses the emulator on the way out, so the buffer has to be emptied
                // first or its tail is what the library hears over the closing animation. When the
                // quit comes from the game menu the buffer is already empty and this only has to
                // hold the mute through the resume that closing the menu performs.
                retroGameView.silenceAudioForShutdown()
                val snapshot = saves.captureSaveSnapshot(true) ?: return@launch
                saves.writeSaveSnapshot(snapshot)
                sideEffects.requestSuccessfulFinish()
            }
        }
    }

    /**
     * Persists the session as the game is put into the background.
     *
     * The emulator is read here and now, on the caller's thread, because it is being torn down
     * alongside the activity and there is no getting it back once it has gone. Only the write is
     * handed off, which no longer depends on anything but the bytes already in hand.
     */
    fun requestBackgroundSave() {
        if (loadingState.value) {
            Timber.w("Skipping the background save: a state operation is still in flight")
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        val snapshot = saves.captureBackgroundSaveSnapshot() ?: return
        Timber.i("Captured the background save in %dms", SystemClock.elapsedRealtime() - startedAt)

        GameService.schedule {
            saves.writeSaveSnapshot(snapshot)
        }
    }

    suspend fun loadAutoSave() {
        if (loadingState.value) return
        withLoading {
            saves.loadAutoSave()
        }
    }

    fun handleVirtualInputEvent(events: List<InputEvent>) {
        touchControls.handleVirtualInputEvent(events)
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        owner.lifecycle.addObserver(tilt)
        owner.lifecycle.addObserver(inputs)
        owner.lifecycle.addObserver(retroGameView)
        owner.lifecycle.addObserver(touchControls)

        viewModelScope.launch { saves.primeSettings() }

        // Only while the game is actually running: the poll reaches the emulator through its own
        // thread, which is only there to answer between frames.
        owner.launchOnState(Lifecycle.State.RESUMED) {
            saves.persistSaveRAMWhileRunning()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)

        // The launch's own start is recorded by the loader, once it has read the previous session
        // and decided whether to resume into its auto-save. This one arrives first, so recording it
        // here would be read back as this session's own start and leave every launch discarding the
        // auto-save it was about to resume.
        if (isFirstStart) {
            isFirstStart = false
            return
        }

        // Every later start resumes play from a state written on the way out, so from this moment
        // the auto-save is behind again and a session which fails to replace it must not be resumed
        // into. Recording the new session is what lets the next launch tell.
        viewModelScope.launch {
            savesCoherencyEngine.onSessionStarted(game, systemCoreConfig.coreID)
        }
    }

    fun sendKeyEvent(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = inputs.sendKeyEvent(keyCode, event)

    fun sendMotionEvent(event: MotionEvent): Boolean = inputs.sendMotionEvent(event)

    companion object {
        private const val TABLET_SMALLEST_WIDTH_DP = 600
    }
}
