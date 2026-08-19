package com.swordfish.lemuroid.app.mobile.feature.settings.skins

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.skin.ControllerSkinPreferences
import com.swordfish.lemuroid.lib.library.skin.DeltaSkinManager
import com.swordfish.lemuroid.lib.library.skin.DeltaSkinSystemMapping
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager.Orientation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerSkinsViewModel(
    private val deltaSkinManager: DeltaSkinManager,
    private val controllerSkinPreferences: ControllerSkinPreferences,
) : ViewModel() {
    class Factory
        @Inject
        constructor(
            private val deltaSkinManager: DeltaSkinManager,
            private val controllerSkinPreferences: ControllerSkinPreferences,
        ) : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ControllerSkinsViewModel(deltaSkinManager, controllerSkinPreferences) as T
        }

    data class SystemSkins(
        val systemID: SystemID,
        val portraitSkinName: String?,
        val landscapeSkinName: String?,
    )

    data class State(
        val systems: List<SystemSkins> = emptyList(),
    )

    private val refreshTrigger = MutableStateFlow(0)

    private val importErrors = Channel<Unit>(Channel.BUFFERED)
    val importErrorEvents = importErrors.receiveAsFlow()

    // Reloads on selection changes too, so the list is already up to date while the drill-down screens
    // are in front of it rather than reloading (and briefly showing stale summaries) on the way back.
    val uiState =
        merge(
            refreshTrigger.map { },
            controllerSkinPreferences.observeSelectionChanges(),
        ).mapLatest { load() }
            .stateIn(viewModelScope, SharingStarted.Lazily, State())

    private suspend fun load(): State {
        val handlesById = deltaSkinManager.listSkins().associateBy { it.id }
        val systems =
            DeltaSkinSystemMapping.SUPPORTED_SYSTEMS.map { systemID ->
                SystemSkins(
                    systemID = systemID,
                    portraitSkinName = selectedSkinName(handlesById, systemID, Orientation.PORTRAIT),
                    landscapeSkinName = selectedSkinName(handlesById, systemID, Orientation.LANDSCAPE),
                )
            }
        return State(systems)
    }

    private fun selectedSkinName(
        handlesById: Map<String, com.swordfish.lemuroid.lib.library.skin.DeltaSkinHandle>,
        systemID: SystemID,
        orientation: Orientation,
    ): String? {
        val id = controllerSkinPreferences.getSelectedSkinId(systemID, orientation) ?: return null
        return handlesById[id]?.info?.name
    }

    fun refresh() {
        refreshTrigger.value += 1
    }

    fun importSkin(uri: Uri) {
        viewModelScope.launch {
            deltaSkinManager
                .importSkin(uri)
                .onSuccess { refresh() }
                .onFailure { importErrors.send(Unit) }
        }
    }
}
