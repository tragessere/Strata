package com.swordfish.lemuroid.lib.saves

import com.swordfish.lemuroid.common.kotlin.readTextAtomic
import com.swordfish.lemuroid.common.kotlin.writeTextAtomic
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Remembers when the session currently playing a game began.
 *
 * An auto-save state is written as the game is being put into the background, which is the least
 * reliable moment there is: the emulator is being torn down, the process is a candidate for being
 * killed from the moment it stops being visible, and the write can simply not happen. Nothing on
 * disk records that it did not, so the next launch finds an auto-save which looks perfectly valid
 * and resumes into it, dropping the player back somewhere in an earlier session with no indication
 * that anything went wrong. That is worse than not resuming at all, because a state which quietly
 * rewinds past an in game save looks like the game lost the save.
 *
 * A session start recorded before the auto-save could possibly be written turns the absent write
 * into something observable: an auto-save older than the session which was supposed to replace it
 * did not survive that session, so it no longer describes where the player is and the game should
 * boot from its save file instead. The record needs no clearing, since a successful write leaves an
 * auto-save newer than it, which is exactly the condition for resuming.
 *
 * Only one game is played at a time in this process, so only the most recent session is kept.
 * It lives in the internal files directory, alongside the other stores the save sync must not
 * carry off, and is written and read from the game process only.
 */
class GameSessionStore(
    private val storeFile: File,
) {
    /**
     * When the session playing [game] on [coreID] started, or null when the last recorded session
     * was for something else.
     */
    suspend fun getSessionStart(
        game: Game,
        coreID: CoreID,
    ): Long? =
        withContext(Dispatchers.IO) {
            val session = read() ?: return@withContext null
            if (session.key == keyOf(game, coreID)) session.startedAt else null
        }

    /**
     * Records that a session playing [game] on [coreID] is starting now.
     *
     * Called at the two moments the auto-save on disk stops describing where the player is: the
     * launch itself, and every return from the background after one, since play resumes from there
     * and the state which was written on the way out is immediately behind again.
     */
    suspend fun markSessionStarted(
        game: Game,
        coreID: CoreID,
    ) = withContext(Dispatchers.IO) {
        write(Session(keyOf(game, coreID), System.currentTimeMillis()))
    }

    private data class Session(
        val key: String,
        val startedAt: Long,
    )

    private fun read(): Session? =
        runCatching {
            if (!storeFile.isFile) return@runCatching null
            val root = JSONObject(storeFile.readTextAtomic())
            Session(root.getString(KEY_GAME), root.getLong(KEY_STARTED_AT))
        }.getOrElse {
            // Losing this only costs the extra certainty: launches fall back to comparing the
            // auto-save against the save file, which is what they did before this store existed.
            Timber.w(it, "Unable to read the current game session. Ignoring it.")
            null
        }

    private fun write(session: Session) {
        runCatching {
            storeFile.writeTextAtomic(
                JSONObject()
                    .put(KEY_GAME, session.key)
                    .put(KEY_STARTED_AT, session.startedAt)
                    .toString(),
            )
        }.onFailure { Timber.e(it, "Unable to record the current game session") }
    }

    private fun keyOf(
        game: Game,
        coreID: CoreID,
    ) = "${coreID.coreName}/${SaveFileNames.autoSaveState(game)}"

    companion object {
        const val SESSION_FILE_NAME = "game-session.json"

        private const val KEY_GAME = "game"
        private const val KEY_STARTED_AT = "startedAt"
    }
}
