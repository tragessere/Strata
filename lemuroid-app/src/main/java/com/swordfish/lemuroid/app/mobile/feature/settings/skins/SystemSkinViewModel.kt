package com.swordfish.lemuroid.app.mobile.feature.settings.skins

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.skin.ControllerSkinPreferences
import com.swordfish.lemuroid.lib.library.skin.DeltaSkinHandle
import com.swordfish.lemuroid.lib.library.skin.DeltaSkinManager
import com.swordfish.lemuroid.lib.library.skin.DeltaSkinSystemMapping
import com.swordfish.touchinput.deltaskin.DeltaSkinInfo
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

/**
 * Backs the per-system skin screen (the first settings layer): it exposes the currently selected
 * portrait and landscape skins, each with a full-width preview, and drills into the per-orientation
 * picker from there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemSkinViewModel(
    private val deltaSkinManager: DeltaSkinManager,
    private val controllerSkinPreferences: ControllerSkinPreferences,
    private val systemID: SystemID?,
    private val isTablet: Boolean,
) : ViewModel() {
    class Factory(
        private val deltaSkinManager: DeltaSkinManager,
        private val controllerSkinPreferences: ControllerSkinPreferences,
        private val systemDbName: String?,
        private val isTablet: Boolean,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val systemID = SystemID.values().firstOrNull { it.dbname == systemDbName }
            return SystemSkinViewModel(deltaSkinManager, controllerSkinPreferences, systemID, isTablet) as T
        }
    }

    /** The skin selected for one orientation: its display name and preview, both null when default. */
    data class OrientationSelection(
        val skinName: String? = null,
        val preview: SkinPreview? = null,
    )

    /** An installed skin usable for this system, with the orientations its artwork provides. */
    data class InstalledSkin(
        val id: String,
        val name: String,
        val orientations: List<Orientation>,
    )

    data class State(
        val portrait: OrientationSelection = OrientationSelection(),
        val landscape: OrientationSelection = OrientationSelection(),
        val installedSkins: List<InstalledSkin> = emptyList(),
    )

    private val refreshTrigger = MutableStateFlow(0)

    private val importErrors = Channel<Unit>(Channel.BUFFERED)
    val importErrorEvents = importErrors.receiveAsFlow()

    // Reloads on selection changes too, so the previews are already up to date while the per-orientation
    // picker is in front of this screen rather than reloading on the way back.
    val uiState =
        merge(
            refreshTrigger.map { },
            controllerSkinPreferences.observeSelectionChanges(),
        ).mapLatest { load() }
            .stateIn(viewModelScope, SharingStarted.Lazily, State())

    private suspend fun load(): State {
        val system = systemID ?: return State()
        val handles = deltaSkinManager.listSkins()
        val handlesById = handles.associateBy { it.id }
        return State(
            portrait = orientationSelection(handlesById, system, Orientation.PORTRAIT),
            landscape = orientationSelection(handlesById, system, Orientation.LANDSCAPE),
            installedSkins = installedSkins(handles, system),
        )
    }

    private fun installedSkins(
        handles: List<DeltaSkinHandle>,
        systemID: SystemID,
    ): List<InstalledSkin> =
        handles
            .filter { DeltaSkinSystemMapping.isCompatible(it.info.gameTypeIdentifier, systemID) }
            .map { handle ->
                val orientations =
                    Orientation.values().filter { handle.info.supportsOrientation(orientationName(it)) }
                InstalledSkin(handle.id, handle.info.name, orientations)
            }

    private fun orientationSelection(
        handlesById: Map<String, DeltaSkinHandle>,
        systemID: SystemID,
        orientation: Orientation,
    ): OrientationSelection {
        val id = controllerSkinPreferences.getSelectedSkinId(systemID, orientation) ?: return OrientationSelection()
        val handle = handlesById[id] ?: return OrientationSelection()
        val representation = deltaSkinManager.resolveRepresentation(handle.info, isTablet, orientationName(orientation))
        return OrientationSelection(
            skinName = handle.info.name,
            preview = representation?.let { SkinPreview(handle.directory, it) },
        )
    }

    fun importSkin(uri: Uri) {
        viewModelScope.launch {
            deltaSkinManager
                .importSkin(uri)
                .onSuccess { refresh() }
                .onFailure { importErrors.send(Unit) }
        }
    }

    /**
     * Removes a skin from the app (files and cached artwork), and reverts any orientation of this
     * system that had it selected back to the default controls.
     */
    fun deleteSkin(id: String) {
        viewModelScope.launch {
            systemID?.let { system ->
                Orientation.entries
                    .filter { controllerSkinPreferences.getSelectedSkinId(system, it) == id }
                    .forEach { controllerSkinPreferences.setSelectedSkinId(system, it, null) }
            }
            deltaSkinManager.deleteSkin(id)
            refresh()
        }
    }

    private fun orientationName(orientation: Orientation): String =
        when (orientation) {
            Orientation.LANDSCAPE -> DeltaSkinInfo.ORIENTATION_LANDSCAPE
            Orientation.PORTRAIT -> DeltaSkinInfo.ORIENTATION_PORTRAIT
        }

    private fun refresh() {
        refreshTrigger.value += 1
    }
}
