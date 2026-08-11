package com.swordfish.lemuroid.lib.savesync

import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.SaveFileNames

/**
 * Picks the conflicts which hold up a single game out of the whole pending list.
 *
 * This runs the naming rules in `SaveFileNames` forwards, from the game to the paths it owns, rather
 * than backwards the way the conflicts screen has to. That makes it exact: a game is never credited
 * with a save which merely looks like it belongs to it.
 *
 * Cover conflicts are deliberately left out. They are worth resolving, but nothing about them
 * changes what a session loads or writes, so they are no reason to stand between the user and the
 * game.
 */
object GameSaveSyncConflicts {
    fun forGame(
        conflicts: List<SaveSyncConflict>,
        game: Game,
    ): List<SaveSyncConflict> {
        val saveNames = (listOf(SaveFileNames.saveRam(game)) + SaveFileNames.legacySaveRams(game)).toSet()

        // States and their previews live under a per core directory, and every name below one of them
        // begins with the rom's full file name. The trailing dot matters: without it a rom called
        // "mario.gb" would also claim the states belonging to "mario.gba".
        val statePrefix = "${game.fileName}."

        return conflicts.filter { conflict ->
            when (conflict.folder) {
                SaveSyncFolders.SAVES -> conflict.relativePath in saveNames

                SaveSyncFolders.STATES, SaveSyncFolders.STATE_PREVIEWS ->
                    conflict.relativePath
                        .substringAfter('/', "")
                        .startsWith(statePrefix)

                else -> false
            }
        }
    }
}
