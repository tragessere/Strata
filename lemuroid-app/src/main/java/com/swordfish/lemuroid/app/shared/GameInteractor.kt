package com.swordfish.lemuroid.app.shared

import android.net.Uri
import android.provider.OpenableColumns
import coil.imageLoader
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.shortcuts.ShortcutsGenerator
import com.swordfish.lemuroid.app.shared.game.GameLauncher
import com.swordfish.lemuroid.app.shared.main.BusyActivity
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.SaveFileNames
import com.swordfish.lemuroid.lib.saves.SaveImporter
import com.swordfish.lemuroid.lib.storage.GameArtFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class GameInteractor(
    private val activity: BusyActivity,
    private val retrogradeDb: RetrogradeDatabase,
    private val useLeanback: Boolean,
    private val shortcutsGenerator: ShortcutsGenerator,
    private val gameLauncher: GameLauncher,
    private val lemuroidLibrary: LemuroidLibrary,
    private val saveImporter: SaveImporter,
) {
    fun onGamePlay(game: Game) {
        if (!ensureNotBusy()) {
            return
        }
        gameLauncher.launchGameAsync(activity.activity(), game, true, useLeanback)
    }

    fun onGameRestart(game: Game) {
        if (!ensureNotBusy()) {
            return
        }
        gameLauncher.launchGameAsync(activity.activity(), game, false, useLeanback)
    }

    fun onFavoriteToggle(
        game: Game,
        isFavorite: Boolean,
    ) {
        GlobalScope.launch {
            retrogradeDb.gameDao().update(game.copy(isFavorite = isFavorite))
        }
    }

    fun onCreateShortcut(game: Game) {
        GlobalScope.launch {
            shortcutsGenerator.pinShortcutForGame(game)
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun onSetCustomArtwork(
        game: Game,
        imageUri: Uri,
    ) {
        GlobalScope.launch {
            val context = activity.activity()
            val resolver = context.contentResolver

            val artUri =
                runCatching {
                    val extension = GameArtFiles.extensionForMimeType(resolver.getType(imageUri))
                    resolver.openInputStream(imageUri)?.use { input ->
                        lemuroidLibrary.writeGameCover(game, extension, input)
                    }
                }.getOrElse {
                    Timber.e(it, "Error while copying custom artwork")
                    null
                }

            if (artUri == null) {
                withContext(Dispatchers.Main) {
                    context.displayToast(R.string.game_interactor_custom_artwork_failed)
                }
                return@launch
            }

            retrogradeDb.gameDao().update(game.copy(coverFrontUrl = artUri.toString()))

            withContext(Dispatchers.Main) {
                // Drop any cached copy so the freshly picked image is displayed immediately.
                val imageLoader = context.imageLoader
                imageLoader.diskCache?.remove(artUri.toString())
                imageLoader.memoryCache?.clear()
            }
        }
    }

    /**
     * Installs the save at [saveUri] for [game].
     *
     * [onSaveAlreadyExists] is called, on the main thread, when the game already has a save and
     * [replaceExisting] was not set. Replacing one is destructive and cannot be taken back, so the
     * question goes to the user and this is called again with their answer.
     */
    fun onImportSave(
        game: Game,
        saveUri: Uri,
        replaceExisting: Boolean,
        onSaveAlreadyExists: () -> Unit,
    ) {
        GlobalScope.launch {
            val context = activity.activity()

            if (!withContext(Dispatchers.IO) { isSaveRamUri(saveUri) }) {
                displayToastOnMain(R.string.game_interactor_import_save_unsupported)
                return@launch
            }

            // Save files are small enough that reading one whole is cheaper than keeping the picked
            // uri readable across the question below and opening it a second time.
            val data =
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(saveUri)?.use { it.readBytes() }
                    }.getOrElse {
                        Timber.e(it, "Error while reading the save file to import")
                        null
                    }
                }

            if (data == null) {
                displayToastOnMain(R.string.game_interactor_import_save_failed)
                return@launch
            }

            when (saveImporter.importSave(game, data, replaceExisting)) {
                SaveImporter.Result.Imported ->
                    displayToastOnMain(R.string.game_interactor_import_save_succeeded)
                SaveImporter.Result.SaveAlreadyExists ->
                    withContext(Dispatchers.Main) { onSaveAlreadyExists() }
                SaveImporter.Result.Failed ->
                    displayToastOnMain(R.string.game_interactor_import_save_failed)
            }
        }
    }

    /**
     * Whether the picked file is one we can read as a save.
     *
     * The display name is what the check has to go on. Save files have no mime type of their own, so
     * the picker cannot filter them and the provider reports them as arbitrary bytes; the name is the
     * only thing which says what was picked.
     */
    private fun isSaveRamUri(saveUri: Uri): Boolean {
        val displayName =
            runCatching { queryDisplayName(saveUri) }
                .getOrElse {
                    Timber.e(it, "Error while reading the name of the save file to import")
                    null
                } ?: return false

        return SaveFileNames.isSaveRam(displayName)
    }

    private fun queryDisplayName(saveUri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)

        activity
            .activity()
            .contentResolver
            .query(saveUri, projection, null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }

        // Not every provider answers the query. A file uri, and most of the ones which do not, still
        // carry the name at the end of the path.
        return saveUri.lastPathSegment
    }

    private suspend fun displayToastOnMain(messageResId: Int) {
        withContext(Dispatchers.Main) {
            activity.activity().displayToast(messageResId)
        }
    }

    fun supportShortcuts(): Boolean = shortcutsGenerator.supportShortcuts()

    private fun ensureNotBusy(): Boolean {
        if (activity.isBusy()) {
            activity.activity().displayToast(R.string.game_interactory_busy)
            return false
        }
        return true
    }
}
