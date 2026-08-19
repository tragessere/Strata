package com.swordfish.lemuroid.app.mobile.feature.settings.skins

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.main.navigateToSkinOrientation
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidCardSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsPage
import com.swordfish.touchinput.deltaskin.DeltaSkinAssetLoader
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager.Orientation

@Composable
fun SystemSkinScreen(
    systemId: String?,
    modifier: Modifier = Modifier,
    viewModel: SystemSkinViewModel,
    navController: NavController,
) {
    val state = viewModel.uiState.collectAsState(SystemSkinViewModel.State()).value
    val context = LocalContext.current
    val assetLoader = remember { DeltaSkinAssetLoader(context.cacheDir) }

    LaunchedEffect(Unit) {
        viewModel.importErrorEvents.collect {
            Toast.makeText(context, R.string.controller_skins_import_error, Toast.LENGTH_LONG).show()
        }
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importSkin(uri)
        }

    var pendingDelete by remember { mutableStateOf<SystemSkinViewModel.InstalledSkin?>(null) }

    LemuroidSettingsPage(modifier = modifier) {
        OrientationCard(
            title = stringResource(R.string.controller_skins_portrait),
            selection = state.portrait,
            assetLoader = assetLoader,
            onClick = { systemId?.let { navController.navigateToSkinOrientation(it, Orientation.PORTRAIT) } },
        )
        OrientationCard(
            title = stringResource(R.string.controller_skins_landscape),
            selection = state.landscape,
            assetLoader = assetLoader,
            onClick = { systemId?.let { navController.navigateToSkinOrientation(it, Orientation.LANDSCAPE) } },
        )

        LemuroidCardSettingsGroup(
            title = { Text(text = stringResource(R.string.controller_skins_manage)) },
        ) {
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(R.string.controller_skins_import)) },
                subtitle = { Text(text = stringResource(R.string.controller_skins_import_description)) },
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            )

            HorizontalDivider()

            if (state.installedSkins.isEmpty()) {
                ListItem(
                    headlineContent = { Text(text = stringResource(R.string.controller_skins_none_installed)) },
                )
            } else {
                state.installedSkins.forEach { skin ->
                    InstalledSkinItem(skin = skin, onDelete = { pendingDelete = skin })
                }
            }
        }
    }

    pendingDelete?.let { skin ->
        DeleteSkinDialog(
            skinName = skin.name,
            onConfirm = {
                viewModel.deleteSkin(skin.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun OrientationCard(
    title: String,
    selection: SystemSkinViewModel.OrientationSelection,
    assetLoader: DeltaSkinAssetLoader,
    onClick: () -> Unit,
) {
    LemuroidCardSettingsGroup(title = { Text(text = title) }) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
        ) {
            SelectedSkinPreview(preview = selection.preview, assetLoader = assetLoader)
            ListItem(
                headlineContent = {
                    Text(text = selection.skinName ?: stringResource(R.string.controller_skins_default))
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
            )
        }
    }
}

@Composable
private fun InstalledSkinItem(
    skin: SystemSkinViewModel.InstalledSkin,
    onDelete: () -> Unit,
) {
    val orientationLabels =
        skin.orientations.map { orientation ->
            stringResource(
                when (orientation) {
                    Orientation.PORTRAIT -> R.string.controller_skins_portrait
                    Orientation.LANDSCAPE -> R.string.controller_skins_landscape
                },
            )
        }
    ListItem(
        headlineContent = { Text(text = skin.name) },
        supportingContent =
            if (orientationLabels.isNotEmpty()) {
                {
                    Text(
                        text =
                            stringResource(
                                R.string.controller_skins_orientations,
                                orientationLabels.joinToString(", "),
                            ),
                    )
                }
            } else {
                null
            },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.controller_skins_delete),
                )
            }
        },
    )
}

@Composable
private fun DeleteSkinDialog(
    skinName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.controller_skins_delete)) },
        text = { Text(text = stringResource(R.string.controller_skins_delete_confirm_message, skinName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.controller_skins_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
