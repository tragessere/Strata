package com.swordfish.lemuroid.app.mobile.feature.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncConflictGroup
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncConflictGrouping
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.savesync.ConflictResolution
import com.swordfish.lemuroid.lib.savesync.GameSaveSyncConflicts
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stands between a game and the emulator for as long as one of its saves is in conflict.
 *
 * Opening a game with an undecided save is how a conflict turns into a loss: the session loads
 * whichever copy happens to be on the device, writes over it, and the copy in the cloud is now the
 * only one holding what was played earlier. Asking at this point costs a tap and is the last moment
 * at which the question can still be answered cheaply.
 *
 * Nothing is ever forced. "Decide later" opens the game against the local copy exactly as before,
 * and leaves both copies and the conflict untouched.
 */
class GameLaunchConflictViewModel(
    private val application: Application,
    private val saveSyncManager: SaveSyncManager,
) : ViewModel() {
    class Factory(
        private val application: Application,
        private val saveSyncManager: SaveSyncManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GameLaunchConflictViewModel(application, saveSyncManager) as T
    }

    enum class Stage {
        /** Waiting on the user to pick a copy for each save. */
        CHOOSING,

        /** The choice has been handed to a sync which is carrying it out. */
        SYNCING,

        /** The sync ended with the conflict still standing, so the choice did not take. */
        UNRESOLVED,
    }

    data class PendingLaunch(
        val game: Game,
        /** Mirrors the flag the launcher takes, and is only carried through untouched. */
        val loadSave: Boolean,
    )

    data class State(
        val launch: PendingLaunch,
        val groups: List<SaveSyncConflictGroup>,
        /** What the user has picked so far, keyed by [SaveSyncConflictGroup.id]. */
        val choices: Map<String, ConflictResolution> = emptyMap(),
        val stage: Stage = Stage.CHOOSING,
    )

    private val stateFlow = MutableStateFlow<State?>(null)

    /** The question being asked, or null when there is nothing standing in the way of a launch. */
    val state: StateFlow<State?> = stateFlow.asStateFlow()

    private val approvedFlow = MutableStateFlow<PendingLaunch?>(null)

    /**
     * A launch which is cleared to go ahead. It is published rather than started here because
     * starting a game is the activity's business, and because the caller may still want to hold it
     * back until nothing else is writing to the save directories.
     */
    val approvedLaunch: StateFlow<PendingLaunch?> = approvedFlow.asStateFlow()

    /**
     * Returns true when this game has an unanswered conflict and the question has been raised, which
     * means the caller must not launch. False means there is nothing to ask about and the launch is
     * the caller's to make as usual.
     */
    fun interceptLaunch(
        game: Game,
        loadSave: Boolean,
    ): Boolean {
        if (!saveSyncManager.isSupported() || !saveSyncManager.isConfigured()) {
            return false
        }

        val conflicts = GameSaveSyncConflicts.forGame(saveSyncManager.pendingConflicts().value, game)
        if (conflicts.isEmpty()) {
            return false
        }

        stateFlow.value =
            State(
                launch = PendingLaunch(game, loadSave),
                // The game is already known here, so grouping is only being asked to sort the paths
                // into saves and slots rather than to work out whose they are.
                groups = SaveSyncConflictGrouping.group(conflicts, emptyList()),
            )
        return true
    }

    fun choose(
        group: SaveSyncConflictGroup,
        resolution: ConflictResolution,
    ) {
        val current = stateFlow.value ?: return
        if (current.stage != Stage.CHOOSING) return
        stateFlow.value = current.copy(choices = current.choices + (group.id to resolution))
    }

    /** Closes the question without playing, for a user who changed their mind about the game. */
    fun dismiss() {
        stateFlow.value = null
    }

    /** "Decide later": play against the local copy, leaving the conflict exactly as it was. */
    fun launchAnyway() {
        val current = stateFlow.value ?: return
        stateFlow.value = null
        approvedFlow.value = current.launch
    }

    fun consumeApprovedLaunch() {
        approvedFlow.value = null
    }

    /**
     * Records the choices, runs a sync to carry them out, and only then lets the game start. Waiting
     * is the whole point: launching while the sync is still replacing a save would hand the session a
     * file which is about to change underneath it.
     */
    fun applyChoices() {
        val current = stateFlow.value ?: return
        if (current.choices.isEmpty() || current.stage == Stage.SYNCING) return

        val groupsById = current.groups.associateBy { it.id }

        // A group is answered as a whole, so every file behind it takes the same resolution.
        val resolutions =
            current.choices
                .flatMap { (groupId, resolution) ->
                    val group = groupsById[groupId] ?: return@flatMap emptyList()
                    group.conflicts.map { it.id to resolution }
                }.toMap()

        stateFlow.value = current.copy(stage = Stage.SYNCING)

        viewModelScope.launch {
            saveSyncManager.requestConflictResolutions(resolutions)
            withContext(Dispatchers.IO) {
                SaveSyncWork.enqueueManualWork(application.applicationContext)
            }
            awaitOperationsToSettle()

            // The user may have walked away while the sync ran, in which case there is no longer a
            // launch waiting on its outcome.
            if (stateFlow.value?.launch != current.launch) {
                return@launch
            }

            val stillPending =
                saveSyncManager
                    .pendingConflicts()
                    .value
                    .map { it.id }
                    .toSet()

            if (resolutions.keys.none { it in stillPending }) {
                stateFlow.value = null
                approvedFlow.value = current.launch
            } else {
                // Either the sync could not run or one of the copies moved again while it was being
                // asked about, which makes a recorded choice stale and drops it. Say so rather than
                // start the game as though the answer had been applied.
                stateFlow.value = stateFlow.value?.copy(stage = Stage.UNRESOLVED)
            }
        }
    }

    /**
     * Waits for the sync just enqueued to start and then finish.
     *
     * Every background operation is watched rather than just the sync, since the caller will not
     * start a game while any of them is running either. Dropping the leading idle readings is what
     * keeps the wait from ending on the state the queue was in before the work was accepted.
     */
    private suspend fun awaitOperationsToSettle() {
        withTimeoutOrNull(SYNC_WAIT_TIMEOUT_MS) {
            PendingOperationsMonitor(application.applicationContext)
                .anyOperationInProgress()
                .dropWhile { !it }
                .first { !it }
        }
    }

    companion object {
        /**
         * Long enough for a sync of a handful of saves over a poor connection, and short enough that
         * a wait which is never going to end still gives the user their answer back. Running out
         * simply falls through to the conflict check below, which is the thing that actually decides.
         */
        private const val SYNC_WAIT_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
