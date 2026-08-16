package com.swordfish.lemuroid.lib.saves

import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.savesync.SyncInstalledSavesStore

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
) {
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
    }
}
