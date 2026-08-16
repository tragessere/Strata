package com.swordfish.lemuroid.lib.saves

import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.savesync.SyncInstalledSavesStore
import timber.log.Timber

/**
 * Takes a save file the user picked and installs it as the save for a game.
 *
 * The file keeps nothing of where it came from. It is stored under the name the game's own save would
 * have, so a copy carried over from a computer is indistinguishable from one this device wrote, and
 * everything downstream (the sync, the data page, a later deletion) treats it as an ordinary save.
 */
class SaveImporter(
    private val savesManager: SavesManager,
    private val syncInstalledSaves: SyncInstalledSavesStore,
) {
    sealed interface Result {
        data object Imported : Result

        /**
         * The game already has a save, and the caller did not ask for it to be replaced. Reported
         * rather than resolved here, because overwriting a save is the one part of this the user has
         * to agree to.
         */
        data object SaveAlreadyExists : Result

        data object Failed : Result
    }

    suspend fun importSave(
        game: Game,
        data: ByteArray,
        replaceExisting: Boolean,
    ): Result {
        if (data.isEmpty()) {
            Timber.w("Refusing to import an empty save for ${game.fileName}")
            return Result.Failed
        }

        if (!replaceExisting && savesManager.getSaveRAMInfo(game).exists) {
            return Result.SaveAlreadyExists
        }

        if (!savesManager.setSaveRAM(game, data)) {
            return Result.Failed
        }

        markAsExternallyInstalled(game)
        return Result.Imported
    }

    /**
     * Records the import the same way a sync records a save it brought down.
     *
     * An imported save is in the same position as a synced one: it was not written by the session
     * which left the auto-save state sitting next to it, so resuming that state would put the game
     * back where it was before the import and overwrite the file on the way out. Timestamps very
     * nearly settle this on their own, since the file is stamped as it is written and the coherency
     * engine prefers a save which is newer than the state, but only nearly: a state saved in the
     * half minute before the import falls inside its tolerance. Saying so outright does not depend on
     * how far apart the two happen to be.
     */
    private suspend fun markAsExternallyInstalled(game: Game) {
        val fileName = SaveFileNames.saveRam(game)

        // Read back rather than computed, since what the store matches on is the file as it ended up
        // on disk. The write has already happened, so anything unexpected here costs the certainty
        // and nothing else: launches fall back to comparing timestamps.
        val saveInfo = savesManager.getSaveRAMInfo(game)
        if (!saveInfo.exists) {
            Timber.w("Imported save for ${game.fileName} could not be read back")
            return
        }

        syncInstalledSaves.update(
            installed = mapOf(fileName to SyncInstalledSavesStore.Installed(saveInfo.size, saveInfo.date)),
            forgotten = emptySet(),
        )
    }
}
