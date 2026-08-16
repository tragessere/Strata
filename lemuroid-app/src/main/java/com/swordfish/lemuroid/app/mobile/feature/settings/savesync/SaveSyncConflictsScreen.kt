package com.swordfish.lemuroid.app.mobile.feature.settings.savesync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidCardSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsPage

@Composable
fun SaveSyncConflictsScreen(
    modifier: Modifier = Modifier,
    viewModel: SaveSyncConflictsViewModel,
) {
    val groups = viewModel.groups.collectAsState().value
    val choices = viewModel.choices.collectAsState().value

    // A sync in flight is about to rewrite the very files these choices describe, so let it finish
    // rather than let a decision be made against a list which is already out of date.
    val isSyncInProgress = viewModel.saveSyncInProgress.collectAsState(true).value

    LemuroidSettingsPage(modifier = modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            LemuroidCardSettingsGroup {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = stringResource(id = R.string.settings_save_sync_conflicts_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@LemuroidSettingsPage
        }

        LemuroidCardSettingsGroup {
            Text(
                modifier = Modifier.padding(16.dp),
                text = stringResource(id = R.string.save_sync_conflicts_explanation),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        groups.forEach { group ->
            LemuroidCardSettingsGroup {
                SaveSyncConflictChoice(
                    title = group.displayName,
                    subtitle = saveSyncConflictKindLabel(group),
                    group = group,
                    selected = choices[group.id],
                    enabled = !isSyncInProgress,
                    onSelected = { viewModel.choose(group, it) },
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                enabled = choices.isNotEmpty() && !isSyncInProgress,
                onClick = { viewModel.applyChoices() },
            ) {
                Text(text = stringResource(id = R.string.save_sync_conflicts_apply))
            }
        }
    }
}

@Composable
fun saveSyncConflictsSubtitle(count: Int): String =
    if (count == 0) {
        stringResource(id = R.string.settings_save_sync_conflicts_none)
    } else {
        pluralStringResource(id = R.plurals.settings_save_sync_conflicts_description, count, count)
    }
