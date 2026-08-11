package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncConflictChoice
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.saveSyncConflictKindLabel

/**
 * The conflict question, asked at the moment it matters most: with the game already on its way to
 * opening. It shows what the conflicts page shows, minus the game name, which is redundant when the
 * user has just tapped that very game.
 */
@Composable
fun GameLaunchConflictDialog(viewModel: GameLaunchConflictViewModel) {
    val state = viewModel.state.collectAsState().value ?: return
    val isSyncing = state.stage == GameLaunchConflictViewModel.Stage.SYNCING

    AlertDialog(
        // Dismissing while the sync runs would leave the game starting against a save which is
        // being replaced as it loads, so the dialog holds until that is over.
        onDismissRequest = { if (!isSyncing) viewModel.dismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = !isSyncing,
                dismissOnClickOutside = !isSyncing,
            ),
        title = { Text(text = stringResource(id = R.string.game_launch_conflict_title)) },
        text = {
            when (state.stage) {
                GameLaunchConflictViewModel.Stage.CHOOSING -> {
                    ChoosingContent(state, viewModel)
                }

                GameLaunchConflictViewModel.Stage.SYNCING -> {
                    SyncingContent()
                }

                GameLaunchConflictViewModel.Stage.UNRESOLVED -> {
                    Text(text = stringResource(id = R.string.game_launch_conflict_unresolved))
                }
            }
        },
        confirmButton = {
            when (state.stage) {
                GameLaunchConflictViewModel.Stage.CHOOSING -> {
                    TextButton(
                        enabled = state.choices.isNotEmpty(),
                        onClick = { viewModel.applyChoices() },
                    ) {
                        Text(text = stringResource(id = R.string.game_launch_conflict_apply))
                    }
                }

                GameLaunchConflictViewModel.Stage.SYNCING -> {
                    Unit
                }

                GameLaunchConflictViewModel.Stage.UNRESOLVED -> {
                    TextButton(onClick = { viewModel.launchAnyway() }) {
                        Text(text = stringResource(id = R.string.game_launch_conflict_open_anyway))
                    }
                }
            }
        },
        dismissButton = {
            when (state.stage) {
                GameLaunchConflictViewModel.Stage.CHOOSING -> {
                    TextButton(onClick = { viewModel.launchAnyway() }) {
                        Text(text = stringResource(id = R.string.game_launch_conflict_later))
                    }
                }

                else -> {
                    TextButton(onClick = { viewModel.dismiss() }) {
                        Text(text = stringResource(id = R.string.game_launch_conflict_cancel))
                    }
                }
            }
        },
    )
}

@Composable
private fun ChoosingContent(
    state: GameLaunchConflictViewModel.State,
    viewModel: GameLaunchConflictViewModel,
) {
    // A game can hold an auto save and every slot in conflict at once, which is more than a dialog
    // can show. Scrolling keeps the buttons reachable however many there are.
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(id = R.string.game_launch_conflict_message),
            style = MaterialTheme.typography.bodyMedium,
        )

        state.groups.forEach { group ->
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                thickness = DividerDefaults.Thickness,
                color = DividerDefaults.color,
            )
            SaveSyncConflictChoice(
                title = saveSyncConflictKindLabel(group),
                group = group,
                selected = state.choices[group.id],
                enabled = true,
                onSelected = { viewModel.choose(group, it) },
            )
        }
    }
}

@Composable
private fun SyncingContent() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(
            text = stringResource(id = R.string.game_launch_conflict_syncing),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
