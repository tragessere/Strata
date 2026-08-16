package com.swordfish.lemuroid.app.mobile.feature.settings.savesync

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.main.MainRoute
import com.swordfish.lemuroid.app.mobile.feature.main.navigateToRoute
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import com.swordfish.lemuroid.app.utils.android.ComposableLifecycle
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidCardSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsListMultiSelect
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsPage
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsSwitch
import com.swordfish.lemuroid.app.utils.android.settings.booleanPreferenceState
import com.swordfish.lemuroid.app.utils.android.settings.stringsSetPreferenceState

@Composable
fun SaveSyncSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SaveSyncSettingsViewModel,
    navController: NavController,
) {
    val context = LocalContext.current

    // Refresh when returning from the provider's sign in/out activity, so the linked account,
    // the used space and the enabled state of the rows below reflect the new configuration.
    ComposableLifecycle { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
    }

    val saveSyncState =
        viewModel.uiState
            .collectAsState()
            .value

    val isSyncInProgress =
        viewModel.saveSyncInProgress
            .collectAsState()
            .value

    val conflictCount =
        viewModel.pendingConflictCount
            .collectAsState()
            .value

    LemuroidSettingsPage(modifier = modifier.fillMaxSize()) {
        LemuroidCardSettingsGroup {
            LemuroidSettingsMenuLink(
                title = {
                    Text(
                        text =
                            stringResource(
                                id = R.string.settings_save_sync_configure,
                                saveSyncState.provider,
                            ),
                    )
                },
                subtitle = { Text(text = saveSyncState.configInfo) },
                enabled = !isSyncInProgress,
                onClick = { context.startActivity(Intent(context, saveSyncState.settingsActivity)) },
            )
            LemuroidSettingsSwitch(
                state = booleanPreferenceState(R.string.pref_key_save_sync_enable, default = false),
                title = { Text(text = stringResource(id = R.string.settings_save_sync_include_saves)) },
                subtitle = {
                    Text(
                        text =
                            stringResource(
                                id = R.string.settings_save_sync_include_saves_description,
                                saveSyncState.savesSpace,
                            ),
                    )
                },
                enabled = saveSyncState.isConfigured && !isSyncInProgress,
            )
            LemuroidSettingsListMultiSelect(
                state =
                    stringsSetPreferenceState(
                        stringResource(R.string.pref_key_save_sync_cores),
                        emptySet(),
                    ),
                title = { Text(text = stringResource(id = R.string.settings_save_sync_include_states)) },
                subtitle = { Text(text = stringResource(id = R.string.settings_save_sync_include_states_description)) },
                entryValues = saveSyncState.coreNames,
                entries = saveSyncState.coreVisibleNames,
                enabled = saveSyncState.isConfigured && !isSyncInProgress,
                confirmButton = stringResource(id = R.string.ok),
            )
            LemuroidSettingsSwitch(
                state = booleanPreferenceState(R.string.pref_key_save_sync_auto, default = false),
                title = { Text(text = stringResource(id = R.string.settings_save_sync_enable_auto)) },
                subtitle = { Text(text = stringResource(id = R.string.settings_save_sync_enable_auto_description)) },
                enabled = saveSyncState.isConfigured && !isSyncInProgress,
            )
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(id = R.string.settings_save_sync_refresh)) },
                subtitle = {
                    Text(
                        text =
                            stringResource(
                                id = R.string.settings_save_sync_refresh_description,
                                saveSyncState.lastSyncInfo,
                            ),
                    )
                },
                enabled = saveSyncState.isConfigured && !isSyncInProgress,
                onClick = { SaveSyncWork.enqueueManualWork(context) },
            )
            // Only worth a row when there is something to answer. Conflicts are the exception, not a
            // setting, and an always present "no conflicts" row would just be noise.
            if (conflictCount > 0) {
                LemuroidSettingsMenuLink(
                    title = { Text(text = stringResource(id = R.string.settings_save_sync_conflicts)) },
                    subtitle = { Text(text = saveSyncConflictsSubtitle(conflictCount)) },
                    enabled = !isSyncInProgress,
                    onClick = { navController.navigateToRoute(MainRoute.SETTINGS_SAVE_SYNC_CONFLICTS) },
                )
            }
        }
    }
}
