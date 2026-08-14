package com.swordfish.lemuroid.lib.savesync

import com.swordfish.lemuroid.common.kotlin.readTextAtomic
import com.swordfish.lemuroid.common.kotlin.writeTextAtomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Holds the conflicts a sync could not decide on its own, and the decisions the user has made about
 * them but which have not been carried out yet.
 *
 * Resolutions are recorded rather than applied on the spot: carrying one out means uploading or
 * downloading, so it has to happen inside the sync, under the same lock as everything else. A
 * decision made offline therefore just waits here until the next sync picks it up.
 *
 * Like the sync baseline this lives in the internal files directory, so it is never itself swept up
 * by the sync. It is only ever touched from the main process, which is where both the sync worker and
 * the UI run.
 */
class SaveSyncConflictStore(
    private val storeFile: File,
) {
    private data class FolderState(
        val conflicts: Map<String, SaveSyncConflict>,
        val resolutions: Map<String, ConflictResolution>,
    )

    private var cache: MutableMap<String, FolderState>? = null

    private val conflictsFlow = MutableStateFlow<List<SaveSyncConflict>>(emptyList())

    /**
     * Every unresolved conflict across all folders, for anything which has to display them.
     *
     * Reads the store on first call so an observer which arrives before the first sync of the session
     * still sees what is already pending. That is a small file, but it is disk access on the calling
     * thread.
     */
    @Synchronized
    fun observeConflicts(): StateFlow<List<SaveSyncConflict>> {
        loadCache()
        return conflictsFlow.asStateFlow()
    }

    @Synchronized
    fun readConflicts(folderName: String): Map<String, SaveSyncConflict> =
        loadCache()[folderName]?.conflicts ?: emptyMap()

    @Synchronized
    fun readResolutions(folderName: String): Map<String, ConflictResolution> =
        loadCache()[folderName]?.resolutions ?: emptyMap()

    /**
     * Replaces what is known about [folderName] at the end of a sync of that folder. The sync
     * recomputes a folder's conflicts from scratch every run, so a conflict which is no longer real
     * is pruned simply by not being passed back in.
     *
     * Resolutions are handled the other way round, by removing only [consumedResolutions], because a
     * sync reads them at its start and can take minutes to finish. Writing back the map it started
     * with would throw away any choice the user made in the meantime. Whatever survives is then
     * pruned to the paths which are still in conflict, so a decision can never outlive the question.
     */
    @Synchronized
    fun writeFolder(
        folderName: String,
        conflicts: Map<String, SaveSyncConflict>,
        consumedResolutions: Set<String>,
    ) {
        val folders = loadCache()
        val current = folders[folderName]?.resolutions ?: emptyMap()

        val resolutions =
            current.filterKeys { it !in consumedResolutions && it in conflicts }

        folders[folderName] = FolderState(conflicts, resolutions)
        persist(folders)
    }

    /**
     * Forgets everything held for folders outside [folderNames], which is how a folder the sync has
     * stopped visiting altogether stops being reported.
     *
     * [writeFolder] prunes a folder as it syncs it, but a folder which is skipped never reaches it:
     * unchecking every core under "sync states" leaves both state folders out of the run entirely, and
     * what they left behind would otherwise stay for good, with no sync left that could clear it.
     *
     * Called by the sync rather than by whatever changed the setting, so that the store is only ever
     * written from one place and a folder is only dropped once a run has actually gone without it.
     */
    @Synchronized
    fun retainFolders(folderNames: Set<String>) {
        val folders = loadCache()
        val stale = folders.keys.filter { it !in folderNames }
        if (stale.isEmpty()) return

        Timber.i("Dropping save sync conflicts for folders which are no longer synced: $stale")
        stale.forEach { folders.remove(it) }
        persist(folders)
    }

    /**
     * Records the user's choices, keyed by [SaveSyncConflict.id]. Ids which are not currently in
     * conflict are ignored, so a decision made against a stale list cannot act on an unrelated path.
     *
     * Callers are expected to kick off a sync afterwards, which is what actually applies these.
     */
    @Synchronized
    fun requestResolutions(resolutions: Map<String, ConflictResolution>) {
        if (resolutions.isEmpty()) return

        val folders = loadCache()
        var changed = false

        folders.keys.toList().forEach { folderName ->
            val state = folders[folderName] ?: return@forEach

            val accepted =
                state.conflicts.keys
                    .mapNotNull { path ->
                        val resolution = resolutions[SaveSyncConflict.buildId(folderName, path)]
                        resolution?.let { path to it }
                    }.toMap()

            if (accepted.isNotEmpty()) {
                folders[folderName] = state.copy(resolutions = state.resolutions + accepted)
                changed = true
            }
        }

        if (changed) {
            persist(folders)
        }
    }

    private fun persist(folders: Map<String, FolderState>) {
        runCatching { storeFile.writeTextAtomic(serialize(folders)) }
            .onFailure { Timber.e(it, "Unable to persist save sync conflicts") }

        // Published even if the write failed, since the in-memory state is what the rest of this
        // process will keep acting on either way.
        conflictsFlow.value = folders.flatMap { it.value.conflicts.values }
    }

    private fun loadCache(): MutableMap<String, FolderState> {
        cache?.let { return it }

        val loaded =
            runCatching {
                if (storeFile.exists()) {
                    deserialize(storeFile.readTextAtomic())
                } else {
                    mutableMapOf()
                }
            }.getOrElse {
                // Losing this only costs the pending decisions: the next sync re-detects the same
                // divergences from the baseline and asks again, which is the safe way to fail.
                Timber.w(it, "Unable to read save sync conflicts. Starting from an empty store.")
                mutableMapOf()
            }

        cache = loaded
        conflictsFlow.value = loaded.flatMap { it.value.conflicts.values }
        return loaded
    }

    private fun serialize(folders: Map<String, FolderState>): String {
        val root = JSONObject()

        folders.forEach { (folderName, state) ->
            val conflictsJson = JSONObject()
            state.conflicts.forEach { (path, conflict) ->
                conflictsJson.put(
                    path,
                    JSONObject()
                        .put(KEY_LOCAL_SIZE, conflict.localSize)
                        .put(KEY_LOCAL_MODIFIED_AT, conflict.localModifiedAt)
                        .put(KEY_REMOTE_SIZE, conflict.remoteSize)
                        .put(KEY_REMOTE_MODIFIED_AT, conflict.remoteModifiedAt)
                        .put(KEY_DETECTED_AT, conflict.detectedAt),
                )
            }

            val resolutionsJson = JSONObject()
            state.resolutions.forEach { (path, resolution) ->
                resolutionsJson.put(path, resolution.name)
            }

            root.put(
                folderName,
                JSONObject()
                    .put(KEY_CONFLICTS, conflictsJson)
                    .put(KEY_RESOLUTIONS, resolutionsJson),
            )
        }

        return root.toString()
    }

    private fun deserialize(text: String): MutableMap<String, FolderState> {
        val root = JSONObject(text)
        val result = mutableMapOf<String, FolderState>()

        root.keys().forEach { folderName ->
            val folderJson = root.getJSONObject(folderName)

            val conflictsJson = folderJson.optJSONObject(KEY_CONFLICTS) ?: JSONObject()
            val conflicts = mutableMapOf<String, SaveSyncConflict>()
            conflictsJson.keys().forEach { path ->
                val entry = conflictsJson.getJSONObject(path)
                conflicts[path] =
                    SaveSyncConflict(
                        folder = folderName,
                        relativePath = path,
                        localSize = entry.getLong(KEY_LOCAL_SIZE),
                        localModifiedAt = entry.getLong(KEY_LOCAL_MODIFIED_AT),
                        remoteSize = entry.getLong(KEY_REMOTE_SIZE),
                        remoteModifiedAt = entry.getLong(KEY_REMOTE_MODIFIED_AT),
                        detectedAt = entry.getLong(KEY_DETECTED_AT),
                    )
            }

            val resolutionsJson = folderJson.optJSONObject(KEY_RESOLUTIONS) ?: JSONObject()
            val resolutions = mutableMapOf<String, ConflictResolution>()
            resolutionsJson.keys().forEach { path ->
                val name = resolutionsJson.getString(path)
                // An unknown name is a resolution written by a newer version. Dropping it leaves the
                // conflict pending rather than applying something we cannot interpret.
                runCatching { ConflictResolution.valueOf(name) }
                    .onSuccess { resolutions[path] = it }
                    .onFailure { Timber.w("Ignoring unknown conflict resolution '$name'") }
            }

            result[folderName] = FolderState(conflicts, resolutions)
        }

        return result
    }

    companion object {
        const val CONFLICTS_FILE_NAME = "save-sync-conflicts.json"

        private const val KEY_CONFLICTS = "conflicts"
        private const val KEY_RESOLUTIONS = "resolutions"
        private const val KEY_LOCAL_SIZE = "localSize"
        private const val KEY_LOCAL_MODIFIED_AT = "localModifiedAt"
        private const val KEY_REMOTE_SIZE = "remoteSize"
        private const val KEY_REMOTE_MODIFIED_AT = "remoteModifiedAt"
        private const val KEY_DETECTED_AT = "detectedAt"
    }
}
