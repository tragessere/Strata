package com.swordfish.lemuroid.app.mobile.feature.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncConflictGroup
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncConflictGrouping
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.savesync.ConflictResolution
import com.swordfish.lemuroid.lib.savesync.GameSaveSyncConflicts
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID

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

    /** How a sync turned out, for when there is no longer a dialog on screen to show it in. */
    enum class Notice {
        RESOLVED,
        UNRESOLVED,
    }

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

    private val noticeFlow = MutableStateFlow<Notice?>(null)

    /**
     * The outcome of a sync which finished with nobody watching, because the dialog was closed while
     * it ran. Held until it is consumed rather than emitted and forgotten, so a user who was away
     * from the app still finds out how it went when they come back.
     */
    val pendingNotice: StateFlow<Notice?> = noticeFlow.asStateFlow()

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

    fun consumeNotice() {
        noticeFlow.value = null
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

            val syncId =
                withContext(Dispatchers.IO) {
                    runCatching { SaveSyncWork.enqueueManualWorkAndGetId(application.applicationContext) }
                        .getOrElse {
                            // Nothing to wait for, so fall through to the check below, which will
                            // find the conflicts untouched and say so.
                            Timber.e(it, "Unable to start the sync for the chosen resolutions")
                            null
                        }
                }

            syncId?.let { awaitSyncToFinish(it) }

            val stillPending =
                saveSyncManager
                    .pendingConflicts()
                    .value
                    .map { it.id }
                    .toSet()

            // Either the sync could not run or one of the copies moved again while it was being asked
            // about, which makes a recorded choice stale and drops it.
            val isResolved = resolutions.keys.none { it in stillPending }

            // The dialog may have been closed while this ran, or moved on to another game. The
            // choices were applied all the same, so the outcome is left as a notice instead of being
            // dropped. The game is deliberately not started: waiting for it was given up on, and
            // opening one unasked minutes later would be worse than saying nothing.
            if (stateFlow.value?.launch != current.launch) {
                noticeFlow.value = if (isResolved) Notice.RESOLVED else Notice.UNRESOLVED
                return@launch
            }

            if (isResolved) {
                stateFlow.value = null
                approvedFlow.value = current.launch
            } else {
                // Say so rather than start the game as though the answer had been applied.
                stateFlow.value = stateFlow.value?.copy(stage = Stage.UNRESOLVED)
            }
        }
    }

    /**
     * Waits for the sync started for these choices to finish.
     *
     * It follows that one run by its id rather than watching for a sync to be running and then not.
     * The latter only works while this sync is still going when the wait begins, and a sync with
     * nothing it can do — no account, syncing switched off — is over in milliseconds. Waiting for
     * that to start is waiting for something which has already happened, and it would hold the
     * dialog here until the timeout for no reason.
     *
     * Losing sight of the run counts as finished, which is what a run replaced by a later sync looks
     * like. Whether the choices were carried out is never decided here: that is settled afterwards
     * by asking whether the conflicts are still standing.
     *
     * Only this sync is waited for. Anything else the queue is busy with belongs to whoever starts
     * the game, and it already holds a cleared launch back until the queue is idle.
     */
    private suspend fun awaitSyncToFinish(syncId: UUID) {
        withTimeoutOrNull(SYNC_WAIT_TIMEOUT_MS) {
            WorkManager
                .getInstance(application.applicationContext)
                .getWorkInfosForUniqueWorkFlow(SaveSyncWork.UNIQUE_WORK_ID)
                .first { infos ->
                    val info = infos.firstOrNull { it.id == syncId }
                    info == null || info.state.isFinished
                }
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
