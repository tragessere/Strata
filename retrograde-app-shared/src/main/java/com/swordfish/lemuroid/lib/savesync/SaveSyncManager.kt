package com.swordfish.lemuroid.lib.savesync

import android.app.Activity
import android.content.Context
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * What a sync changed on this device, for the callers which have to react to it.
 *
 * Empty after a sync which failed outright, since nothing reliable can be said about what it managed
 * to transfer first.
 */
data class SaveSyncResult(
    /** Custom artwork files this sync downloaded, replaced or removed locally. */
    val changedCovers: Set<File> = emptySet(),
    /**
     * Paths this sync refused to decide on because both sides had changed. Neither copy was touched,
     * and they will keep being reported until the user picks a winner.
     */
    val conflicts: List<SaveSyncConflict> = emptyList(),
    /**
     * Whether the sync gave up part way through, which is what makes the fields above an incomplete
     * account rather than an empty one. Callers which only act when something changed have to treat
     * this as "something may well have", since a run which failed still transferred whatever it got
     * to before it did.
     */
    val isPartial: Boolean = false,
)

abstract class SaveSyncManager {
    abstract fun getProvider(): String

    abstract fun getSettingsActivity(): Class<out Activity>?

    abstract fun isSupported(): Boolean

    abstract fun isConfigured(): Boolean

    abstract fun getLastSyncInfo(): String

    abstract fun getConfigInfo(): String

    abstract suspend fun sync(cores: Set<CoreID>): SaveSyncResult

    /** Conflicts still waiting on the user, kept up to date as syncs detect and clear them. */
    abstract fun pendingConflicts(): StateFlow<List<SaveSyncConflict>>

    /**
     * Records which copy to keep for each conflict, keyed by [SaveSyncConflict.id].
     *
     * Applying a choice means transferring a file, so this only stores the decision. Callers have to
     * start a sync afterwards for it to take effect.
     */
    abstract suspend fun requestConflictResolutions(resolutions: Map<String, ConflictResolution>)

    abstract fun computeSavesSpace(): String

    abstract fun computeStatesSpace(core: CoreID): String

    fun getDisplayNameForCore(
        context: Context,
        coreID: CoreID,
    ): String {
        val systems = GameSystem.findSystemForCore(coreID)
        val systemHasMultipleCores = systems.any { it.systemCoreConfigs.size > 1 }

        val chunks =
            mutableListOf<String>().apply {
                add(systems.joinToString(", ") { context.getString(it.shortTitleResId) })

                if (systemHasMultipleCores) {
                    add(coreID.coreDisplayName)
                }

                add(computeStatesSpace(coreID))
            }

        return chunks.joinToString(" - ")
    }
}
