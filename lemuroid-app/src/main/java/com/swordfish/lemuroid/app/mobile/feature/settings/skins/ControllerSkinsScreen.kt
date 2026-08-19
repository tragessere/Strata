package com.swordfish.lemuroid.app.mobile.feature.settings.skins

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.main.navigateToSystemSkin
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidCardSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsPage
import com.swordfish.lemuroid.lib.library.GameSystem

@Composable
fun ControllerSkinsScreen(
    modifier: Modifier = Modifier,
    viewModel: ControllerSkinsViewModel,
    navController: NavController,
) {
    val state = viewModel.uiState.collectAsState(ControllerSkinsViewModel.State()).value
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.importErrorEvents.collect {
            Toast.makeText(context, R.string.controller_skins_import_error, Toast.LENGTH_LONG).show()
        }
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importSkin(uri)
        }

    val defaultLabel = stringResource(R.string.controller_skins_default)

    LemuroidSettingsPage(modifier = modifier) {
        LemuroidCardSettingsGroup(
            title = { Text(text = stringResource(R.string.controller_skins_manage)) },
        ) {
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(R.string.controller_skins_import)) },
                subtitle = { Text(text = stringResource(R.string.controller_skins_import_description)) },
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            )
        }

        LemuroidCardSettingsGroup(
            title = { Text(text = stringResource(R.string.controller_skins_by_system)) },
        ) {
            state.systems.forEach { systemSkins ->
                val systemName = stringResource(GameSystem.findById(systemSkins.systemID.dbname).shortTitleResId)
                val portrait = systemSkins.portraitSkinName ?: defaultLabel
                val landscape = systemSkins.landscapeSkinName ?: defaultLabel
                LemuroidSettingsMenuLink(
                    title = { Text(text = systemName) },
                    subtitle = {
                        Text(
                            text =
                                stringResource(
                                    R.string.controller_skins_system_summary,
                                    portrait,
                                    landscape,
                                ),
                        )
                    },
                    onClick = { navController.navigateToSystemSkin(systemSkins.systemID.dbname) },
                )
            }
        }
    }
}
