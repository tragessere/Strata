package com.swordfish.lemuroid.app.shared.game.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.shared.game.ShaderChooser
import com.swordfish.lemuroid.app.shared.rumble.RumbleManager
import com.swordfish.lemuroid.app.shared.settings.HDModeQuality
import com.swordfish.lemuroid.common.coroutines.MutableStateProperty
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.view.disableTouchEvents
import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.game.GameLoader
import com.swordfish.lemuroid.lib.game.GameLoaderError
import com.swordfish.lemuroid.lib.game.GameLoaderException
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ImmersiveMode
import com.swordfish.libretrodroid.Variable
import com.swordfish.libretrodroid.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(FlowPreview::class)
class GameViewModelRetroGameView(
    private val appContext: Context,
    private val system: GameSystem,
    private val systemCoreConfig: SystemCoreConfig,
    private val settingsManager: SettingsManager,
    private val coreVariablesManager: CoreVariablesManager,
    private val sideEffects: GameViewModelSideEffects,
    private val rumbleManager: RumbleManager,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    sealed interface GameState {
        data object Uninitialized : GameState

        data class Loading(
            val message: String,
        ) : GameState

        data class Loaded(
            val gameData: GameLoader.GameData,
            val retroViewData: GLRetroViewData,
        ) : GameState

        data object Ready : GameState
    }

    private val gameState: MutableStateFlow<GameState> = MutableStateFlow(GameState.Uninitialized)

    private val retroGameViewFlow = MutableStateFlow<GLRetroView?>(null)
    var retroGameView: GLRetroView? by MutableStateProperty(retroGameViewFlow)

    /** Whether the player wants to hear the game, as opposed to whether it is audible right now. */
    private var isAudioRequested = true

    /** True while audio is being held back ahead of a pause. See [silenceAudioForPause]. */
    private var isAudioSilenced = false

    /** True once audio has been silenced for a pause the game will never come back from. */
    private var isAudioSilencedForShutdown = false

    /**
     * The audio setting as the player left it. Reading the emulator directly would instead report
     * whatever [silenceAudioForPause] has done to it, and the game menu is opened through exactly
     * that path, so it would show itself muted every time it is opened.
     */
    var audioEnabled: Boolean
        get() = isAudioRequested
        set(value) {
            isAudioRequested = value
            applyAudioEnabled()
        }

    fun getGameState(): Flow<GameState> = gameState.debounce(200)

    suspend fun initialize(
        applicationContext: Context,
        game: Game,
        systemCoreConfig: SystemCoreConfig,
        gameLoader: GameLoader,
        requestLoadSave: Boolean,
    ) {
        val currentState = gameState.value
        if (currentState != GameState.Uninitialized) return

        val autoSaveEnabled = settingsManager.autoSave()
        val filter = settingsManager.screenFilter()
        val hdMode = settingsManager.hdMode()
        val hdModeQuality = settingsManager.hdModeQuality()
        val lowLatencyAudio = settingsManager.lowLatencyAudio()
        val enableRumble = settingsManager.enableRumble()
        val directLoad = settingsManager.allowDirectGameLoad()
        val enableImmersiveMode = settingsManager.enableImmersiveMode()

        val hasMicrophonePermission =
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        val enableMicrophone = systemCoreConfig.supportsMicrophone && hasMicrophonePermission

        val loadingStatesFlow =
            gameLoader.load(
                applicationContext,
                game,
                requestLoadSave && autoSaveEnabled,
                systemCoreConfig,
                directLoad,
            )

        loadingStatesFlow
            .flowOn(Dispatchers.IO)
            .catch {
                val message =
                    if (it is GameLoaderException) {
                        getErrorMessage(it.error)
                    } else {
                        ""
                    }
                sideEffects.requestFailureFinish(message)
            }.debounce(200)
            .collect { loadingState ->
                gameState.value =
                    if (loadingState is GameLoader.LoadingState.Ready) {
                        Timber.i("Setting state to loaded")
                        val retroViewData =
                            buildRetroViewData(
                                applicationContext,
                                systemCoreConfig,
                                loadingState.gameData,
                                hdMode,
                                hdModeQuality,
                                filter,
                                lowLatencyAudio,
                                enableRumble,
                                enableMicrophone,
                                enableImmersiveMode,
                            )
                        GameState.Loaded(
                            gameData = loadingState.gameData,
                            retroViewData = retroViewData,
                        )
                    } else {
                        GameState.Loading(getLoadingMessage(loadingState))
                    }
            }
    }

    fun createRetroView(
        context: Context,
        lifecycle: LifecycleOwner,
    ): Pair<GameLoader.GameData, GLRetroView> {
        val currentState = gameState.value
        if (currentState !is GameState.Loaded) throw IllegalStateException("Game is not loaded.")

        val result =
            GLRetroView(context, currentState.retroViewData)
                .apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                }

        if (!system.hasTouchScreen) {
            result.disableTouchEvents()
        }

        lifecycle.lifecycle.addObserver(result)

        if (BuildConfig.DEBUG) {
            runCatching {
                printRetroVariables(result)
            }
        }

        retroGameViewFlow.value = result
        gameState.value = GameState.Ready

        applyAudioEnabled()

        return currentState.gameData to result
    }

    /**
     * Stops the emulator queueing audio and waits for what it has already queued to play out, so
     * that the pause which follows lands on a buffer that is already empty. The next resume gives
     * the game its audio back.
     *
     * LibretroDroid stops its audio stream when the game pauses and starts it again on resume
     * without ever flushing it, so the tail left in the buffer - up to a tenth of a second of it -
     * is replayed on the way back in, ahead of anything the emulator has produced since. That
     * replay is the stutter. The same sound is lost either way; draining it first simply leaves
     * nothing behind to repeat.
     */
    suspend fun silenceAudioForPause() = silenceAudio(forShutdown = false)

    /**
     * The same, for the way out of the game, where no resume may undo it.
     *
     * Quitting from the game menu closes that menu first, which resumes the game underneath it for
     * the moment it takes to write the save and finish, and a resume is otherwise exactly what
     * hands the emulator its audio back.
     */
    suspend fun silenceAudioForShutdown() = silenceAudio(forShutdown = true)

    private suspend fun silenceAudio(forShutdown: Boolean) {
        isAudioSilencedForShutdown = isAudioSilencedForShutdown || forShutdown

        // Silent already means drained already: whoever silenced it has waited that out.
        if (isAudioSilenced) return

        isAudioSilenced = true
        applyAudioEnabled()
        delay(AUDIO_DRAIN_DURATION)
    }

    private fun applyAudioEnabled() {
        retroGameView?.audioEnabled = isAudioRequested && !isAudioSilenced
    }

    suspend fun retroGameViewFlow() =
        retroGameViewFlow
            .filterNotNull()
            .first()

    suspend fun waitRetroGameViewInitialized() {
        retroGameViewFlow()
    }

    suspend inline fun <reified T> waitGLEvent() {
        val retroView = retroGameViewFlow()
        retroView
            .getGLRetroEvents()
            .filterIsInstance<T>()
            .first()
    }

    private fun buildRetroViewData(
        appContext: Context,
        systemCoreConfig: SystemCoreConfig,
        gameData: GameLoader.GameData,
        hdMode: Boolean,
        hdModeQuality: HDModeQuality,
        screenFilter: String,
        lowLatencyAudio: Boolean,
        requestRumble: Boolean,
        requestMicrophone: Boolean,
        enableImmersiveMode: Boolean,
    ): GLRetroViewData =
        GLRetroViewData(appContext).apply {
            coreFilePath = gameData.coreLibrary

            when (val gameFiles = gameData.gameFiles) {
                is RomFiles.Standard -> {
                    gameFilePath = gameFiles.files.first().absolutePath
                }

                is RomFiles.Virtual -> {
                    gameVirtualFiles = gameFiles.files.map { VirtualFile(it.filePath, it.fd) }
                }
            }

            systemDirectory = gameData.systemDirectory.absolutePath
            savesDirectory = gameData.savesDirectory.absolutePath
            variables = gameData.coreVariables.map { Variable(it.key, it.value) }.toTypedArray()
            saveRAMState = gameData.saveRAMData
            shader =
                ShaderChooser.getShaderForSystem(
                    appContext,
                    hdMode,
                    hdModeQuality,
                    screenFilter,
                    GameSystem.findById(gameData.game.systemId),
                )
            preferLowLatencyAudio = lowLatencyAudio
            rumbleEventsEnabled = requestRumble
            skipDuplicateFrames = systemCoreConfig.skipDuplicateFrames
            enableMicrophone = requestMicrophone
            immersiveMode = buildImmersiveModeConfiguration(enableImmersiveMode)
        }

    private fun buildImmersiveModeConfiguration(enableImmersiveMode: Boolean): ImmersiveMode? =
        if (enableImmersiveMode) {
            ImmersiveMode(blendFactor = 0.05f)
        } else {
            null
        }

    private fun getLoadingMessage(loadingState: GameLoader.LoadingState): String =
        when (loadingState) {
            is GameLoader.LoadingState.LoadingCore -> {
                appContext.getString(com.swordfish.lemuroid.ext.R.string.game_loading_download_core)
            }

            is GameLoader.LoadingState.LoadingGame -> {
                appContext.getString(R.string.game_loading_preparing_game)
            }

            else -> ""
        }

    private fun printRetroVariables(retroGameView: GLRetroView) {
        scope.launch {
            // Some cores do not immediately call SET_VARIABLES so we might need to wait a little bit
            delay(1.seconds)
            retroGameView.getVariables().forEach {
                Timber.i("Libretro variable: $it")
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        owner.launchOnState(Lifecycle.State.STARTED) {
            initializeRetroGameViewErrorsFlow()
        }

        owner.launchOnState(Lifecycle.State.RESUMED) {
            initializeCoreVariablesFlow()
        }

        owner.launchOnState(Lifecycle.State.RESUMED) {
            initializeRumbleFlow()
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)

        // Everything past the point of no return is teardown, and the resume that closing the game
        // menu performs on the way there must not hand the emulator its audio back for it.
        if (isAudioSilencedForShutdown) return

        // Whatever the pause was for is over, and the buffer it left behind has already played out.
        isAudioSilenced = false
        applyAudioEnabled()
    }

    private suspend fun initializeCoreVariablesFlow() {
        try {
            waitRetroGameViewInitialized()
            val options = coreVariablesManager.getOptionsForCore(system.id, systemCoreConfig)
            updateCoreVariables(options)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private suspend fun initializeRumbleFlow() {
        val retroGameView = retroGameViewFlow()
        val rumbleEvents = retroGameView.getRumbleEvents()
        rumbleManager.collectAndProcessRumbleEvents(systemCoreConfig, rumbleEvents)
    }

    private suspend fun initializeRetroGameViewErrorsFlow() {
        retroGameViewFlow()
            .getGLRetroErrors()
            .catch { Timber.e(it, "Exception in GLRetroErrors. Ironic.") }
            .collect { handleRetroViewError(it) }
    }

    private fun updateCoreVariables(options: List<CoreVariable>) {
        val updatedVariables =
            options
                .map { Variable(it.key, it.value) }
                .toTypedArray()

        updatedVariables.forEach {
            Timber.i("Updating core variable: ${it.key} ${it.value}")
        }

        retroGameView?.updateVariables(*updatedVariables)
    }

    private fun handleRetroViewError(errorCode: Int) {
        Timber.e("Error in GLRetroView $errorCode")
        val gameLoaderError =
            when (errorCode) {
                GLRetroView.ERROR_GL_NOT_COMPATIBLE -> GameLoaderError.GLIncompatible
                GLRetroView.ERROR_LOAD_GAME -> GameLoaderError.LoadGame
                GLRetroView.ERROR_LOAD_LIBRARY -> GameLoaderError.LoadCore
                GLRetroView.ERROR_SERIALIZATION -> GameLoaderError.Saves
                else -> GameLoaderError.Generic
            }

        sideEffects.requestFailureFinish(getErrorMessage(gameLoaderError))
    }

    private fun getErrorMessage(gameError: GameLoaderError): String {
        val message =
            when (gameError) {
                is GameLoaderError.GLIncompatible -> {
                    appContext.getString(R.string.game_loader_error_gl_incompatible)
                }
                is GameLoaderError.Generic -> {
                    appContext.getString(R.string.game_loader_error_generic)
                }
                is GameLoaderError.LoadCore -> {
                    appContext.getString(com.swordfish.lemuroid.ext.R.string.game_loader_error_load_core)
                }
                is GameLoaderError.LoadGame -> {
                    appContext.getString(R.string.game_loader_error_load_game)
                }
                is GameLoaderError.Saves -> {
                    appContext.getString(R.string.game_loader_error_save)
                }
                is GameLoaderError.UnsupportedArchitecture -> {
                    appContext.getString(R.string.game_loader_error_unsupported_architecture)
                }
                is GameLoaderError.MissingBiosFiles -> {
                    appContext.getString(R.string.game_loader_error_missing_bios, gameError.missingFiles)
                }
            }

        return message
    }

    companion object {
        /**
         * Long enough to cover the emulator's audio buffer, which holds around a tenth of a second
         * and is only ever drained by playing it.
         */
        private val AUDIO_DRAIN_DURATION = 250.milliseconds
    }
}
