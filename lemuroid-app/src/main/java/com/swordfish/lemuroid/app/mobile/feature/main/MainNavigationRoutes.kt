package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.swordfish.lemuroid.R

fun NavGraphBuilder.composable(
    route: MainRoute,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    this.composable(route = route.route, arguments = route.arguments, content = content)
}

fun NavController.navigateToRoute(route: MainRoute) {
    this.navigate(route.route)
}

const val ARG_SYSTEM_ID = "systemId"
const val ARG_ORIENTATION = "orientation"

fun NavController.navigateToSystemSkin(systemId: String) {
    this.navigate("settings/skins/$systemId")
}

fun NavController.navigateToSkinOrientation(
    systemId: String,
    orientation: com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager.Orientation,
) {
    this.navigate("settings/skins/$systemId/${orientation.name}")
}

enum class MainRoute(
    val route: String,
    @StringRes val titleId: Int,
    val parent: MainRoute? = null,
    val arguments: List<NamedNavArgument> = emptyList(),
    val showTopLevelActions: Boolean = true,
) {
    HOME(
        route = "home",
        titleId = R.string.title_home,
    ),
    SETTINGS(
        route = "settings/home",
        titleId = R.string.title_settings,
        parent = HOME,
        showTopLevelActions = false,
    ),
    SETTINGS_ADVANCED(
        route = "settings/advanced",
        titleId = R.string.settings_title_advanced_settings,
        parent = SETTINGS,
        showTopLevelActions = false,
    ),
    SETTINGS_BIOS(
        route = "settings/bios",
        titleId = R.string.settings_title_display_bios_info,
        parent = SETTINGS,
        showTopLevelActions = false,
    ),
    SETTINGS_CORES_SELECTION(
        route = "settings/cores",
        titleId = R.string.settings_title_open_cores_selection,
        parent = SETTINGS,
        showTopLevelActions = false,
    ),
    SETTINGS_INPUT_DEVICES(
        route = "settings/inputdevices",
        titleId = R.string.settings_title_gamepad_settings,
        parent = SETTINGS,
        showTopLevelActions = false,
    ),
    SETTINGS_CONTROLLER_SKINS(
        route = "settings/skins",
        titleId = R.string.settings_title_controller_skins,
        parent = SETTINGS,
        showTopLevelActions = false,
    ),
    SETTINGS_CONTROLLER_SKIN_SYSTEM(
        route = "settings/skins/{$ARG_SYSTEM_ID}",
        titleId = R.string.settings_title_controller_skins,
        parent = SETTINGS_CONTROLLER_SKINS,
        arguments = listOf(navArgument(ARG_SYSTEM_ID) { type = NavType.StringType }),
        showTopLevelActions = false,
    ),
    SETTINGS_CONTROLLER_SKIN_ORIENTATION(
        route = "settings/skins/{$ARG_SYSTEM_ID}/{$ARG_ORIENTATION}",
        titleId = R.string.settings_title_controller_skins,
        parent = SETTINGS_CONTROLLER_SKIN_SYSTEM,
        arguments =
            listOf(
                navArgument(ARG_SYSTEM_ID) { type = NavType.StringType },
                navArgument(ARG_ORIENTATION) { type = NavType.StringType },
            ),
        showTopLevelActions = false,
    ),
    SETTINGS_SAVE_SYNC(
        route = "settings/savesync",
        titleId = R.string.settings_title_save_sync,
        parent = SETTINGS,
        showTopLevelActions = false,
    ),
    SETTINGS_SAVE_SYNC_CONFLICTS(
        route = "settings/savesync/conflicts",
        titleId = R.string.settings_title_save_sync_conflicts,
        parent = SETTINGS_SAVE_SYNC,
        showTopLevelActions = false,
    ),
    ;

    val root = root()

    private fun root(): MainRoute = parent?.root() ?: this

    companion object {
        fun findByRoute(route: String): MainRoute = values().first { it.route == route }
    }
}
