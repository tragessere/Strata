package com.swordfish.lemuroid.lib.saves

import com.swordfish.lemuroid.lib.library.db.entity.Game

/**
 * Names of the files Lemuroid keeps for a game's saves and states.
 *
 * They live here, rather than in the managers writing them, so that code which measures or deletes
 * this data cannot drift from the code which creates it.
 */
object SaveFileNames {
    /**
     * This name should make it compatible with RetroArch so that users can freely sync saves across
     * the two application.
     */
    fun saveRam(game: Game) = saveRam(game.fileName)

    /**
     * The save file belonging to a rom, addressed by file name rather than by [Game].
     *
     * Needed by anything working backwards from a path, which has the rom's file name but not the
     * library row behind it.
     */
    fun saveRam(romFileName: String) = "${romFileName.substringBeforeLast(".")}.$SRM_EXTENSION"

    /**
     * Whether [fileName] names a file we can take in as a save.
     *
     * Only the extension we write ourselves is accepted. The contents of one of those are the raw
     * bytes the core exchanges, which is also what RetroArch keeps in its own ".srm" files, so a save
     * carried over from there is usable as it stands. Other emulators name their saves differently and
     * do not all agree on a layout, and one of those copied in unchanged would read as a corrupt save
     * rather than as a file we refused.
     */
    fun isSaveRam(fileName: String) = fileName.substringAfterLast(".", "").lowercase() == SRM_EXTENSION

    /**
     * Saves written by older versions, still read as a fallback whenever the current one is missing.
     * Deleting a save has to take these along, or the next launch would silently restore the data
     * which was just deleted.
     */
    fun legacySaveRams(game: Game) = LEGACY_SAVE_EXTENSIONS.map { "${game.baseName()}.$it" }

    fun autoSaveState(game: Game) = "${game.fileName}.$STATE_EXTENSION"

    /**
     * The rom an auto-save state belongs to, or null when [stateFileName] names something else.
     *
     * This is [autoSaveState] read backwards, and it is what lets a state be paired up with the save
     * file of the same game. Save slots and the metadata sidecars deliberately answer null: only the
     * auto-save is written by the same session as the save file, so only it can be reasoned about
     * alongside one.
     */
    fun romFileNameForAutoSaveState(stateFileName: String): String? =
        if (stateFileName.endsWith(".$STATE_EXTENSION")) {
            stateFileName.removeSuffix(".$STATE_EXTENSION")
        } else {
            null
        }

    fun slotState(
        game: Game,
        index: Int,
    ) = "${game.fileName}.slot${index + 1}"

    fun stateMetadata(stateFileName: String) = "$stateFileName.metadata"

    fun slotStatePreview(
        game: Game,
        index: Int,
    ) = "${slotState(game, index)}.jpg"

    private fun Game.baseName() = fileName.substringBeforeLast(".")

    private const val SRM_EXTENSION = "srm"
    private const val STATE_EXTENSION = "state"

    /** DeSmuME wrote ".dsv" saves, while melonDS used to write raw ".sav" ones. */
    private val LEGACY_SAVE_EXTENSIONS = listOf("dsv", "sav")
}
