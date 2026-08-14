package com.swordfish.lemuroid.lib.storage

import android.content.Context
import com.swordfish.lemuroid.common.files.safeDelete
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.SaveFileNames
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.storage.local.GameCacheUtils
import com.swordfish.lemuroid.lib.storage.local.LocalStorageProvider
import com.swordfish.lemuroid.lib.storage.local.StorageAccessFrameworkProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Measures and deletes the data Lemuroid keeps for a single game.
 *
 * The game file itself is never touched: everything here is data the app created and can recreate,
 * with the exception of saves and states which are gone for good.
 */
class GameFilesManager(
    private val appContext: Context,
    private val directoriesManager: DirectoriesManager,
    private val retrogradeDatabase: RetrogradeDatabase,
    private val lemuroidLibrary: LemuroidLibrary,
) {
    enum class GameDataType {
        /** Custom cover image the user picked, stored next to the game file. */
        ARTWORK,

        /** In game saves, shared by every core of a system. */
        SAVES,

        /** Auto save and save slots, per core, together with their previews. */
        STATES,

        /** Copy of the game extracted out of an archive, or pulled out of external storage. */
        EXTRACTED_ROM,
    }

    data class DeleteResult(
        val freedBytes: Long,
        val failedTypes: Set<GameDataType>,
    )

    /** Sizes of the data currently stored for [game], leaving out anything which is not present. */
    suspend fun computeSizes(game: Game): Map<GameDataType, Long> =
        withContext(Dispatchers.IO) {
            GameDataType.entries
                .associateWith { computeSize(game, it) }
                .filterValues { it > 0L }
        }

    suspend fun delete(
        game: Game,
        types: Set<GameDataType>,
    ): DeleteResult =
        withContext(Dispatchers.IO) {
            var freedBytes = 0L
            val failedTypes = mutableSetOf<GameDataType>()

            types.forEach { type ->
                // Measured before the deletion, since afterwards there is nothing left to size up.
                val size = computeSize(game, type)

                val deleted =
                    runCatching { deleteData(game, type) }
                        .getOrElse {
                            Timber.e(it, "Error while deleting $type for ${game.fileName}")
                            false
                        }

                if (deleted) {
                    freedBytes += size
                } else {
                    failedTypes.add(type)
                }
            }

            DeleteResult(freedBytes, failedTypes)
        }

    private fun computeSize(
        game: Game,
        type: GameDataType,
    ): Long =
        when (type) {
            GameDataType.ARTWORK -> lemuroidLibrary.getGameCoverSize(game)
            else -> filesFor(game, type).filter { it.isFile }.sumOf { it.length() }
        }

    private suspend fun deleteData(
        game: Game,
        type: GameDataType,
    ): Boolean =
        when (type) {
            GameDataType.ARTWORK -> lemuroidLibrary.deleteGameCover(game)
            // Every delete is attempted, so a single failure does not leave the rest behind.
            else ->
                filesFor(game, type)
                    .filter { it.isFile }
                    .map { it.safeDelete() }
                    .all { it }
        }

    private fun filesFor(
        game: Game,
        type: GameDataType,
    ): List<File> =
        when (type) {
            GameDataType.ARTWORK -> emptyList()
            GameDataType.SAVES -> saveFiles(game)
            GameDataType.STATES -> stateFiles(game)
            GameDataType.EXTRACTED_ROM -> extractedRomFiles(game)
        }

    private fun saveFiles(game: Game): List<File> {
        val savesDirectory = directoriesManager.getSavesDirectory()
        val fileNames = listOf(SaveFileNames.saveRam(game)) + SaveFileNames.legacySaveRams(game)
        return fileNames.map { File(savesDirectory, it) }
    }

    private fun stateFiles(game: Game): List<File> {
        val statesDirectory = directoriesManager.getStatesDirectory()
        val previewsDirectory = directoriesManager.getStatesPreviewDirectory()

        val slots = 0 until StatesManager.MAX_STATES
        val stateNames = listOf(SaveFileNames.autoSaveState(game)) + slots.map { SaveFileNames.slotState(game, it) }

        // States are stored per core, and a game can be played by more than one of them.
        return coreNamesFor(game).flatMap { coreName ->
            val states =
                stateNames.flatMap {
                    listOf(
                        File(File(statesDirectory, coreName), it),
                        File(File(statesDirectory, coreName), SaveFileNames.stateMetadata(it)),
                    )
                }

            val previews =
                slots.map {
                    File(File(previewsDirectory, coreName), SaveFileNames.slotStatePreview(game, it))
                }

            states + previews
        }
    }

    private fun extractedRomFiles(game: Game): List<File> {
        val dataFiles = retrogradeDatabase.dataFileDao().selectDataFilesForGame(game.id)

        return CACHE_SUBFOLDERS.flatMap { subfolder ->
            val gameFile = GameCacheUtils.getCacheFileForGame(subfolder, appContext, game)
            val dataFileCopies =
                dataFiles.map { GameCacheUtils.getDataFileForGame(subfolder, appContext, game, it) }
            listOf(gameFile) + dataFileCopies
        }
    }

    private fun coreNamesFor(game: Game): List<String> =
        runCatching {
            GameSystem
                .findById(game.systemId)
                .systemCoreConfigs
                .map { it.coreID.coreName }
        }.getOrElse {
            Timber.e(it, "Unable to resolve cores for system ${game.systemId}")
            emptyList()
        }

    companion object {
        private val CACHE_SUBFOLDERS =
            listOf(
                StorageAccessFrameworkProvider.SAF_CACHE_SUBFOLDER,
                LocalStorageProvider.LOCAL_STORAGE_CACHE_SUBFOLDER,
            )
    }
}
