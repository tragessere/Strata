package com.swordfish.lemuroid.app.mobile.feature.settings.savesync

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.savesync.ActionableSaveSyncConflicts
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.savesync.ConflictResolution
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class SaveSyncConflictsViewModel(
    private val application: Application,
    private val saveSyncManager: SaveSyncManager,
    private val retrogradeDatabase: RetrogradeDatabase,
) : ViewModel() {
    class Factory(
        private val application: Application,
        private val saveSyncManager: SaveSyncManager,
        private val retrogradeDatabase: RetrogradeDatabase,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SaveSyncConflictsViewModel(application, saveSyncManager, retrogradeDatabase) as T
    }

    val saveSyncInProgress = PendingOperationsMonitor(application.applicationContext).anySaveOperationInProgress()

    // Conflicts the sync no longer covers are left out. There is nothing to answer there: the choice
    // would be recorded and then wait for a run which never looks at that path again.
    val groups =
        ActionableSaveSyncConflicts(application, saveSyncManager)
            .observe()
            .mapLatest { conflicts ->
                SaveSyncConflictGrouping.group(conflicts, retrogradeDatabase.gameDao().asyncSelectAll())
            }.flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val choicesState = MutableStateFlow<Map<String, ConflictResolution>>(emptyMap())

    /** What the user has picked so far, keyed by [SaveSyncConflictGroup.id]. */
    val choices = choicesState.asStateFlow()

    fun choose(
        group: SaveSyncConflictGroup,
        resolution: ConflictResolution,
    ) {
        choicesState.value = choicesState.value + (group.id to resolution)
    }

    /**
     * Hands the choices over and starts a sync, which is what actually moves the files. Only the
     * groups the user answered are submitted, so the rest stay parked.
     */
    fun applyChoices() {
        val chosen = choicesState.value
        if (chosen.isEmpty()) return

        val groupsById = groups.value.associateBy { it.id }

        // A group is answered as a whole, so every file behind it takes the same resolution.
        val resolutions =
            chosen
                .flatMap { (groupId, resolution) ->
                    val group = groupsById[groupId] ?: return@flatMap emptyList()
                    group.conflicts.map { it.id to resolution }
                }.toMap()

        viewModelScope.launch {
            saveSyncManager.requestConflictResolutions(resolutions)
            // The choices stay on screen rather than being cleared here. They are only carried out
            // once the sync gets to them, and blanking the selections in the meantime would look
            // like the answer had been lost. They go away with the groups themselves.
            withContext(Dispatchers.IO) {
                SaveSyncWork.enqueueManualWork(application.applicationContext)
            }
        }
    }
}
