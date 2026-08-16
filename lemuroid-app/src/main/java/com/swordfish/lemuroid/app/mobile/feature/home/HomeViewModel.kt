package com.swordfish.lemuroid.app.mobile.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.settings.StorageFrameworkPickerLauncher
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.db.entity.displayTitle
import com.swordfish.lemuroid.lib.library.metaSystemID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class HomeViewModel(
    private val appContext: Context,
    retrogradeDb: RetrogradeDatabase,
    private val coresSelection: CoresSelection,
) : ViewModel() {
    companion object {
        const val DEBOUNCE_TIME = 100L
    }

    class Factory(
        val appContext: Context,
        val retrogradeDb: RetrogradeDatabase,
        val coresSelection: CoresSelection,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(appContext, retrogradeDb, coresSelection) as T
    }

    /** A group of games rendered as a single section in the grouped grid. */
    sealed interface Section {
        val games: List<Game>

        data class Favorites(
            override val games: List<Game>,
        ) : Section

        data class System(
            val metaSystem: MetaSystemID,
            override val games: List<Game>,
        ) : Section
    }

    data class UIState(
        /** True until the first state is built, so the UI can avoid flashing an empty message. */
        val isLoading: Boolean = true,
        val sections: List<Section> = emptyList(),
        val indexInProgress: Boolean = true,
        val showNoMicrophonePermissionCard: Boolean = false,
        val showNoGamesCard: Boolean = false,
        val showDesmumeDeprecatedCard: Boolean = false,
    )

    private val microphonePermissionEnabledState = MutableStateFlow(true)
    private val searchQueryState = MutableStateFlow("")
    private val uiStates = MutableStateFlow(UIState())

    fun getViewStates(): Flow<UIState> = uiStates

    fun changeSearchQuery(query: String) {
        searchQueryState.value = query
    }

    fun changeLocalStorageFolder(context: Context) {
        StorageFrameworkPickerLauncher.pickFolder(context)
    }

    fun updatePermissions(context: Context) {
        microphonePermissionEnabledState.value = isMicrophonePermissionGranted(context)
    }

    private fun isMicrophonePermissionGranted(context: Context): Boolean {
        val permissionResult =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            )

        return permissionResult == PackageManager.PERMISSION_GRANTED
    }

    private fun buildViewState(
        allGames: List<Game>,
        searchQuery: String,
        indexInProgress: Boolean,
        showMicrophoneCard: Boolean,
        showDesmumeWarning: Boolean,
    ): UIState =
        UIState(
            isLoading = false,
            sections = buildSections(allGames, searchQuery),
            indexInProgress = indexInProgress,
            showNoMicrophonePermissionCard = showMicrophoneCard,
            showNoGamesCard = allGames.isEmpty(),
            showDesmumeDeprecatedCard = showDesmumeWarning,
        )

    private fun buildSections(
        allGames: List<Game>,
        searchQuery: String,
    ): List<Section> {
        val query = searchQuery.trim()
        val filtered =
            if (query.isEmpty()) {
                allGames
            } else {
                allGames.filter { it.displayTitle.contains(query, ignoreCase = true) }
            }

        val sections = mutableListOf<Section>()

        val favorites = filtered.filter { it.isFavorite }
        if (favorites.isNotEmpty()) {
            sections += Section.Favorites(favorites)
        }

        filtered
            .groupBy { GameSystem.findById(it.systemId).metaSystemID() }
            .map { (metaSystem, games) -> Section.System(metaSystem, games) }
            .sortedBy { appContext.getString(it.metaSystem.titleResId) }
            .forEach { sections += it }

        return sections
    }

    init {
        viewModelScope.launch {
            val uiStatesFlow =
                combine(
                    retrogradeDb.gameDao().selectAll(),
                    searchQueryState,
                    indexingInProgress(appContext),
                    microphoneNotification(retrogradeDb),
                    desmumeWarningNotification(),
                    ::buildViewState,
                )

            uiStatesFlow
                .debounce(DEBOUNCE_TIME)
                .flowOn(Dispatchers.IO)
                .collect { uiStates.value = it }
        }
    }

    private fun indexingInProgress(appContext: Context) =
        PendingOperationsMonitor(appContext).anyLibraryOperationInProgress()

    private fun dsGamesCount(retrogradeDb: RetrogradeDatabase): Flow<Int> =
        retrogradeDb
            .gameDao()
            .selectSystemsWithCount()
            .map { systems ->
                systems
                    .firstOrNull { it.systemId == SystemID.NDS.dbname }
                    ?.count
                    ?: 0
            }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun microphoneNotification(db: RetrogradeDatabase): Flow<Boolean> =
        microphonePermissionEnabledState
            .flatMapLatest { isMicrophoneEnabled ->
                if (isMicrophoneEnabled) {
                    flowOf(false)
                } else {
                    combine(
                        coresSelection.getSelectedCores(),
                        dsGamesCount(db),
                    ) { cores, dsCount ->
                        cores.any { it.coreConfig.supportsMicrophone } &&
                            dsCount > 0
                    }
                }.distinctUntilChanged()
            }

    private fun desmumeWarningNotification(): Flow<Boolean> =
        coresSelection
            .getSelectedCores()
            .map { cores -> cores.any { it.coreConfig.coreID == CoreID.DESMUME } }
            .distinctUntilChanged()
}
