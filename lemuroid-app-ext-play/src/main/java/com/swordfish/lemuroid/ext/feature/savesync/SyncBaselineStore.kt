package com.swordfish.lemuroid.ext.feature.savesync

import com.swordfish.lemuroid.common.kotlin.readTextAtomic
import com.swordfish.lemuroid.common.kotlin.writeTextAtomic
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * State a file had the last time local and remote agreed on its content.
 *
 * Both modification times are kept because they are not guaranteed to match: some filesystems
 * truncate [File.setLastModified] to whole seconds, so the local copy of a freshly downloaded file
 * can carry a slightly different timestamp than its remote counterpart. Comparing each side against
 * its own recorded value avoids reading that skew as a change.
 */
data class BaselineEntry(
    val size: Long,
    val localModifiedAt: Long,
    val remoteModifiedAt: Long,
)

/**
 * Remembers, for every synced folder, which files were in sync at the end of the last successful
 * sync.
 *
 * Comparing local and remote alone cannot tell a locally deleted file apart from a file which was
 * simply never downloaded on this device, so a two way merge can only ever add files. Diffing both
 * sides against this baseline turns it into a three way merge, which makes deletions detectable and
 * lets them propagate in both directions.
 *
 * The file lives in the internal files directory so it is never itself picked up by the sync.
 */
class SyncBaselineStore(
    private val baselineFile: File,
) {
    private var cache: MutableMap<String, Map<String, BaselineEntry>>? = null

    @Synchronized
    fun read(folderName: String): Map<String, BaselineEntry> = loadCache()[folderName] ?: emptyMap()

    @Synchronized
    fun write(
        folderName: String,
        baseline: Map<String, BaselineEntry>,
    ) {
        val folders = loadCache()
        folders[folderName] = baseline

        runCatching { baselineFile.writeTextAtomic(serialize(folders)) }
            .onFailure { Timber.e(it, "Unable to persist save sync baseline") }
    }

    private fun loadCache(): MutableMap<String, Map<String, BaselineEntry>> {
        cache?.let { return it }

        val loaded =
            runCatching {
                if (baselineFile.exists()) {
                    deserialize(baselineFile.readTextAtomic())
                } else {
                    mutableMapOf()
                }
            }.getOrElse {
                // A missing or unreadable baseline degrades the merge to the previous additive
                // behaviour, which resurrects files instead of deleting them. That is the safe
                // direction to fail in, so we just start over.
                Timber.w(it, "Unable to read save sync baseline. Starting from an empty one.")
                mutableMapOf()
            }

        cache = loaded
        return loaded
    }

    private fun serialize(folders: Map<String, Map<String, BaselineEntry>>): String {
        val root = JSONObject()
        folders.forEach { (folderName, entries) ->
            val folderJson = JSONObject()
            entries.forEach { (path, entry) ->
                folderJson.put(
                    path,
                    JSONObject()
                        .put(KEY_SIZE, entry.size)
                        .put(KEY_LOCAL_MODIFIED_AT, entry.localModifiedAt)
                        .put(KEY_REMOTE_MODIFIED_AT, entry.remoteModifiedAt),
                )
            }
            root.put(folderName, folderJson)
        }
        return root.toString()
    }

    private fun deserialize(text: String): MutableMap<String, Map<String, BaselineEntry>> {
        val root = JSONObject(text)
        val result = mutableMapOf<String, Map<String, BaselineEntry>>()

        root.keys().forEach { folderName ->
            val folderJson = root.getJSONObject(folderName)
            val entries = mutableMapOf<String, BaselineEntry>()

            folderJson.keys().forEach { path ->
                val entryJson = folderJson.getJSONObject(path)
                entries[path] =
                    BaselineEntry(
                        size = entryJson.getLong(KEY_SIZE),
                        localModifiedAt = entryJson.getLong(KEY_LOCAL_MODIFIED_AT),
                        remoteModifiedAt = entryJson.getLong(KEY_REMOTE_MODIFIED_AT),
                    )
            }

            result[folderName] = entries
        }

        return result
    }

    companion object {
        const val BASELINE_FILE_NAME = "save-sync-baseline.json"

        private const val KEY_SIZE = "size"
        private const val KEY_LOCAL_MODIFIED_AT = "localModifiedAt"
        private const val KEY_REMOTE_MODIFIED_AT = "remoteModifiedAt"
    }
}
