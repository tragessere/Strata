/*
 * GameLibrary.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.lib.library

import android.net.Uri
import com.swordfish.lemuroid.common.coroutines.batchWithSizeAndTime
import com.swordfish.lemuroid.lib.bios.BiosManager
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.DataFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.metadata.GameMetadata
import com.swordfish.lemuroid.lib.library.metadata.GameMetadataProvider
import com.swordfish.lemuroid.lib.library.metadata.LibretroCoverUrls
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.GameCoversManager
import com.swordfish.lemuroid.lib.storage.GroupedStorageFiles
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.StorageProvider
import com.swordfish.lemuroid.lib.storage.StorageProviderRegistry
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream

class LemuroidLibrary(
    private val retrogradedb: RetrogradeDatabase,
    private val storageProviderRegistry: Lazy<StorageProviderRegistry>,
    private val gameMetadataProvider: Lazy<GameMetadataProvider>,
    private val biosManager: BiosManager,
    private val gameCoversManager: GameCoversManager,
) {
    suspend fun indexLibrary() {
        val startedAtMs = System.currentTimeMillis()

        val completed =
            try {
                indexProviders(startedAtMs)
                true
            } catch (_: CancellationException) {
                // Don't bother adding a stack trace for cancellation, just a simple log
                Timber.i("Library indexing cancelled")
                false
            } catch (e: Throwable) {
                Timber.e(e, "Library indexing stopped due to exception")
                false
            }
        if (completed) {
            cleanUp(startedAtMs)
        } else {
            Timber.w("Skipping clean up, indexing did not reach every file")
        }

        val executionTime = System.currentTimeMillis() - startedAtMs
        val outcome = if (completed) "completed" else "interrupted"
        Timber.i("Library indexing $outcome in: $executionTime ms")
    }

    @OptIn(FlowPreview::class)
    private suspend fun indexProviders(startedAtMs: Long) {
        val gameMetadata = gameMetadataProvider.get()
        val enabledProviders = storageProviderRegistry.get().enabledProviders
        enabledProviders
            .asFlow()
            .flatMapConcat { indexSingleProvider(it, startedAtMs, gameMetadata) }
            .collect()
    }

    @OptIn(FlowPreview::class)
    private fun indexSingleProvider(
        provider: StorageProvider,
        startedAtMs: Long,
        gameMetadata: GameMetadataProvider,
    ): Flow<Unit> =
        provider
            .listBaseStorageFiles()
            .flatMapConcat { StorageFilesMerger.mergeDataFiles(provider, it).asFlow() }
            .batchWithSizeAndTime(MAX_BUFFER_SIZE, MAX_TIME)
            .flatMapMerge { processBatch(it, provider, startedAtMs, gameMetadata) }

    private suspend fun processBatch(
        batch: List<GroupedStorageFiles>,
        provider: StorageProvider,
        startedAtMs: Long,
        gameMetadata: GameMetadataProvider,
    ) = flow<Unit> {
        val entries = batch.map { fetchEntriesFromDatabase(it) }

        val existingEntries = entries.filterIsInstance<ScanEntry.GameFile>()
        handleExistingEntries(existingEntries, startedAtMs)

        val newEntries =
            entries
                .filterIsInstance<ScanEntry.File>()
                .map { buildEntryFromMetadata(it.file, provider, gameMetadata, startedAtMs) }

        handleNewEntries(newEntries, startedAtMs, provider)
    }

    private fun fetchEntriesFromDatabase(storageFile: GroupedStorageFiles): ScanEntry {
        Timber.d("Retrieving scan entry for uri: ${storageFile.primaryFile}")
        val game = retrogradedb.gameDao().selectByFileUri(storageFile.primaryFile.uri.toString())
        return buildScanEntry(storageFile, game)
    }

    private fun buildScanEntry(
        storageFile: GroupedStorageFiles,
        game: Game?,
    ): ScanEntry =
        if (game != null) {
            ScanEntry.GameFile(storageFile, game)
        } else {
            ScanEntry.File(storageFile)
        }

    private fun handleExistingEntries(
        entries: List<ScanEntry.GameFile>,
        startedAtMs: Long,
    ) {
        updateGames(entries, startedAtMs)
        updateDataFiles(entries, startedAtMs)
    }

    private fun updateGames(
        entries: List<ScanEntry.GameFile>,
        startedAtMs: Long,
    ) {
        val updatedGames =
            entries
                .map { it.game.copy(lastIndexedAt = startedAtMs) }

        updatedGames
            .forEach { Timber.d("Updating game: $it") }

        retrogradedb.gameDao().update(updatedGames)
    }

    private fun updateDataFiles(
        entries: List<ScanEntry.GameFile>,
        startedAtMs: Long,
    ) {
        val dataFiles =
            entries.flatMap { (storageFile, game) ->
                storageFile.dataFiles.map { convertIntoDataFile(game.id, it, startedAtMs) }
            }

        dataFiles
            .forEach { Timber.d("Updating data file: $it") }

        retrogradedb.dataFileDao().insert(dataFiles)
    }

    private fun convertIntoDataFile(
        gameId: Int,
        baseStorageFile: BaseStorageFile,
        startedAtMs: Long,
    ): DataFile =
        DataFile(
            gameId = gameId,
            fileUri = baseStorageFile.uri.toString(),
            fileName = baseStorageFile.name,
            lastIndexedAt = startedAtMs,
            path = baseStorageFile.path,
        )

    private fun handleNewEntries(
        entries: List<ScanEntry>,
        startedAtMs: Long,
        provider: StorageProvider,
    ) {
        val gameFiles =
            entries
                .filterIsInstance<ScanEntry.GameFile>()

        val unknownFiles =
            entries
                .filterIsInstance<ScanEntry.File>()
                .flatMap { it.file.allFiles() }

        handleNewGames(gameFiles, startedAtMs)
        handleUnknownFiles(provider, unknownFiles, startedAtMs)
    }

    private fun handleNewGames(
        pairs: List<ScanEntry.GameFile>,
        startedAtMs: Long,
    ) {
        val games =
            pairs
                .map { it.game }

        games.forEach { Timber.d("Insert: $it") }

        val gameIds = retrogradedb.gameDao().insert(games)
        val dataFiles =
            pairs
                .map { it.file.dataFiles }
                .zip(gameIds)
                .flatMap { (files, gameId) ->
                    files.map {
                        convertIntoDataFile(gameId.toInt(), it, startedAtMs)
                    }
                }

        retrogradedb.dataFileDao().insert(dataFiles)
    }

    private fun handleUnknownFiles(
        provider: StorageProvider,
        files: List<BaseStorageFile>,
        startedAtMs: Long,
    ) {
        files.forEach { baseStorageFile ->
            val storageFile = safeStorageFile(provider, baseStorageFile)
            val inputStream = storageFile?.uri?.let { provider.getInputStream(it) }

            if (storageFile != null && inputStream != null) {
                biosManager.tryAddBiosAfter(storageFile, inputStream, startedAtMs)
            }
        }
    }

    private suspend fun buildEntryFromMetadata(
        groupedStorageFile: GroupedStorageFiles,
        provider: StorageProvider,
        metadataProvider: GameMetadataProvider,
        startedAtMs: Long,
    ): ScanEntry {
        val game =
            sortedFilesForScanning(groupedStorageFile)
                .asFlow()
                .mapNotNull { safeStorageFile(provider, it) }
                .mapNotNull { storageFile ->
                    val metadata = metadataProvider.retrieveMetadata(storageFile)
                    convertGameMetadataToGame(groupedStorageFile, storageFile, metadata, startedAtMs)
                }.firstOrNull()

        return buildScanEntry(groupedStorageFile, game)
    }

    private fun safeStorageFile(
        provider: StorageProvider,
        baseStorageFile: BaseStorageFile,
    ): StorageFile? =
        runCatching { provider.getStorageFile(baseStorageFile) }
            .getOrNull()

    private fun cleanUp(startedAtMs: Long) {
        kotlin.runCatching {
            removeDeletedBios(startedAtMs)
        }
        kotlin.runCatching {
            removeDeletedGames(startedAtMs)
        }
        kotlin.runCatching {
            removeDeletedDataFiles(startedAtMs)
        }
    }

    private fun removeDeletedBios(startedAtMs: Long) {
        biosManager.deleteBiosBefore(startedAtMs)
    }

    private fun sortedFilesForScanning(groupedStorageFile: GroupedStorageFiles): List<BaseStorageFile> =
        groupedStorageFile.dataFiles.sortedBy {
            it.name
        } + listOf(groupedStorageFile.primaryFile)

    private fun convertGameMetadataToGame(
        groupedStorageFile: GroupedStorageFiles,
        storageFile: StorageFile,
        gameMetadata: GameMetadata?,
        lastIndexedAt: Long,
    ): Game? {
        if (gameMetadata == null) {
            return null
        }

        val gameSystem = GameSystem.findById(gameMetadata.system!!)

        // If the databased matched a data file (as with bin/cue) we force link the primary filename
        val fileName =
            if (groupedStorageFile.dataFiles.isNotEmpty()) {
                groupedStorageFile.primaryFile.name
            } else {
                storageFile.name
            }

        // Custom artwork the user picked takes precedence over the remote cover.
        val customArtUrl =
            runCatching { gameCoversManager.getCoverUri(gameSystem.id.dbname, fileName) }
                .getOrNull()
                ?.toString()

        return Game(
            fileName = fileName,
            fileUri = groupedStorageFile.primaryFile.uri.toString(),
            title = gameMetadata.name ?: groupedStorageFile.primaryFile.name,
            systemId = gameSystem.id.dbname,
            developer = gameMetadata.developer,
            coverFrontUrl = customArtUrl ?: gameMetadata.thumbnail,
            lastIndexedAt = lastIndexedAt,
        )
    }

    /**
     * Stores [inputStream] as custom artwork for [game] and returns the uri of the stored image, or
     * null if it could not be written.
     */
    fun writeGameCover(
        game: Game,
        imageExtension: String,
        inputStream: InputStream,
    ): Uri? = gameCoversManager.writeCover(game, imageExtension, inputStream)

    /** Size in bytes of the custom artwork stored for [game], or 0 if there is none. */
    fun getGameCoverSize(game: Game): Long = runCatching { gameCoversManager.getCoverSize(game) }.getOrDefault(0L)

    /**
     * Points every game at the custom artwork currently on disk, returning the cover urls which
     * changed.
     *
     * A scan only resolves covers for games it has already seen once, so artwork which arrived from
     * a save sync, or which a sync deleted, would otherwise go unnoticed until the game row is
     * recreated from scratch.
     */
    suspend fun refreshCustomCovers(): List<String> =
        withContext(Dispatchers.IO) {
            // Read once up front, so this costs a listing per system rather than a lookup per game.
            val storedCovers: Map<String, Uri> =
                runCatching { gameCoversManager.listCovers() }.getOrDefault(emptyMap())

            val updatedGames =
                retrogradedb
                    .gameDao()
                    .asyncSelectAll()
                    .mapNotNull { game ->
                        val coverFrontUrl = resolveCoverUrl(game, storedCovers)
                        game
                            .copy(coverFrontUrl = coverFrontUrl)
                            .takeIf { coverFrontUrl != game.coverFrontUrl }
                    }

            if (updatedGames.isEmpty()) {
                return@withContext emptyList()
            }

            Timber.i("Refreshing custom artwork for ${updatedGames.size} games")
            retrogradedb.gameDao().update(updatedGames)
            updatedGames.mapNotNull { it.coverFrontUrl }
        }

    private fun resolveCoverUrl(
        game: Game,
        storedCovers: Map<String, Uri>,
    ): String? {
        val storedCover = storedCovers[gameCoversManager.coverKey(game)]

        if (storedCover != null) {
            return storedCover.toString()
        }

        // The artwork this game was pointing at is gone, so fall back to the libretro boxart. Games
        // which never had custom artwork are left untouched, so a cover which legitimately has no
        // url does not end up pointing at an image which does not exist.
        if (gameCoversManager.isStoredCover(game.coverFrontUrl)) {
            return runCatching {
                LibretroCoverUrls.forGameName(GameSystem.findById(game.systemId), game.title)
            }.getOrNull()
        }

        return game.coverFrontUrl
    }

    /**
     * Deletes the custom artwork stored for [game] and points the game back at the cover from the
     * libretro database.
     *
     * A scan only resolves covers for games it has never seen before, so the original url has to be
     * rebuilt here rather than left for the next library scan to restore.
     */
    suspend fun deleteGameCover(game: Game): Boolean {
        if (!gameCoversManager.deleteCover(game)) {
            return false
        }

        val system = GameSystem.findById(game.systemId)
        val originalCover = LibretroCoverUrls.forGameName(system, game.title)
        retrogradedb.gameDao().update(game.copy(coverFrontUrl = originalCover))
        return true
    }

    private fun removeDeletedDataFiles(startedAtMs: Long) {
        Timber.d("Deleting data files from db before: $startedAtMs")
        val dataFiles = retrogradedb.dataFileDao().selectByLastIndexedAtLessThan(startedAtMs)
        retrogradedb.dataFileDao().delete(dataFiles)
    }

    private fun removeDeletedGames(startedAtMs: Long) {
        Timber.d("Deleting games from db before: $startedAtMs")
        val games = retrogradedb.gameDao().selectByLastIndexedAtLessThan(startedAtMs)
        retrogradedb.gameDao().delete(games)
    }

    fun getGameFiles(
        game: Game,
        dataFiles: List<DataFile>,
        allowVirtualFiles: Boolean,
    ): RomFiles {
        val provider = storageProviderRegistry.get()
        return provider.getProvider(game).getGameRomFiles(game, dataFiles, allowVirtualFiles)
    }

    private sealed class ScanEntry {
        data class GameFile(
            val file: GroupedStorageFiles,
            val game: Game,
        ) : ScanEntry()

        data class File(
            val file: GroupedStorageFiles,
        ) : ScanEntry()
    }

    companion object {
        // We batch database updates to avoid unnecessary UI updates.
        const val MAX_BUFFER_SIZE = 200
        const val MAX_TIME = 5000
    }
}
