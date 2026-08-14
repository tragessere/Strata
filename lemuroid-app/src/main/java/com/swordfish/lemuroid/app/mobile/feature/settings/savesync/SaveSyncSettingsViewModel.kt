package com.swordfish.lemuroid.app.mobile.feature.settings.savesync

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.savesync.ActionableSaveSyncConflicts
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class SaveSyncSettingsViewModel(
    private val application: Application,
    private val saveSyncManager: SaveSyncManager,
) : ViewModel() {
    class Factory(
        private val application: Application,
        private val saveSyncManager: SaveSyncManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SaveSyncSettingsViewModel(application, saveSyncManager) as T
    }

    /**
     * Started eagerly rather than on first collection: WorkManager only answers asynchronously, so
     * the sooner the query goes out the smaller the window in which we do not yet have an answer.
     * Asking at construction usually means the real state has landed before anything composes.
     *
     * That window cannot be closed entirely, and it is filled with `true` because the two ways of
     * being wrong are not equally bad. Guessing idle offers a working sync button that races the
     * worker; guessing busy costs a moment of disabled rows.
     */
    val saveSyncInProgress =
        PendingOperationsMonitor(getContext())
            .anySaveOperationInProgress()
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Counted in saves rather than files, since a single savestate is spread over as many as three
     * of them and reporting it as three conflicts would be nonsense. Grouping needs no game titles
     * to work out how many there are, so the library is left out of it here.
     *
     * Only conflicts the sync would still act on are counted, so the row goes away as soon as the
     * settings above it stop covering them rather than offering a screen where nothing can be done.
     */
    val pendingConflictCount =
        ActionableSaveSyncConflicts(getContext(), saveSyncManager)
            .observe()
            .map { SaveSyncConflictGrouping.group(it, emptyList()).size }
            .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    data class State(
        val isConfigured: Boolean = false,
        val configInfo: String = "",
        val savesSpace: String = "",
        val lastSyncInfo: String = "",
        val coreNames: List<String> = emptyList(),
        val coreVisibleNames: List<String> = emptyList(),
        val provider: String = "",
        val settingsActivity: Class<out Activity>? = null,
    )

    private val refreshTrigger = MutableStateFlow(0)

    /**
     * Rebuilt whenever a sync starts or stops as well as on an explicit refresh, so that the last
     * sync time is current the moment the worker finishes instead of waiting for the screen to be
     * resumed. The worker records the timestamp before it reports success, so it is already written
     * by the time the flow reports the sync as no longer running.
     */
    val uiState =
        combine(refreshTrigger, saveSyncInProgress) { _, _ -> }
            .mapLatest { buildState() }
            .flowOn(Dispatchers.IO)
            .stateIn(
                viewModelScope,
                SharingStarted.Lazily,
                State(),
            )

    fun refresh() {
        refreshTrigger.value += 1
    }

    private fun buildState(): State =
        State(
            saveSyncManager.isConfigured(),
            saveSyncManager.getConfigInfo(),
            saveSyncManager.computeSavesSpace(),
            saveSyncManager.getLastSyncInfo(),
            computeCoreNames(),
            computeCoreVisibleNames(),
            saveSyncManager.getProvider(),
            saveSyncManager.getSettingsActivity(),
        )

    private fun computeCoreNames(): List<String> = CoreID.entries.map { it.coreName }

    private fun computeCoreVisibleNames(): List<String> {
        val context = getContext()
        return CoreID.entries.map { saveSyncManager.getDisplayNameForCore(context, it) }
    }

    private fun getContext(): Context = application.applicationContext
}
