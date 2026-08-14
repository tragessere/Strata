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
 * different process from the one the sync runs in: writes only ever happen on the sync side, so the
 * two never race for it, but the reading side cannot hold on to what it read either. That process
 * outlives a single game, so a copy kept for its lifetime would stop describing the file as soon as
 * the next sync installed anything, and every launch after that would be back to comparing
 * timestamps. It is re-read whenever the file itself has moved on, which is a stat per launch.
 */
class SyncInstalledSavesStore(
    private val storeFile: File,
    /**
     * Where the saves the entries describe live. Resolved on each use rather than held, since it is
     * only needed on the writing side and creating it is the directory manager's business.
     */
    private val savesDirectory: () -> File,
) {
    /** What a save looked like immediately after a sync wrote it. */
    data class Installed(
        val size: Long,
        val modifiedAt: Long,
    )

    /** Size and modification time of the file a cache was built from, or null when there was none. */
    private data class FileStamp(
        val size: Long,
        val modifiedAt: Long,
    )

    private var cache: MutableMap<String, Installed>? = null
    private var cacheStamp: FileStamp? = null

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
        dropExpiredEntries(entries)

        runCatching { storeFile.writeTextAtomic(serialize(entries)) }
            .onFailure { Timber.e(it, "Unable to persist sync installed saves") }

        // Taken after the write, so what we just put there is not read straight back in. A failed
        // write leaves the old stamp, which is what makes the next read notice and reload.
        cacheStamp = readStamp()
    }

    /**
     * Forgets entries which have stopped describing their file, which is every entry whose save has
     * been played since, plus any whose save is gone.
     *
     * An entry expires by ceasing to match rather than by being cleared, so nothing else ever removes
     * one and the store would keep a line for every save a sync has ever installed. One of these
     * already answers [isInstalled] with false, so dropping it costs nothing and is done here, on the
     * writing side, where the file is being rewritten anyway.
     */
    private fun dropExpiredEntries(entries: MutableMap<String, Installed>) {
        val directory = runCatching { savesDirectory() }.getOrNull() ?: return

        val expired =
            entries
                .filterNot { (fileName, installed) ->
                    val file = File(directory, fileName)
                    file.isFile &&
                        file.length() == installed.size &&
                        file.lastModified() == installed.modifiedAt
                }.keys

        if (expired.isEmpty()) return

        Timber.i("Dropping ${expired.size} sync installed saves which no longer match their file")
        entries.keys.removeAll(expired)
    }

    private fun loadCache(): MutableMap<String, Installed> {
        val stamp = readStamp()

        cache?.let { cached ->
            if (stamp == cacheStamp) {
                return cached
            }
            Timber.i("Sync installed saves changed underneath us. Reading them again.")
        }

        val loaded =
            runCatching {
                if (stamp != null) {
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
        cacheStamp = stamp
        return loaded
    }

    /**
     * What the file looks like from the outside, which is all the sync side gives the game side to
     * notice a change by. Size is read alongside the modification time because a filesystem which
     * keeps only whole seconds could otherwise hide two writes made within the same one.
     */
    private fun readStamp(): FileStamp? =
        if (storeFile.isFile) {
            FileStamp(storeFile.length(), storeFile.lastModified())
        } else {
            null
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
