package com.swordfish.lemuroid.lib.savesync

import com.swordfish.lemuroid.common.kotlin.readTextAtomic
import com.swordfish.lemuroid.common.kotlin.writeTextAtomic
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Remembers which save files a sync put on this device, as opposed to ones a session played here
 * wrote.
 *
 * Nothing on disk records that difference. A save downloaded from the remote keeps the remote's
 * modification time, and a save written at the end of a session keeps the moment the session ended,
 * so the two are indistinguishable by timestamp alone. That matters because an auto-save state is
 * only ever the continuation of the save its own session wrote: pair it with a save which arrived
 * from somewhere else and the state wins, quietly undoing the copy the sync just installed. The gap
 * between the two timestamps is no help either, since a downloaded save can land arbitrarily close to
 * the local state it does not belong with.
 *
 * Entries carry the size and modification time the file had once the sync was done with it, which is
 * what makes them self expiring: the next session rewrites the save and the recorded pair stops
 * matching, so the save counts as locally written again without anything having to clear it. That
 * matters because the write which would do the clearing happens as a game is being torn down, which
 * is not a moment to depend on.
 *
 * Like the sync baseline and the conflict store this lives in the internal files directory so it is
 * never itself swept up by the sync. Unlike them it is read from the game process, which is a
 * different process from the one the sync runs in: writes only ever happen on the sync side, and the
 * game side reads the file once per launch, so the two never race for it.
 */
class SyncInstalledSavesStore(
    private val storeFile: File,
) {
    /** What a save looked like immediately after a sync wrote it. */
    data class Installed(
        val size: Long,
        val modifiedAt: Long,
    )

    private var cache: MutableMap<String, Installed>? = null

    /**
     * Whether the save at [fileName] is still the copy a sync installed.
     *
     * [size] and [modifiedAt] are the file as it is now. A recorded entry which no longer describes
     * it means a session has written since, which is the answer callers are really after.
     */
    @Synchronized
    fun isInstalled(
        fileName: String,
        size: Long,
        modifiedAt: Long,
    ): Boolean {
        val installed = loadCache()[fileName] ?: return false
        return installed.size == size && installed.modifiedAt == modifiedAt
    }

    /**
     * Records [installed] and drops [forgotten], in one write.
     *
     * Both directions are needed in the same pass: a sync which replaces a save records it, and a
     * sync which also replaced that save's auto-save state drops it again, because two copies which
     * both came from the remote belong together and the state should be resumed into as normal.
     */
    @Synchronized
    fun update(
        installed: Map<String, Installed>,
        forgotten: Set<String>,
    ) {
        if (installed.isEmpty() && forgotten.isEmpty()) return

        val entries = loadCache()
        entries.keys.removeAll(forgotten)
        entries.putAll(installed)

        runCatching { storeFile.writeTextAtomic(serialize(entries)) }
            .onFailure { Timber.e(it, "Unable to persist sync installed saves") }
    }

    private fun loadCache(): MutableMap<String, Installed> {
        cache?.let { return it }

        val loaded =
            runCatching {
                if (storeFile.exists()) {
                    deserialize(storeFile.readTextAtomic())
                } else {
                    mutableMapOf()
                }
            }.getOrElse {
                // Losing this costs nothing but the certainty: launches fall back to comparing
                // timestamps, which is what they did before this store existed.
                Timber.w(it, "Unable to read sync installed saves. Starting from an empty store.")
                mutableMapOf()
            }

        cache = loaded
        return loaded
    }

    private fun serialize(entries: Map<String, Installed>): String {
        val root = JSONObject()
        entries.forEach { (fileName, installed) ->
            root.put(
                fileName,
                JSONObject()
                    .put(KEY_SIZE, installed.size)
                    .put(KEY_MODIFIED_AT, installed.modifiedAt),
            )
        }
        return root.toString()
    }

    private fun deserialize(text: String): MutableMap<String, Installed> {
        val root = JSONObject(text)
        val result = mutableMapOf<String, Installed>()

        root.keys().forEach { fileName ->
            val entry = root.getJSONObject(fileName)
            result[fileName] =
                Installed(
                    size = entry.getLong(KEY_SIZE),
                    modifiedAt = entry.getLong(KEY_MODIFIED_AT),
                )
        }

        return result
    }

    companion object {
        const val INSTALLED_SAVES_FILE_NAME = "save-sync-installed-saves.json"

        private const val KEY_SIZE = "size"
        private const val KEY_MODIFIED_AT = "modifiedAt"
    }
}
