package com.swordfish.lemuroid.lib.saves

import com.swordfish.lemuroid.common.kotlin.readBytesUncompressedAtomic
import com.swordfish.lemuroid.common.kotlin.readTextAtomic
import com.swordfish.lemuroid.common.kotlin.runCatchingWithRetry
import com.swordfish.lemuroid.common.kotlin.writeBytesCompressedAtomic
import com.swordfish.lemuroid.common.kotlin.writeTextAtomic
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

class StatesManager(
    private val directoriesManager: DirectoriesManager,
) {
    suspend fun getSlotSave(
        game: Game,
        coreID: CoreID,
        index: Int,
    ): SaveState? =
        withContext(Dispatchers.IO) {
            assert(index in 0 until MAX_STATES)
            getSaveState(getSlotSaveFileName(game, index), coreID.coreName)
        }

    suspend fun setSlotSave(
        game: Game,
        saveState: SaveState,
        coreID: CoreID,
        index: Int,
    ) = withContext(Dispatchers.IO) {
        assert(index in 0 until MAX_STATES)
        setSaveState(getSlotSaveFileName(game, index), coreID.coreName, saveState)
    }

    suspend fun getAutoSaveInfo(
        game: Game,
        coreID: CoreID,
    ): SaveInfo =
        withContext(Dispatchers.IO) {
            val autoSaveFile = getStateFile(getAutoSaveFileName(game), coreID.coreName)
            val autoSaveHasData = autoSaveFile.length() > 0
            SaveInfo(
                autoSaveFile.exists() && autoSaveHasData,
                autoSaveFile.lastModified(),
                autoSaveFile.length(),
            )
        }

    suspend fun getAutoSave(
        game: Game,
        coreID: CoreID,
    ) = withContext(Dispatchers.IO) {
        getSaveState(getAutoSaveFileName(game), coreID.coreName)
    }

    /**
     * Writes [saveState] as the auto-save for [game], reporting whether it landed.
     *
     * An empty state is not a state and is never written. A core which fails to serialize hands one
     * back rather than reporting the failure, and writing it would replace a working auto-save with
     * a file which looks perfectly valid from the outside: recent, non empty once compressed, and
     * impossible to restore. Refusing it leaves the previous auto-save in place, older than the
     * session which was meant to replace it, which is the state the next launch can recognise.
     */
    suspend fun setAutoSave(
        game: Game,
        coreID: CoreID,
        saveState: SaveState,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (saveState.state.isEmpty()) {
                Timber.e("Refusing to write an empty auto-save state for ${game.fileName}")
                return@withContext false
            }

            setSaveState(getAutoSaveFileName(game), coreID.coreName, saveState)
                .onFailure { Timber.e(it, "Unable to write the auto-save state for ${game.fileName}") }
                .isSuccess
        }

    suspend fun getSavedSlotsInfo(
        game: Game,
        coreID: CoreID,
    ): List<SaveInfo> =
        withContext(Dispatchers.IO) {
            (0 until MAX_STATES)
                .map { getStateFile(getSlotSaveFileName(game, it), coreID.coreName) }
                .map { SaveInfo(it.exists(), it.lastModified(), it.length()) }
                .toList()
        }

    private suspend fun getSaveState(
        fileName: String,
        coreName: String,
    ): SaveState? =
        runCatchingWithRetry(FILE_ACCESS_RETRIES) {
            val saveFile = getStateFile(fileName, coreName)
            val metadataFile = getMetadataStateFile(fileName, coreName)
            if (saveFile.exists()) {
                val byteArray = saveFile.readBytesUncompressedAtomic()
                val stateMetadata =
                    runCatching {
                        Json.Default.decodeFromString(
                            SaveState.Metadata.serializer(),
                            metadataFile.readTextAtomic(),
                        )
                    }
                SaveState(byteArray, stateMetadata.getOrNull() ?: SaveState.Metadata())
            } else {
                null
            }
        }.getOrNull()

    private suspend fun setSaveState(
        fileName: String,
        coreName: String,
        saveState: SaveState,
    ): Result<Unit> =
        runCatchingWithRetry(FILE_ACCESS_RETRIES) {
            writeStateToDisk(fileName, coreName, saveState.state)
            writeMetadataToDisk(fileName, coreName, saveState.metadata)
        }

    private fun writeMetadataToDisk(
        fileName: String,
        coreName: String,
        metadata: SaveState.Metadata,
    ) {
        val metadataFile = getMetadataStateFile(fileName, coreName)
        metadataFile.writeTextAtomic(Json.encodeToString(SaveState.Metadata.serializer(), metadata))
    }

    private fun writeStateToDisk(
        fileName: String,
        coreName: String,
        stateArray: ByteArray,
    ) {
        val saveFile = getStateFile(fileName, coreName)
        saveFile.writeBytesCompressedAtomic(stateArray)
    }

    private fun getStateFile(
        fileName: String,
        coreName: String,
    ): File {
        val statesDirectories = File(directoriesManager.getStatesDirectory(), coreName)
        statesDirectories.mkdirs()
        return File(statesDirectories, fileName)
    }

    private fun getMetadataStateFile(
        stateFileName: String,
        coreName: String,
    ): File {
        val statesDirectories = File(directoriesManager.getStatesDirectory(), coreName)
        statesDirectories.mkdirs()
        return File(statesDirectories, SaveFileNames.stateMetadata(stateFileName))
    }

    private fun getAutoSaveFileName(game: Game) = SaveFileNames.autoSaveState(game)

    private fun getSlotSaveFileName(
        game: Game,
        index: Int,
    ) = SaveFileNames.slotState(game, index)

    companion object {
        const val MAX_STATES = 4
        private const val FILE_ACCESS_RETRIES = 3
    }
}
