package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.library.db.entity.Game
import java.io.Serializable

/**
 * A picked save file waiting on the user's answer about the save it would replace.
 *
 * Held together as one value so it can be put into the saved instance state, which is what keeps the
 * question and the file it is about from being thrown away by a rotation.
 */
data class ImportSaveReplaceRequest(
    val game: Game,
    /**
     * The picked file, as text. Saving this goes through java serialization, which a [android.net.Uri]
     * does not support even though a bundle can carry one on its own.
     */
    val saveUri: String,
) : Serializable

/**
 * Asks before an import overwrites the save a game already has.
 *
 * The picked file is only written once this comes back confirmed, so cancelling here leaves the
 * existing save exactly as it was.
 */
@Composable
fun GameImportSaveReplaceDialog(
    gameTitle: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = stringResource(id = R.string.game_import_save_replace_title)) },
        text = {
            Text(text = stringResource(id = R.string.game_import_save_replace_message, gameTitle))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(id = R.string.game_import_save_replace_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(id = R.string.cancel))
            }
        },
    )
}
