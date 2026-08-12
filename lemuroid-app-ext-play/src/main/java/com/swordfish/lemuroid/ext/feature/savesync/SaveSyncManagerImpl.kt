package com.swordfish.lemuroid.ext.feature.savesync

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.http.FileContent
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.swordfish.lemuroid.common.files.safeDelete
import com.swordfish.lemuroid.common.kotlin.SharedPreferencesDelegates
import com.swordfish.lemuroid.common.kotlin.calculateMd5
import com.swordfish.lemuroid.ext.R
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.saves.SaveFileNames
import com.swordfish.lemuroid.lib.savesync.ConflictResolution
import com.swordfish.lemuroid.lib.savesync.SaveSyncConflict
import com.swordfish.lemuroid.lib.savesync.SaveSyncConflictStore
import com.swordfish.lemuroid.lib.savesync.SaveSyncFolders
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import com.swordfish.lemuroid.lib.savesync.SaveSyncResult
import com.swordfish.lemuroid.lib.savesync.SyncInstalledSavesStore
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat

private typealias DriveFile = com.google.api.services.drive.model.File

class SaveSyncManagerImpl(
    private val appContext: Context,
    private val directoriesManager: DirectoriesManager,
    private val syncInstalledSaves: SyncInstalledSavesStore,
) : SaveSyncManager() {
    private var lastSyncTimestamp: Long by SharedPreferencesDelegates.LongDelegate(
        SharedPreferencesHelper.getSharedPreferences(appContext),
        appContext.getString(com.swordfish.lemuroid.lib.R.string.pref_key_last_save_sync),
        0L,
    )

    private val syncBaselineStore =
        SyncBaselineStore(File(appContext.filesDir, SyncBaselineStore.BASELINE_FILE_NAME))

    private val conflictStore =
        SaveSyncConflictStore(File(appContext.filesDir, SaveSyncConflictStore.CONFLICTS_FILE_NAME))

    override fun getProvider(): String = "Google Drive"

    override fun getSettingsActivity(): Class<out Activity> = ActivateGoogleDriveActivity::class.java

    override fun isSupported(): Boolean = true

    override fun isConfigured(): Boolean = GoogleSignIn.getLastSignedInAccount(appContext) != null

    override fun getLastSyncInfo(): String {
        val dateString =
            if (lastSyncTimestamp > 0) {
                SimpleDateFormat.getDateTimeInstance().format(lastSyncTimestamp)
            } else {
                "-"
            }
        return appContext.getString(R.string.gdrive_last_sync_completed, dateString)
    }

    override fun getConfigInfo(): String {
        val email = GoogleSignIn.getLastSignedInAccount(appContext)?.email
        return if (email != null) {
            appContext.getString(R.string.gdrive_connected_summary, email)
        } else {
            appContext.getString(R.string.gdrive_connected_none_summary)
        }
    }

    override suspend fun sync(cores: Set<CoreID>): SaveSyncResult =
        withContext(Dispatchers.IO) {
            synchronized(SYNC_LOCK) {
                runCatching {
                    performSaveSyncForCores(cores)
                }.getOrElse {
                    Timber.e(it, "Error while performing save sync.")
                    SaveSyncResult()
                }
            }
        }

    private fun performSaveSyncForCores(cores: Set<CoreID>): SaveSyncResult {
        val startedAt = System.currentTimeMillis()
        val drive = DriveFactory(appContext).create() ?: return SaveSyncResult()

        // One listing of the whole space serves every folder below. Looking each folder up and then
        // listing its contents separately cost two round trips per folder before a single byte moved,
        // which is most of what a sync with nothing to do used to spend its time on.
        val remote = fetchRemoteSnapshot(drive)

        val conflicts = mutableListOf<SaveSyncConflict>()

        val savesDirectory = directoriesManager.getSavesDirectory()
        val savesOutcome =
            syncLocalAndRemoteFolder(
                drive,
                remote,
                SAVES_FOLDER,
                savesDirectory,
                null,
            )
        conflicts += savesOutcome.conflicts
        recordInstalledSaves(savesDirectory, savesOutcome.locallyChangedPaths)

        // Custom artwork rides along with the saves. It is small, and a cover the user picked is
        // just as much theirs as a save is.
        val coversDirectory = directoriesManager.getCoversDirectory()
        val coversOutcome =
            syncLocalAndRemoteFolder(
                drive,
                remote,
                COVERS_FOLDER,
                coversDirectory,
                null,
            )
        val changedCovers =
            coversOutcome.locallyChangedPaths
                .map { File(coversDirectory, it) }
                .toSet()
        conflicts += coversOutcome.conflicts

        if (cores.isNotEmpty()) {
            val corePrefixes = cores.map { it.coreName }.toSet()

            val statesOutcome =
                syncLocalAndRemoteFolder(
                    drive,
                    remote,
                    STATES_FOLDER,
                    directoriesManager.getStatesDirectory(),
                    corePrefixes,
                )
            conflicts += statesOutcome.conflicts
            forgetSavesPairedWithStates(statesOutcome.locallyChangedPaths)

            conflicts +=
                syncLocalAndRemoteFolder(
                    drive,
                    remote,
                    STATE_PREVIEWS_FOLDER,
                    directoriesManager.getStatesPreviewDirectory(),
                    corePrefixes,
                ).conflicts
        }

        lastSyncTimestamp = System.currentTimeMillis()
        Timber.i("Save sync took ${lastSyncTimestamp - startedAt}ms, ${conflicts.size} conflicts pending")
        return SaveSyncResult(changedCovers, conflicts)
    }

    /**
     * Notes the saves this sync replaced with the remote copy, so that a launch can tell them apart
     * from a save the last session wrote here. See [SyncInstalledSavesStore] for why the difference
     * cannot be read off the files themselves.
     *
     * Recorded after the write rather than from what was downloaded, since the size and time the file
     * ends up with on disk is what a launch will be comparing against.
     */
    private fun recordInstalledSaves(
        savesDirectory: File,
        changedPaths: Set<String>,
    ) {
        if (changedPaths.isEmpty()) return

        val installed = mutableMapOf<String, SyncInstalledSavesStore.Installed>()
        val forgotten = mutableSetOf<String>()

        changedPaths.forEach { relativePath ->
            val localFile = File(savesDirectory, relativePath)

            // A path is also reported here when the sync removed it to follow a remote deletion, and a
            // save which is gone has no origin left worth remembering.
            if (localFile.isFile) {
                installed[relativePath] =
                    SyncInstalledSavesStore.Installed(localFile.length(), localFile.lastModified())
            } else {
                forgotten += relativePath
            }
        }

        syncInstalledSaves.update(installed, forgotten)
    }

    /**
     * Drops the notes made by [recordInstalledSaves] for saves whose auto-save this same sync also
     * replaced.
     *
     * Both copies then came from the remote, which makes the state the continuation of the save after
     * all, and a launch should resume into it exactly as it would have before any of this.
     */
    private fun forgetSavesPairedWithStates(changedPaths: Set<String>) {
        val forgotten =
            changedPaths
                .mapNotNull { relativePath ->
                    // States are stored per core, so the file name sits behind a core directory.
                    val fileName = relativePath.substringAfterLast('/')
                    SaveFileNames.romFileNameForAutoSaveState(fileName)?.let { SaveFileNames.saveRam(it) }
                }.toSet()

        syncInstalledSaves.update(emptyMap(), forgotten)
    }

    override fun pendingConflicts(): StateFlow<List<SaveSyncConflict>> = conflictStore.observeConflicts()

    override suspend fun requestConflictResolutions(resolutions: Map<String, ConflictResolution>) =
        withContext(Dispatchers.IO) {
            conflictStore.requestResolutions(resolutions)
        }

    override fun computeSavesSpace() = getSizeHumanReadable(directoriesManager.getSavesDirectory())

    override fun computeStatesSpace(core: CoreID) =
        getSizeHumanReadable(File(directoriesManager.getStatesDirectory(), core.coreName))

    private fun getSizeHumanReadable(directory: File): String {
        val size =
            directory
                .walkBottomUp()
                .fold(0L) { acc, file -> acc + file.length() }
        return android.text.format.Formatter
            .formatShortFileSize(appContext, size)
    }

    /** What syncing one folder changed locally, and what it could not decide on its own. */
    private data class FolderSyncOutcome(
        /** Relative paths whose local copy this sync created, replaced or removed. */
        val locallyChangedPaths: Set<String>,
        val conflicts: List<SaveSyncConflict>,
    )

    private fun syncLocalAndRemoteFolder(
        drive: Drive,
        remote: RemoteSnapshot,
        folderName: String,
        localFolder: File,
        prefixes: Set<String>?,
    ): FolderSyncOutcome {
        val previousBaseline = syncBaselineStore.read(folderName)
        val previousConflicts = conflictStore.readConflicts(folderName)
        val previousResolutions = conflictStore.readResolutions(folderName)

        val remoteFolder =
            remote.folders[folderName] ?: run {
                // A folder we have synced before cannot simply be absent. Reading a missing listing
                // as "the remote is empty" would hand every file in it to the deletion path, so leave
                // the whole folder untouched and wait for a sync which can see it again.
                if (previousBaseline.isNotEmpty()) {
                    Timber.e("Remote folder $folderName is missing. Skipping it rather than syncing against nothing.")
                    return FolderSyncOutcome(emptySet(), previousConflicts.values.toList())
                }
                RemoteFolder(createAppDataFolder(drive, folderName), emptyMap())
            }

        val remoteFilesMap = remoteFolder.filesByPath
        val localFilesMap = buildLocalFileMap(localFolder)
        val processedKeys = getFilteredKeys(remoteFilesMap.keys + localFilesMap.keys, prefixes)

        // Paths outside the current prefix filter were not looked at, so what the stores remember
        // about them has to be carried over untouched rather than dropped. Everything processed is
        // recomputed from scratch below, which is also what prunes conflicts that are no longer real.
        val updatedBaseline = previousBaseline.filterKeys { it !in processedKeys }.toMutableMap()
        val updatedConflicts = previousConflicts.filterKeys { it !in processedKeys }.toMutableMap()
        val locallyChangedPaths = mutableSetOf<String>()
        // Reported rather than rewritten, so a choice made while this sync was running survives.
        val consumedResolutions = mutableSetOf<String>()

        processedKeys.forEach { relativePath ->
            val baseline = previousBaseline[relativePath]

            val outcome =
                handleFileSync(
                    drive = drive,
                    remoteParentFolderId = remoteFolder.uploadFolderId,
                    localParentFolder = localFolder,
                    folderName = folderName,
                    relativePath = relativePath,
                    remote = remoteEntryFor(relativePath, remoteFilesMap[relativePath], baseline),
                    previous =
                        PathSyncState(
                            baseline = baseline,
                            conflict = previousConflicts[relativePath],
                            resolution = previousResolutions[relativePath],
                        ),
                )

            if (outcome.baseline != null) {
                updatedBaseline[relativePath] = outcome.baseline
            }

            if (outcome.conflict != null) {
                updatedConflicts[relativePath] = outcome.conflict
            }

            // Anything not handed back was either carried out or found to no longer apply.
            if (previousResolutions[relativePath] != null && outcome.resolution == null) {
                consumedResolutions.add(relativePath)
            }

            if (outcome.localChanged) {
                locallyChangedPaths.add(relativePath)
            }
        }

        syncBaselineStore.write(folderName, updatedBaseline)
        conflictStore.writeFolder(folderName, updatedConflicts, consumedResolutions)

        return FolderSyncOutcome(locallyChangedPaths, updatedConflicts.values.toList())
    }

    private fun getFilteredKeys(
        keys: Set<String>,
        prefixes: Set<String>?,
    ): Set<String> {
        if (prefixes == null) return keys
        return keys.filter { key -> prefixes.any { key.startsWith(it) } }.toSet()
    }

    /** The remote side of one path: the copy to sync against, and any other copies of it. */
    private data class RemoteEntry(
        val file: DriveFile,
        /**
         * Copies of the same path sitting in another folder of the same name. They are never read or
         * written, only trashed alongside [file] when a deletion is propagated, since a survivor
         * would look like a file this device had never had and be downloaded straight back.
         */
        val duplicates: List<DriveFile>,
    )

    /**
     * The remote side of a path, or null when the remote does not hold it at all.
     *
     * With duplicate folders the same path can exist more than once, and the copy the sync works
     * against has to be settled before anything else can be said about it. A copy the baseline
     * already agrees with wins, which pins the choice: were the winner free to change between syncs,
     * each side would take its turn looking like the one which had moved on, and the two would
     * overwrite each other indefinitely. Failing that the most recently modified copy wins, as the
     * one most likely to hold what was last played, with the id settling ties so that every device
     * arrives at the same answer.
     */
    private fun remoteEntryFor(
        relativePath: String,
        copies: List<DriveFile>?,
        baseline: BaselineEntry?,
    ): RemoteEntry? {
        if (copies.isNullOrEmpty()) return null

        if (copies.size == 1) {
            return RemoteEntry(copies.first(), emptyList())
        }

        Timber.w("Found ${copies.size} remote copies of $relativePath. Syncing against one of them.")

        val ordered = copies.sortedBy { it.id }
        val file =
            baseline?.let { entry -> ordered.firstOrNull { entry.matchesRemote(it) } }
                ?: ordered.maxWith(compareBy<DriveFile> { it.modifiedTime.value }.thenBy { it.id })

        return RemoteEntry(file, ordered.filter { it.id != file.id })
    }

    /** What the stores remembered about a path before this sync looked at it. */
    private data class PathSyncState(
        val baseline: BaselineEntry?,
        val conflict: SaveSyncConflict?,
        val resolution: ConflictResolution?,
    )

    /**
     * The baseline entry a path should carry from now on (null when it is gone on both sides), plus
     * whether the local copy was touched, which callers use to invalidate anything derived from it.
     *
     * [conflict] and [resolution] work the same way: whatever is returned here is what the stores
     * will remember, so leaving them null is how a conflict gets cleared.
     */
    private data class FileSyncOutcome(
        val baseline: BaselineEntry?,
        val localChanged: Boolean = false,
        val conflict: SaveSyncConflict? = null,
        val resolution: ConflictResolution? = null,
    )

    /** Leaves both copies and everything remembered about the path exactly as they were. */
    private fun unchanged(previous: PathSyncState) =
        FileSyncOutcome(previous.baseline, conflict = previous.conflict, resolution = previous.resolution)

    /**
     * Decides what to do with a single path by comparing both sides against the state they had at
     * the end of the last successful sync.
     */
    private fun handleFileSync(
        drive: Drive,
        remoteParentFolderId: String,
        localParentFolder: File,
        folderName: String,
        relativePath: String,
        remote: RemoteEntry?,
        previous: PathSyncState,
    ): FileSyncOutcome {
        val remoteFile = remote?.file
        val localFile = File(localParentFolder, relativePath)
        val localExists = localFile.isFile

        Timber.i("Handling file pair: $relativePath local=$localExists remote=${remoteFile?.id}")

        // An empty file is either a write which was interrupted half way through or the inert zero
        // byte remote which downloadToLocal refuses to fetch. Never let one of those be read as a
        // deletion, or an interrupted write would take the last remaining copy with it.
        if (localExists && localFile.length() == 0L) return unchanged(previous)
        if (remoteFile != null && remoteSize(remoteFile) == 0L) return unchanged(previous)

        return runCatching {
            val resolved =
                applyPendingResolution(
                    drive = drive,
                    localFile = localFile,
                    remoteFile = remoteFile,
                    previous = previous,
                )

            resolved ?: when {
                remote != null && localExists ->
                    syncExistingPair(drive, remote.file, localFile, folderName, relativePath, previous)
                remote != null -> syncRemoteOnly(drive, remote, localFile, previous.baseline)
                localExists ->
                    syncLocalOnly(drive, remoteParentFolderId, localParentFolder, localFile, previous.baseline)
                else -> FileSyncOutcome(null)
            }
        }.getOrElse {
            Timber.e(it, "Error while syncing $relativePath")
            // Keep everything the path had before, so a failed operation is never recorded as a
            // success and a decision the user already made is not silently thrown away.
            unchanged(previous)
        }
    }

    /**
     * Carries out a choice the user made earlier, or returns null to let the normal merge run.
     *
     * The decision is only honoured while both sides still look the way they did when it was
     * presented. If either has moved on the choice no longer describes what the user was asked
     * about, so it is dropped and the divergence is worked out again from the current state.
     */
    private fun applyPendingResolution(
        drive: Drive,
        localFile: File,
        remoteFile: DriveFile?,
        previous: PathSyncState,
    ): FileSyncOutcome? {
        val resolution = previous.resolution ?: return null
        val conflict = previous.conflict ?: return null

        // Every resolution weighs one copy against the other, so a side which has gone missing
        // entirely is enough on its own to make the decision no longer meaningful.
        val stillMatches =
            remoteFile != null &&
                localFile.isFile &&
                localFile.length() == conflict.localSize &&
                localFile.lastModified() == conflict.localModifiedAt &&
                remoteSize(remoteFile) == conflict.remoteSize &&
                remoteFile.modifiedTime.value == conflict.remoteModifiedAt

        if (!stillMatches) {
            Timber.i("Discarding stale $resolution for ${conflict.relativePath}. Re-detecting.")
            return null
        }

        Timber.i("Applying $resolution for ${conflict.relativePath}")

        return when (resolution) {
            ConflictResolution.KEEP_LOCAL -> {
                val updated = onLocalUpdated(localFile, drive, remoteFile)
                FileSyncOutcome(entryFor(localFile, updated.modifiedTime.value))
            }

            ConflictResolution.KEEP_REMOTE -> {
                onRemoteUpdated(drive, remoteFile, localFile)
                FileSyncOutcome(entryFor(localFile, remoteFile.modifiedTime.value), localChanged = true)
            }
        }
    }

    /**
     * Present on both sides. Whichever side moved since the last agreement is the one with something
     * to say; if they both did, only the user can settle it.
     */
    private fun syncExistingPair(
        drive: Drive,
        remoteFile: DriveFile,
        localFile: File,
        folderName: String,
        relativePath: String,
        previous: PathSyncState,
    ): FileSyncOutcome {
        val baseline = previous.baseline

        if (!areFileDifferent(remoteFile, localFile)) {
            // Identical content needs no decision even when there is no baseline yet, which is what
            // keeps a device that simply enabled sync from being asked about files it already agrees
            // on.
            return FileSyncOutcome(entryFor(localFile, remoteFile.modifiedTime.value))
        }

        // With no baseline there is no agreement to measure against, so both sides count as changed.
        val localDiverged = baseline == null || !baseline.matchesLocal(localFile)
        val remoteDiverged = baseline == null || !baseline.matchesRemote(remoteFile)

        return when {
            localDiverged && remoteDiverged ->
                conflictOutcome(remoteFile, localFile, folderName, relativePath, previous)

            localDiverged -> {
                val updated = onLocalUpdated(localFile, drive, remoteFile)
                FileSyncOutcome(entryFor(localFile, updated.modifiedTime.value))
            }

            remoteDiverged -> {
                onRemoteUpdated(drive, remoteFile, localFile)
                // The local content was replaced in place, so the path stayed the same while what
                // it holds did not.
                FileSyncOutcome(entryFor(localFile, remoteFile.modifiedTime.value), localChanged = true)
            }

            // The baseline claims both sides still agree, yet the content differs. Something wrote
            // without moving a timestamp, so the baseline is the thing that is wrong. Hand it to the
            // user rather than trust either side.
            else -> conflictOutcome(remoteFile, localFile, folderName, relativePath, previous)
        }
    }

    /**
     * Parks a path instead of touching either copy. The baseline is deliberately left where it was,
     * which makes the same divergence show up again on every sync until it is resolved and costs
     * nothing but the comparison.
     */
    private fun conflictOutcome(
        remoteFile: DriveFile,
        localFile: File,
        folderName: String,
        relativePath: String,
        previous: PathSyncState,
    ): FileSyncOutcome {
        val conflict =
            SaveSyncConflict(
                folder = folderName,
                relativePath = relativePath,
                localSize = localFile.length(),
                localModifiedAt = localFile.lastModified(),
                remoteSize = remoteSize(remoteFile),
                remoteModifiedAt = remoteFile.modifiedTime.value,
                detectedAt = System.currentTimeMillis(),
            )

        // Re-detecting the same divergence keeps the original timestamp, so "in conflict since" does
        // not creep forward on every sync.
        val previousConflict = previous.conflict
        val isSameConflict =
            previousConflict != null &&
                previousConflict.localSize == conflict.localSize &&
                previousConflict.localModifiedAt == conflict.localModifiedAt &&
                previousConflict.remoteSize == conflict.remoteSize &&
                previousConflict.remoteModifiedAt == conflict.remoteModifiedAt

        Timber.i("Conflict on $folderName/$relativePath (new=${!isSameConflict})")

        return FileSyncOutcome(
            baseline = previous.baseline,
            conflict = if (isSameConflict) previousConflict else conflict,
        )
    }

    /**
     * Only on the remote. Without a baseline this is a file this device has never seen, but if the
     * baseline knows it and the remote still matches it, the local copy was deleted on purpose.
     */
    private fun syncRemoteOnly(
        drive: Drive,
        remote: RemoteEntry,
        localFile: File,
        baseline: BaselineEntry?,
    ): FileSyncOutcome {
        val remoteFile = remote.file

        // A remote which moved on since the last agreement was changed by another device. A change
        // beats a deletion: an unwanted file can be deleted again, a lost save cannot come back.
        if (baseline == null || !baseline.matchesRemote(remoteFile)) {
            localFile.parentFile?.mkdirs()
            downloadToLocal(drive, remoteFile, localFile)
            return FileSyncOutcome(entryFor(localFile, remoteFile.modifiedTime.value), localChanged = true)
        }

        Timber.i("Local deletion detected for ${remoteFile.name}. Trashing the remote copy.")
        (listOf(remoteFile) + remote.duplicates).forEach { trashRemote(drive, it) }
        return FileSyncOutcome(null)
    }

    /**
     * Only local. Without a baseline this is a newly created save, otherwise an unchanged local
     * copy means the file was deleted from another device.
     */
    private fun syncLocalOnly(
        drive: Drive,
        remoteParentFolderId: String,
        localParentFolder: File,
        localFile: File,
        baseline: BaselineEntry?,
    ): FileSyncOutcome {
        if (baseline == null || !baseline.matchesLocal(localFile)) {
            val created = onLocalOnly(remoteParentFolderId, localFile, localParentFolder, drive)
            return FileSyncOutcome(entryFor(localFile, created.modifiedTime.value))
        }

        Timber.i("Remote deletion detected for ${localFile.name}. Removing the local copy.")
        localFile.safeDelete()
        return FileSyncOutcome(null, localChanged = true)
    }

    private fun entryFor(
        localFile: File,
        remoteModifiedAt: Long,
    ): BaselineEntry? =
        if (localFile.isFile && localFile.length() > 0) {
            BaselineEntry(localFile.length(), localFile.lastModified(), remoteModifiedAt)
        } else {
            null
        }

    private fun BaselineEntry.matchesLocal(localFile: File): Boolean =
        size == localFile.length() && localModifiedAt == localFile.lastModified()

    private fun BaselineEntry.matchesRemote(remoteFile: DriveFile): Boolean =
        size == remoteSize(remoteFile) && remoteModifiedAt == remoteFile.modifiedTime.value

    private fun remoteSize(remoteFile: DriveFile): Long = remoteFile.getSize() ?: 0L

    /**
     * Trashing rather than deleting keeps the file recoverable through the Drive API for 30 days.
     * [fetchRemoteSnapshot] already filters trashed files out, so it disappears from the merge either
     * way. Note that appDataFolder contents are hidden from the Drive web UI, so this is a safety
     * net for us rather than something the user can undo themselves.
     */
    private fun trashRemote(
        drive: Drive,
        remoteFile: DriveFile,
    ) {
        val metadata = DriveFile()
        metadata.trashed = true
        drive
            .files()
            .update(remoteFile.id, metadata)
            .execute()
    }

    /**
     * Size is checked before the timestamps so that a pair which differs in length is never waved
     * through on the strength of a matching timestamp. Equal timestamps are still taken as proof of
     * equal content beyond that, which is what keeps a sync from hashing every file it looks at.
     */
    private fun areFileDifferent(
        remoteFile: DriveFile,
        localFile: File,
    ): Boolean {
        if (remoteSize(remoteFile) != localFile.length()) {
            return true
        }

        if (remoteFile.modifiedTime.value == localFile.lastModified()) {
            return false
        }

        return remoteFile.md5Checksum != localFile.calculateMd5()
    }

    private fun onLocalUpdated(
        localFile: File,
        drive: Drive,
        remoteFile: DriveFile,
    ): DriveFile {
        Timber.i("Local file updated $localFile")

        val mediaContent = FileContent(BINARY_MIME_TYPE, localFile)
        val metadata = DriveFile()
        metadata.modifiedTime = DateTime(localFile.lastModified())
        return drive
            .files()
            .update(remoteFile.id, metadata, mediaContent)
            .setFields(REMOTE_FILE_FIELDS)
            .execute()
    }

    private fun onLocalOnly(
        remoteParentFolderId: String,
        localFile: File,
        localParentFolder: File,
        drive: Drive,
    ): DriveFile {
        Timber.i("Local-only file detected $localFile")

        val metadata = DriveFile()
        metadata.parents = listOf(remoteParentFolderId)
        metadata.name = localFile.name
        metadata.appProperties =
            mapOf(
                GDRIVE_PROPERTY_LOCAL_PATH to
                    localFile.toRelativeString(
                        localParentFolder,
                    ),
            )
        metadata.modifiedTime = DateTime(localFile.lastModified())
        val mediaContent = FileContent(BINARY_MIME_TYPE, localFile)
        return drive
            .files()
            .create(metadata, mediaContent)
            .setFields(REMOTE_FILE_FIELDS)
            .execute()
    }

    private fun onRemoteUpdated(
        drive: Drive,
        remoteFile: DriveFile,
        localFile: File,
    ) {
        Timber.i("Remote file updated $remoteFile")
        downloadToLocal(drive, remoteFile, localFile)
    }

    private fun downloadToLocal(
        drive: Drive,
        remoteFile: DriveFile,
        localFile: File,
    ) {
        if (remoteSize(remoteFile) == 0L) return
        Timber.i("Downloading file to $localFile")
        localFile.outputStream().use {
            drive
                .files()
                .get(remoteFile.id)
                .executeMediaAndDownloadTo(it)
        }
        localFile.setLastModified(remoteFile.modifiedTime.value)
    }

    // Empty files are deliberately kept here. Filtering them out would make a half written save
    // indistinguishable from a missing one, which the three way merge would read as a deletion.
    private fun buildLocalFileMap(folder: File): Map<String, File> =
        folder
            .walkBottomUp()
            .filter { it.isFile }
            .map { it.toRelativeString(folder) to it }
            .toMap()

    /** Everything the remote holds, read in one pass. */
    private data class RemoteSnapshot(
        /** Synced folder name to what the remote holds under it. */
        val folders: Map<String, RemoteFolder>,
    )

    /**
     * One synced folder, which is not necessarily one Drive folder: two devices syncing for the first
     * time at once can each create a folder of the same name, and neither of them is the wrong one.
     */
    private data class RemoteFolder(
        /** Where this device creates files it uploads. */
        val uploadFolderId: String,
        /**
         * Every copy of every path held under this name, keyed by the path relative to the folder.
         * Normally one copy each, more only where duplicate folders hold the same path.
         */
        val filesByPath: Map<String, List<DriveFile>>,
    )

    /**
     * Lists the whole app data space once and sorts the result out locally. The folders and the files
     * they hold come back together, which is also why no folder id needs caching: a cached id which
     * had gone stale would produce an empty listing, and an empty listing is indistinguishable from a
     * remote where everything was deleted.
     *
     * Folders which share a name are read as one. Choosing between them instead would hide
     * everything the folders not chosen hold, and hidden is not harmless here: a file which is only
     * in one of them looks exactly like a file this device deleted, so the next sync would carry that
     * deletion out on the remote. Which folder came out on top could also change from one sync to the
     * next, since it depended on what each of them held at the time.
     */
    private fun fetchRemoteSnapshot(drive: Drive): RemoteSnapshot {
        val folderIdsByName = mutableMapOf<String, MutableList<String>>()
        val filesByFolderId = mutableMapOf<String, MutableList<Pair<String, DriveFile>>>()

        var pageToken: String? = null
        do {
            val result =
                drive
                    .files()
                    .list()
                    .setPageSize(1000)
                    .setSpaces(APP_DATA_SPACE)
                    .setQ(SNAPSHOT_QUERY)
                    .setFields(SNAPSHOT_FIELDS)
                    .setPageToken(pageToken)
                    .execute()

            result.files.forEach { file ->
                if (file.mimeType == FOLDER_MIME_TYPE) {
                    folderIdsByName.getOrPut(file.name) { mutableListOf() }.add(file.id)
                    return@forEach
                }

                val localPath = file.appProperties?.get(GDRIVE_PROPERTY_LOCAL_PATH) ?: return@forEach
                val parentId = file.parents?.firstOrNull() ?: return@forEach
                filesByFolderId.getOrPut(parentId) { mutableListOf() }.add(localPath to file)
            }

            pageToken = result.nextPageToken
        } while (pageToken != null)

        val folders =
            folderIdsByName.mapValues { (folderName, ids) ->
                val sortedIds = ids.sorted()

                if (sortedIds.size > 1) {
                    Timber.w("Found ${sortedIds.size} remote folders named $folderName. Reading them as one.")
                }

                RemoteFolder(
                    // Lowest id rather than, say, the fullest folder, so that this answer does not
                    // depend on what the folders hold and every device keeps uploading into the same
                    // one for as long as the duplicates exist.
                    uploadFolderId = sortedIds.first(),
                    filesByPath =
                        sortedIds
                            .flatMap { filesByFolderId[it] ?: emptyList() }
                            .groupBy({ it.first }, { it.second }),
                )
            }

        return RemoteSnapshot(folders)
    }

    private fun createAppDataFolder(
        drive: Drive,
        folderName: String,
    ): String {
        Timber.i("Creating remote folder $folderName")

        val metadata = DriveFile()
        metadata.parents = listOf(APP_DATA_SPACE)
        metadata.name = folderName
        metadata.mimeType = FOLDER_MIME_TYPE

        return drive
            .files()
            .create(metadata)
            .setFields("id")
            .execute()
            .id
    }

    companion object {
        const val GDRIVE_PROPERTY_LOCAL_PATH = "localPath"

        private const val SAVES_FOLDER = SaveSyncFolders.SAVES
        private const val COVERS_FOLDER = SaveSyncFolders.COVERS
        private const val STATES_FOLDER = SaveSyncFolders.STATES
        private const val STATE_PREVIEWS_FOLDER = SaveSyncFolders.STATE_PREVIEWS

        /** Requested on writes so the baseline can record the timestamp Drive actually stored. */
        private const val REMOTE_FILE_FIELDS = "id, size, modifiedTime"

        private const val APP_DATA_SPACE = "appDataFolder"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val BINARY_MIME_TYPE = "application/x-binary"

        /** Folders and saved files in one query, so the space can be read in a single pass. */
        private const val SNAPSHOT_QUERY =
            "trashed = false and " +
                "(mimeType = '$FOLDER_MIME_TYPE' or mimeType = '$BINARY_MIME_TYPE')"

        private const val SNAPSHOT_FIELDS =
            "nextPageToken, " +
                "files(id, name, mimeType, size, appProperties, modifiedTime, parents, md5Checksum)"

        private val SYNC_LOCK = Object()
    }
}
