package com.swordfish.lemuroid.lib.saves

import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.savesync.SyncInstalledSavesStore
import timber.log.Timber

/*
   Why does this class exist? Because shit happens and we want to make sure we are prepared.
   This is the issue:

   User enables auto-save, plays, disables auto-save, plays for 10h, saves in game, re-enables
   auto-save and loses 10h worth of game.

   If we detect a more recent SRAM file, we basically avoid loading the state. This is also handy,
   if different cores share the same SRAM file. */
class SavesCoherencyEngine(
    val savesManager: SavesManager,
    val statesManager: StatesManager,
    private val syncInstalledSaves: SyncInstalledSavesStore,
    private val gameSessionStore: GameSessionStore,
) {
    /**
     * Records that a session is starting, so that the next launch can tell whether the auto-save it
     * finds is the one this session was supposed to leave behind.
     *
     * See [GameSessionStore] for why an auto-save cannot be trusted on its timestamp alone.
     */
    suspend fun onSessionStarted(
        game: Game,
        coreID: CoreID,
    ) {
        gameSessionStore.markSessionStarted(game, coreID)
    }

    suspend fun shouldDiscardAutoSaveState(
        game: Game,
        coreID: CoreID,
        // TODO Get rid of it when desmume is removed
        sramTimestampOverride: Long? = null,
    ): Boolean {
        val autoSave = statesManager.getAutoSaveInfo(game, coreID)
        if (!autoSave.exists) {
            return false
        }

        // Read even when a timestamp is being overridden below, because what is being asked here is
        // where the file came from rather than when it was written.
        val saveRAM = savesManager.getSaveRAMInfo(game)

        // A session which started after the auto-save was written and never wrote a newer one ended
        // without its state being saved, most likely because the write happening as the game was
        // backgrounded never completed. The auto-save is then a state from an earlier session, and
        // resuming it would rewind the player past everything the lost session did, in game saves
        // included. Boot from the save file instead, which is at worst as old as the state.
        val sessionStart = gameSessionStore.getSessionStart(game, coreID)
        if (sessionStart != null && autoSave.date + SESSION_TOLERANCE < sessionStart) {
            Timber.i("Discarding an auto-save which the session started at $sessionStart never rewrote")
            return true
        }

        // A save a sync installed was written by a session played on another device, so this state is
        // not its continuation however close together the two happen to have been written. Left to the
        // comparison below, a downloaded save landing within the tolerance of the local state would
        // resume into the state and undo the copy the sync had just brought down.
        if (syncInstalledSaves.isInstalled(SaveFileNames.saveRam(game), saveRAM.size, saveRAM.date)) {
            return true
        }

        val autoSRAM =
            if (sramTimestampOverride != null) {
                SaveInfo(true, sramTimestampOverride)
            } else {
                saveRAM
            }
        return autoSRAM.exists && autoSRAM.date > autoSave.date + TOLERANCE
    }

    companion object {
        private const val TOLERANCE = 30L * 1000L

        /**
         * Slack between a recorded session start and the auto-save it should have produced. A
         * session which is backgrounded almost as soon as it is resumed writes its state within a
         * second of the record, and filesystems which keep whole seconds of modification time can
         * round that back below it.
         */
        private const val SESSION_TOLERANCE = 2L * 1000L
    }
}
