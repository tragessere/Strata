package com.swordfish.lemuroid.lib.saves

import com.swordfish.lemuroid.common.kotlin.runCatchingWithRetry
import com.swordfish.lemuroid.common.kotlin.writeBytesAtomic
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.migrators.getSavesMigrator
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class SavesManager(
    private val directoriesManager: DirectoriesManager,
) {
    suspend fun getSaveRAM(
        game: Game,
        systemCoreConfig: SystemCoreConfig,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            val result =
                runCatchingWithRetry(FILE_ACCESS_RETRIES) {
                    val saveFile = getSaveFile(getSaveRAMFileName(game))
                    if (saveFile.exists() && saveFile.length() > 0) {
                        saveFile.readBytes()
                    } else {
                        val savesMigrator = systemCoreConfig.getSavesMigrator()
                        savesMigrator?.loadPreviousSaveForGame(game, directoriesManager)
                    }
                }
            result.getOrNull()
        }

    /**
     * Writes [data] as the save for [game], reporting whether it landed.
     *
     * Empty data is not a save and is never written: a core with no SRAM hands back an empty array on
     * every teardown, and writing those would replace a real save with nothing.
     */
    suspend fun setSaveRAM(
        game: Game,
        data: ByteArray,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (data.isEmpty()) {
                return@withContext false
            }

            val result =
                runCatchingWithRetry(FILE_ACCESS_RETRIES) {
                    val saveFile = getSaveFile(getSaveRAMFileName(game))
                    saveFile.writeBytesAtomic(data)
                }

            result
                .onFailure { Timber.e(it, "Unable to write the save file for ${game.fileName}") }
                .isSuccess
        }

    suspend fun getSaveRAMInfo(game: Game): SaveInfo =
        withContext(Dispatchers.IO) {
            val saveFile = getSaveFile(getSaveRAMFileName(game))
            val fileExists = saveFile.exists() && saveFile.length() > 0
            SaveInfo(fileExists, saveFile.lastModified(), saveFile.length())
        }

    private suspend fun getSaveFile(fileName: String): File =
        withContext(Dispatchers.IO) {
            val savesDirectory = directoriesManager.getSavesDirectory()
            File(savesDirectory, fileName)
        }

    private fun getSaveRAMFileName(game: Game) = SaveFileNames.saveRam(game)

    companion object {
        private const val FILE_ACCESS_RETRIES = 3
    }
}
