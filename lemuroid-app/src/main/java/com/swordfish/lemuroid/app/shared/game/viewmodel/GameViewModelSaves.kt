package com.swordfish.lemuroid.app.shared.game.viewmodel

import android.content.Context
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.common.graphics.GraphicsUtils
import com.swordfish.lemuroid.common.graphics.takeScreenshot
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.IncompatibleStateException
import com.swordfish.lemuroid.lib.saves.SaveState
import com.swordfish.lemuroid.lib.saves.SavesManager
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.libretrodroid.GLRetroView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.roundToInt

class GameViewModelSaves(
    private val appContext: Context,
    private val system: GameSystem,
    private val game: Game,
    private val systemCoreConfig: SystemCoreConfig,
    private val retroGameView: GameViewModelRetroGameView,
    private val settingsManager: SettingsManager,
    private val savesManager: SavesManager,
    private val statesManager: StatesManager,
    private val statesPreviewManager: StatesPreviewManager,
    private val sideEffects: GameViewModelSideEffects,
) {
    private var currentQuickSave: SaveState? = null

    /**
     * Whether states are being auto-saved, as last read from the settings.
     *
     * The setting itself is only readable from a coroutine, and the snapshot taken as the game is
     * backgrounded cannot afford to wait for one, so it is kept here from [primeSettings] onwards.
     * Until then it stands at what the setting defaults to, which errs towards writing a state that
     * will not be resumed rather than skipping one that would have been.
     */
    @Volatile
    private var autoSaveEnabled: Boolean = systemCoreConfig.statesSupported

    /**
     * The save file as it was last known to be on disk, so that an unchanged one is not rewritten.
     *
     * Comparing against it is what turns the poll below into a way of noticing that the game wrote
     * its save, which libretro gives a frontend no other way of hearing about.
     */
    private var lastPersistedSaveRAM: ByteArray? = null

    data class SaveSnapshot(
        val sram: ByteArray,
        val autoSave: SaveState?,
    )

    suspend fun saveSlot(index: Int) {
        getCurrentSaveState()?.let {
            statesManager.setSlotSave(game, it, systemCoreConfig.coreID, index)
            runCatching {
                takeScreenshotPreview(index)
            }
        }
    }

    suspend fun loadSlot(index: Int) {
        try {
            statesManager.getSlotSave(game, systemCoreConfig.coreID, index)?.let {
                val loaded =
                    withContext(Dispatchers.IO) {
                        loadSaveState(it)
                    }

                if (!loaded) {
                    sideEffects.showToast(appContext.getString(R.string.game_toast_load_state_failed))
                }
            }
        } catch (e: Throwable) {
            val errorMessageId =
                when (e) {
                    is IncompatibleStateException -> R.string.error_message_incompatible_state
                    else -> R.string.game_toast_load_state_failed
                }
            sideEffects.showToast(appContext.getString(errorMessageId))
        }
    }

    /** Seeds [lastPersistedSaveRAM] with the save the core was handed, which is what is on disk. */
    fun onSaveRAMLoaded(saveRAM: ByteArray?) {
        lastPersistedSaveRAM = saveRAM
    }

    /**
     * Persists the game's save file for as long as the session is running, whenever the game writes
     * to it.
     *
     * Until this existed, a save the player made in game lived only in the core's memory until the
     * session ended, and the write at the end of a session is the least dependable moment there is.
     * A process killed while the game sat in the background took the in game save with it, which is
     * the loss the player actually notices, states being a convenience on top of it.
     *
     * libretro has no way of telling a frontend that a game has written its save, so the only way
     * to know is to look. The save is small, kilobytes rather than the megabytes a state runs to,
     * and reading it costs the emulator a memcpy between frames, so looking often is cheap. Writing
     * is not, and only happens when the bytes have actually changed, which is also what keeps the
     * modification time meaning "when the game last saved" rather than "when we last looked".
     *
     * Only the save is written here, never a state. Serializing one means stopping the emulator
     * mid frame for as long as the core takes, which on the larger systems is several frames, and
     * a stutter at the moment the player saves in game is a poor trade for something the coherency
     * engine already covers: a save newer than the state is exactly what makes the next launch cold
     * boot into it rather than resume somewhere behind it.
     */
    suspend fun persistSaveRAMWhileRunning() {
        while (true) {
            delay(SAVE_RAM_POLL_INTERVAL_MS)
            runCatching { persistSaveRAMIfChanged() }
                .onFailure { Timber.e(it, "Unable to persist the save file mid session") }
        }
    }

    private suspend fun persistSaveRAMIfChanged() {
        val retroGameView = retroGameView.retroGameView ?: return

        val saveRAM = withContext(Dispatchers.IO) { retroGameView.serializeSRAM(true) }
        if (saveRAM.isEmpty() || saveRAM.contentEquals(lastPersistedSaveRAM)) return

        Timber.i("The game wrote its save file. Persisting it.")
        if (savesManager.setSaveRAM(game, saveRAM)) {
            lastPersistedSaveRAM = saveRAM
        }
    }

    /** Reads the auto-save setting once, so that [captureBackgroundSaveSnapshot] can act on it. */
    suspend fun primeSettings() {
        autoSaveEnabled = isAutoSaveEnabled()
    }

    suspend fun captureSaveSnapshot(useEmulationThread: Boolean): SaveSnapshot? {
        autoSaveEnabled = isAutoSaveEnabled()
        return buildSaveSnapshot(useEmulationThread)
    }

    /**
     * The snapshot to persist as the game is put into the background, read on the caller's thread.
     *
     * This has to happen while the activity is still being stopped. The emulator is torn down with
     * it, and a capture handed to another thread first loses that race often enough to matter: the
     * view is gone by the time it runs, nothing is captured, and the session's progress is dropped
     * with no sign that it happened beyond an auto-save which is quietly one session behind. Only
     * the writing is deferred, which is plain file IO and safe to finish afterwards.
     *
     * The emulation thread is already paused by this point, so the emulator is read directly rather
     * than through it, which would wait for a thread that is never going to run the work.
     */
    fun captureBackgroundSaveSnapshot(): SaveSnapshot? = buildSaveSnapshot(useEmulationThread = false)

    private fun buildSaveSnapshot(useEmulationThread: Boolean): SaveSnapshot? {
        val retroGameView =
            retroGameView.retroGameView ?: run {
                Timber.e("Unable to capture a save snapshot: the emulator is already gone")
                return null
            }
        val sramState = retroGameView.serializeSRAM(useEmulationThread)
        val autoSaveState = if (autoSaveEnabled) getCurrentSaveState(useEmulationThread) else null
        return SaveSnapshot(sramState, autoSaveState)
    }

    suspend fun writeSaveSnapshot(snapshot: SaveSnapshot?) {
        if (snapshot == null) return
        Timber.i(
            "GameViewModelSaves.write game=%s core=%s writingSram=%s writingAutoSave=%s",
            game.id,
            systemCoreConfig.coreID,
            true,
            snapshot.autoSave != null,
        )
        if (savesManager.setSaveRAM(game, snapshot.sram)) {
            lastPersistedSaveRAM = snapshot.sram
        }

        val autoSave = snapshot.autoSave ?: return
        if (!statesManager.setAutoSave(game, systemCoreConfig.coreID, autoSave)) {
            // The state on disk is now older than the session which should have replaced it, which
            // is what makes the next launch cold boot instead of resuming somewhere behind here.
            Timber.e("The auto-save state was not written. The next launch will not resume into it.")
        }
    }

    // On some cores unserialize fails with no reason. So we need to try multiple times.
    suspend fun restoreAutoSaveAsync(saveState: SaveState) {
        // PPSSPP and Mupen64 initialize some state while rendering the first frame, so we have to wait before restoring
        // the autosave. Do not change thread here. Stick to the GL one to avoid issues with PPSSPP.
        if (!isAutoSaveEnabled()) return

        try {
            retroGameView.waitGLEvent<GLRetroView.GLRetroEvents.FrameRendered>()
            restoreQuickSave(saveState)
        } catch (e: Throwable) {
            Timber.e(e, "Error while loading auto-save")
        }
    }

    private fun getCurrentSaveState(useEmulationThread: Boolean = true): SaveState? {
        val retroGameView = retroGameView.retroGameView ?: return null
        val currentDisk =
            if (system.hasMultiDiskSupport) {
                retroGameView.getCurrentDisk(useEmulationThread)
            } else {
                0
            }

        // A core which cannot serialize hands back an empty array rather than reporting it, and an
        // empty state written out is a file which looks valid and restores into nothing.
        val state = retroGameView.serializeState(useEmulationThread)
        if (state.isEmpty()) {
            Timber.e("The core serialized an empty state for ${game.fileName}. Discarding it.")
            return null
        }

        return SaveState(
            state,
            SaveState.Metadata(currentDisk, systemCoreConfig.statesVersion),
        )
    }

    private suspend fun isAutoSaveEnabled(): Boolean = systemCoreConfig.statesSupported && settingsManager.autoSave()

    /**
     * Save a screenshot of the game's viewport for use in a save-state preview
     */
    private suspend fun takeScreenshotPreview(index: Int) {
        val gameView = retroGameView.retroGameView ?: return
        val sizeInDp = StatesPreviewManager.PREVIEW_SIZE_DP
        val previewSize = GraphicsUtils.convertDpToPixel(sizeInDp, appContext).roundToInt()
        val preview = gameView.takeScreenshot(previewSize, gameView.viewport, 3)
        if (preview != null) {
            statesPreviewManager.setPreviewForSlot(game, preview, systemCoreConfig.coreID, index)
        }
    }

    // Now that we wait for the first rendered frame this is probably no longer needed, but we'll keep it just to be sure
    private suspend fun restoreQuickSave(saveState: SaveState) {
        var times = 10

        while (!loadSaveState(saveState) && times > 0) {
            delay(200)
            times--
        }
    }

    private fun loadSaveState(saveState: SaveState): Boolean {
        val retroGameView = retroGameView.retroGameView ?: return false

        if (systemCoreConfig.statesVersion != saveState.metadata.version) {
            throw IncompatibleStateException()
        }

        if (system.hasMultiDiskSupport &&
            retroGameView.getAvailableDisks() > 1 &&
            retroGameView.getCurrentDisk() != saveState.metadata.diskIndex
        ) {
            retroGameView.changeDisk(saveState.metadata.diskIndex)
        }

        return retroGameView.unserializeState(saveState.state)
    }

    /**
     * Restores the auto-save, the state the last session left behind, on request from the menu.
     *
     * A launch will refuse to resume into one it cannot vouch for, so this is how a player gets back
     * to it when it was the one they wanted after all.
     */
    suspend fun loadAutoSave() {
        try {
            val autoSave = statesManager.getAutoSave(game, systemCoreConfig.coreID)
            if (autoSave == null) {
                sideEffects.showToast(appContext.getString(R.string.game_toast_load_state_failed))
                return
            }

            val loaded =
                withContext(Dispatchers.IO) {
                    loadSaveState(autoSave)
                }

            if (!loaded) {
                sideEffects.showToast(appContext.getString(R.string.game_toast_load_state_failed))
            }
        } catch (e: Throwable) {
            val errorMessageId =
                when (e) {
                    is IncompatibleStateException -> R.string.error_message_incompatible_state
                    else -> R.string.game_toast_load_state_failed
                }
            sideEffects.showToast(appContext.getString(errorMessageId))
        }
    }

    fun saveQuickSave() {
        currentQuickSave = getCurrentSaveState()
        sideEffects.showToast(appContext.getString(R.string.game_toast_quick_save_saved))
    }

    fun loadQuickSave() {
        loadSaveState(currentQuickSave ?: return)
        sideEffects.showToast(appContext.getString(R.string.game_toast_quick_save_loaded))
    }

    companion object {
        /**
         * How often the game's save file is checked for changes.
         *
         * This bounds how much of an in game save can be lost to the process dying without the
         * session ending first, which is a crash or a kill while the game is in the foreground:
         * leaving the app writes the save outright and does not wait for the poll. Cheap enough at
         * this rate to not be worth tuning per system.
         */
        private const val SAVE_RAM_POLL_INTERVAL_MS = 30_000L
    }
}
