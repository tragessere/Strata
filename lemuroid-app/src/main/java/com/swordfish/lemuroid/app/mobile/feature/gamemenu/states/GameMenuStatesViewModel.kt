package com.swordfish.lemuroid.app.mobile.feature.gamemenu.states

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuActivity
import com.swordfish.lemuroid.app.shared.gamemenu.GameMenuHelper
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import kotlinx.coroutines.flow.flow

class GameMenuStatesViewModel(
    private val application: Application,
    private val gameMenuRequest: GameMenuActivity.GameMenuRequest,
    private val statesManager: StatesManager,
    private val disableMissingEntries: Boolean,
    private val includeAutoSave: Boolean,
    private val statesPreviewManager: StatesPreviewManager,
) : ViewModel() {
    class Factory(
        private val application: Application,
        private val gameMenuRequest: GameMenuActivity.GameMenuRequest,
        private val statesManager: StatesManager,
        private val disableMissingEntries: Boolean,
        private val includeAutoSave: Boolean,
        private val statesPreviewManager: StatesPreviewManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GameMenuStatesViewModel(
                application,
                gameMenuRequest,
                statesManager,
                disableMissingEntries,
                includeAutoSave,
                statesPreviewManager,
            ) as T
    }

    data class StateEntry(
        val title: String,
        val description: String,
        val enabled: Boolean,
        val preview: Bitmap?,
        /** The save slot this row stands for, or null for the auto-save. */
        val slot: Int?,
    )

    data class State(
        val entries: List<StateEntry> = emptyList(),
    )

    val uiStates =
        flow {
            val slotsInfo = statesManager.getSavedSlotsInfo(gameMenuRequest.game, gameMenuRequest.coreConfig.coreID)

            val slotEntries =
                slotsInfo.mapIndexed { index, slotInfo ->
                    val title =
                        application.applicationContext.getString(
                            R.string.game_menu_state,
                            (index + 1).toString(),
                        )
                    val description = GameMenuHelper.getSaveStateDescription(slotInfo)
                    val isEnabled = !disableMissingEntries || slotInfo.exists
                    val preview =
                        GameMenuHelper.getSaveStateBitmap(
                            application.applicationContext,
                            statesPreviewManager,
                            slotInfo,
                            gameMenuRequest.game,
                            gameMenuRequest.coreConfig.coreID,
                            index,
                        )

                    StateEntry(title, description, isEnabled, preview, index)
                }

            emit(State(autoSaveEntry() + slotEntries))
        }

    /**
     * The state the last session left behind, offered alongside the slots so it can be reached on
     * purpose.
     *
     * A launch resumes into it by itself when it can vouch for it, and deliberately does not when it
     * cannot, which is the case this row exists for: the state is still on disk, and the player is
     * the one who knows whether it is the one they want.
     */
    private suspend fun autoSaveEntry(): List<StateEntry> {
        if (!includeAutoSave) return emptyList()

        val context = application.applicationContext
        val autoSaveInfo = statesManager.getAutoSaveInfo(gameMenuRequest.game, gameMenuRequest.coreConfig.coreID)

        val description =
            if (autoSaveInfo.exists) {
                GameMenuHelper.getSaveStateDescription(autoSaveInfo)
            } else {
                context.getString(R.string.game_menu_state_auto_save_missing)
            }

        return listOf(
            StateEntry(
                title = context.getString(R.string.game_menu_state_auto_save),
                description = description,
                enabled = !disableMissingEntries || autoSaveInfo.exists,
                // Previews are only captured for the slots, which are saved to on purpose. Taking
                // one as the game is being torn down would be one more thing to get wrong there.
                preview = null,
                slot = null,
            ),
        )
    }
}
