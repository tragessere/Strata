package com.swordfish.lemuroid.app.shared.savesync

import android.content.Context
import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.savesync.SaveSyncConflict
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import com.swordfish.lemuroid.lib.savesync.SaveSyncScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The pending conflicts the sync is still in a position to do something about.
 *
 * Everything which asks the user to choose a copy goes through here, so that no question is put
 * which the answer cannot reach. A conflict falls out of scope when save syncing is switched off or
 * the account is unlinked, and a state conflict falls out of it when its core is unchecked under
 * "sync states": in each case the sync stops visiting those paths, and a resolution recorded against
 * one would wait for a run which never reads it.
 *
 * The sync prunes the store itself as soon as it next runs, which is what keeps the file from
 * growing. This is the other half of it: it takes effect the moment the setting changes rather than
 * at the next sync, and it still holds when syncing is off and so no sync is coming.
 */
class ActionableSaveSyncConflicts(
    context: Context,
    private val saveSyncManager: SaveSyncManager,
) {
    private val appContext = context.applicationContext

    private val preferences =
        FlowSharedPreferences(SharedPreferencesHelper.getSharedPreferences(appContext))

    // The same default the sync worker reads this with. Disagreeing would either hide conflicts a
    // sync is going to keep raising, or ask about ones it is never going to look at.
    private val isSyncEnabled =
        preferences.getBoolean(appContext.getString(R.string.pref_key_save_sync_enable), true)

    private val syncedCores =
        preferences.getStringSet(appContext.getString(R.string.pref_key_save_sync_cores), emptySet())

    // Held from here on, rather than asked for at each call, because asking is also what sets the
    // conflict store reading itself in the background. Doing that when this is built puts it well
    // before the first game launch, which is the one caller that cannot wait for the answer.
    private val pendingConflicts = saveSyncManager.pendingConflicts()

    /**
     * Answers on the spot, for the launch path, which has to decide whether to let a game through
     * before it can suspend. Both preferences are already in memory by then, and the pending list is
     * a flow which is only read for its current value.
     */
    fun current(): List<SaveSyncConflict> =
        scopeOf(isSyncEnabled.get(), syncedCores.get())
            .filter(pendingConflicts.value)

    /**
     * Kept up to date as conflicts are detected and cleared, and as the settings behind the scope
     * change, so a screen showing them empties out the moment the sync stops covering them.
     */
    fun observe(): Flow<List<SaveSyncConflict>> =
        combine(
            pendingConflicts,
            isSyncEnabled.asFlow(),
            syncedCores.asFlow(),
        ) { conflicts, isEnabled, cores ->
            scopeOf(isEnabled, cores).filter(conflicts)
        }

    private fun scopeOf(
        isEnabled: Boolean,
        cores: Set<String>,
    ) = SaveSyncScope(
        isEnabled = saveSyncManager.isSupported() && saveSyncManager.isConfigured() && isEnabled,
        syncedCores = cores,
    )
}
