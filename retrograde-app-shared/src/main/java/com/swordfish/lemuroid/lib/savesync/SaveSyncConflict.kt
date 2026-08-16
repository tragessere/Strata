package com.swordfish.lemuroid.lib.savesync

/**
 * The folders a sync keeps in step, named here because a conflict is reported against one of them and
 * whatever displays it has to know how to read the paths inside.
 */
object SaveSyncFolders {
    const val SAVES = "saves"
    const val COVERS = "covers"
    const val STATES = "states"
    const val STATE_PREVIEWS = "state-previews"

    /** Synced for the cores the user picked and no others, each core holding a directory of its own. */
    val PER_CORE = setOf(STATES, STATE_PREVIEWS)
}

/**
 * What a sync would keep in step, given the way it is set up right now.
 *
 * A conflict outlives the settings which produced it, and the sync only ever records conflicts for
 * the paths it looks at. Anything it has stopped looking at is therefore a question nobody can
 * answer: the resolution would sit waiting for a sync which is never going to read it, while the row
 * stays on the conflicts screen and the dialog keeps standing in front of the game.
 */
data class SaveSyncScope(
    /** False when nothing is synced at all: no provider, no linked account, or syncing switched off. */
    val isEnabled: Boolean,
    /** Names of the cores whose states are synced. Empty leaves both state folders out entirely. */
    val syncedCores: Set<String>,
) {
    /** Decided the way the sync filters its own keys, so the two can never disagree about a path. */
    fun contains(conflict: SaveSyncConflict): Boolean =
        when {
            !isEnabled -> false
            conflict.folder !in SaveSyncFolders.PER_CORE -> true
            else -> syncedCores.any { conflict.relativePath.startsWith(it) }
        }

    fun filter(conflicts: List<SaveSyncConflict>): List<SaveSyncConflict> = conflicts.filter { contains(it) }
}

/**
 * A path whose local and remote copies both moved on since they last agreed, which makes picking a
 * winner a guess only the user can make.
 *
 * Sizes and modification times are recorded for both sides so the choice can be presented with
 * something meaningful to compare, and so a decision can be discarded if either side changes again
 * before it is applied.
 */
data class SaveSyncConflict(
    /** The synced folder this path belongs to, e.g. `saves` or `states`. */
    val folder: String,
    /** Path relative to [folder], the same key the sync and the baseline use. */
    val relativePath: String,
    val localSize: Long,
    val localModifiedAt: Long,
    val remoteSize: Long,
    val remoteModifiedAt: Long,
    /** When this divergence was first noticed, so re-detection does not keep resetting it. */
    val detectedAt: Long,
) {
    val id: String
        get() = buildId(folder, relativePath)

    companion object {
        fun buildId(
            folder: String,
            relativePath: String,
        ): String = "$folder/$relativePath"
    }
}

/**
 * Which copy of a conflicted path the user chose to keep.
 *
 * There is deliberately no "delete both": a conflict only ever arises with a file present on both
 * sides, so deletion is never one of the answers being weighed. Getting rid of a conflicted save
 * means resolving it first and then deleting it as normal, which works because resolving leaves a
 * baseline the two sides agree on for an ordinary deletion to propagate against.
 */
enum class ConflictResolution {
    KEEP_LOCAL,
    KEEP_REMOTE,
}
