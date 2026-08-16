package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.db.entity.displayTitle

/**
 * Takes a name of the user's own for a game.
 *
 * The name is only ever stored next to the game, never used to reach the rom on disk, so it is free
 * to hold anything typeable rather than being held to what a file name allows.
 *
 * Submitting an empty field is how a game is put back to the name it was indexed under, which saves
 * needing a separate entry in the menu for it.
 */
@Composable
fun GameRenameDialog(
    game: Game,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // Saved rather than merely remembered, so a rotation part way through typing does not throw the
    // entry away. TextFieldValue carries the selection along with the text, which is what keeps the
    // cursor where it was left instead of sending it back to the start.
    var name by
        rememberSaveable(game.id, stateSaver = TextFieldValue.Saver) {
            val initial = game.displayTitle
            mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val confirm = { onConfirm(name.text) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = stringResource(id = R.string.game_rename_title)) },
        text = {
            OutlinedTextField(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(text = stringResource(id = R.string.game_rename_label)) },
                supportingText = {
                    Text(text = stringResource(id = R.string.game_rename_supporting, game.title))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
            )
        },
        confirmButton = {
            TextButton(onClick = confirm) {
                Text(text = stringResource(id = R.string.game_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(id = R.string.cancel))
            }
        },
    )
}
