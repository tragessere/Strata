package com.swordfish.lemuroid.app.mobile.feature.main

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.home.HomeScreen
import com.swordfish.lemuroid.app.mobile.feature.home.HomeViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.advanced.AdvancedSettingsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.advanced.AdvancedSettingsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.bios.BiosScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.bios.BiosSettingsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.coreselection.CoresSelectionScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.coreselection.CoresSelectionViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.general.SettingsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.general.SettingsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.inputdevices.InputDevicesSettingsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.inputdevices.InputDevicesSettingsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncConflictsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncConflictsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncSettingsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.savesync.SaveSyncSettingsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.skins.ControllerSkinsScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.skins.ControllerSkinsViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.skins.SkinOrientationScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.skins.SkinOrientationViewModel
import com.swordfish.lemuroid.app.mobile.feature.settings.skins.SystemSkinScreen
import com.swordfish.lemuroid.app.mobile.feature.settings.skins.SystemSkinViewModel
import com.swordfish.lemuroid.app.mobile.feature.shortcuts.ShortcutsGenerator
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.GameLauncher
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.main.BusyActivity
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.app.shared.settings.SettingsInteractor
import com.swordfish.lemuroid.common.coroutines.safeLaunch
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.ext.feature.review.ReviewManager
import com.swordfish.lemuroid.lib.android.RetrogradeComponentActivity
import com.swordfish.lemuroid.lib.bios.BiosManager
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.injection.PerActivity
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.skin.ControllerSkinPreferences
import com.swordfish.lemuroid.lib.library.skin.DeltaSkinManager
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.storage.GameFilesManager
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import dagger.Provides
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TABLET_SMALLEST_WIDTH_DP = 600
private const val NAV_ANIM_DURATION = 350

@OptIn(DelicateCoroutinesApi::class)
class MainActivity :
    RetrogradeComponentActivity(),
    BusyActivity {
    @Inject
    lateinit var gameLaunchTaskHandler: GameLaunchTaskHandler

    @Inject
    lateinit var saveSyncManager: SaveSyncManager

    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameInteractor: GameInteractor

    @Inject
    lateinit var gameFilesManager: GameFilesManager

    @Inject
    lateinit var biosManager: BiosManager

    @Inject
    lateinit var coresSelection: CoresSelection

    @Inject
    lateinit var settingsInteractor: SettingsInteractor

    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    @Inject
    lateinit var deltaSkinManager: DeltaSkinManager

    @Inject
    lateinit var controllerSkinPreferences: ControllerSkinPreferences

    private val reviewManager = ReviewManager()

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(applicationContext, saveSyncManager)
    }

    private val gameLaunchConflictViewModel: GameLaunchConflictViewModel by viewModels {
        GameLaunchConflictViewModel.Factory(application, saveSyncManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        GlobalScope.safeLaunch {
            reviewManager.initialize(applicationContext)
        }

        setContent {
            val navController = rememberNavController()
            MainScreen(navController)
        }
    }

    private fun isTabletDevice(): Boolean = resources.configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen(navController: NavHostController) {
        AppTheme {
            val navBackStackEntry = navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry.value?.destination
            val currentRoute =
                currentDestination
                    ?.route
                    ?.let { MainRoute.findByRoute(it) }
                    ?: MainRoute.HOME

            LaunchedEffect(currentRoute) {
                mainViewModel.changeRoute(currentRoute)
            }

            // The skin picker routes show a context-specific toolbar title rather than a static one:
            // the selected system name on the system screen, and the orientation name on the orientation screen.
            val topBarTitleOverride: String? =
                when (currentRoute) {
                    MainRoute.SETTINGS_CONTROLLER_SKIN_SYSTEM -> {
                        val systemId = navBackStackEntry.value?.arguments?.getString(ARG_SYSTEM_ID)
                        systemId?.let { stringResource(GameSystem.findById(it).shortTitleResId) }
                    }

                    MainRoute.SETTINGS_CONTROLLER_SKIN_ORIENTATION -> {
                        val orientation = navBackStackEntry.value?.arguments?.getString(ARG_ORIENTATION)
                        if (orientation == TouchControllerSettingsManager.Orientation.LANDSCAPE.name) {
                            stringResource(R.string.controller_skins_landscape)
                        } else {
                            stringResource(R.string.controller_skins_portrait)
                        }
                    }

                    else -> {
                        null
                    }
                }

            val selectedGameState =
                remember {
                    mutableStateOf<Game?>(null)
                }

            val onGameLongClick = { game: Game ->
                selectedGameState.value = game
            }

            val artworkTargetGameState =
                remember {
                    mutableStateOf<Game?>(null)
                }

            val pickArtworkLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { imageUri ->
                    val targetGame = artworkTargetGameState.value
                    if (imageUri != null && targetGame != null) {
                        gameInteractor.onSetCustomArtwork(targetGame, imageUri)
                    }
                    artworkTargetGameState.value = null
                }

            val onChangeArtwork = { game: Game ->
                artworkTargetGameState.value = game
                pickArtworkLauncher.launch("image/*")
            }

            // Both go through the conflict gate, which takes over whenever the game has a save the
            // user has not yet chosen a copy for. It answers false when there is nothing to ask,
            // which is every launch that does not involve a conflict.
            //
            // A sync already running comes first, though. It is in the middle of moving the very
            // copies the question is about, so any list the dialog could show is one edit behind, and
            // an answer given against it would be applied to files which have since changed. Falling
            // straight through to the interactor lets it turn the launch away with the usual "wait
            // for the pending operations to complete" toast, and the question is asked on the next
            // attempt, once the sync is done.
            val onGamePlay = { game: Game ->
                if (isBusy() || !gameLaunchConflictViewModel.interceptLaunch(game, loadSave = true)) {
                    gameInteractor.onGamePlay(game)
                }
            }

            val onGameRestart = { game: Game ->
                if (isBusy() || !gameLaunchConflictViewModel.interceptLaunch(game, loadSave = false)) {
                    gameInteractor.onGameRestart(game)
                }
            }

            val onGameFavoriteToggle = { game: Game, isFavorite: Boolean ->
                gameInteractor.onFavoriteToggle(game, isFavorite)
            }

            val mainUIState =
                mainViewModel.state
                    .collectAsState(MainViewModel.UiState())
                    .value

            val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

            Scaffold(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                topBar = {
                    MainTopBar(
                        currentRoute = currentRoute,
                        titleOverride = topBarTitleOverride,
                        navController = navController,
                        mainUIState = mainUIState,
                        scrollBehavior = scrollBehavior,
                        onUpdateQueryString = { mainViewModel.changeQueryString(it) },
                        onSetSearchActive = { mainViewModel.setSearchActive(it) },
                    )
                },
            ) { padding ->
                NavHost(
                    modifier = Modifier.fillMaxSize(),
                    navController = navController,
                    startDestination = MainRoute.HOME.route,
                    // Settings pages slide in from the right (and out to the right on back).
                    // The pop transitions double as the seekable predictive-back animation.
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Start,
                            tween(NAV_ANIM_DURATION),
                        ) +
                            fadeIn(tween(NAV_ANIM_DURATION))
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Start,
                            tween(NAV_ANIM_DURATION),
                        ) +
                            fadeOut(tween(NAV_ANIM_DURATION))
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.End,
                            tween(NAV_ANIM_DURATION),
                        ) +
                            fadeIn(tween(NAV_ANIM_DURATION))
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.End,
                            tween(NAV_ANIM_DURATION),
                        ) +
                            fadeOut(tween(NAV_ANIM_DURATION))
                    },
                ) {
                    composable(MainRoute.HOME) {
                        HomeScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        HomeViewModel.Factory(
                                            applicationContext,
                                            retrogradeDb,
                                            coresSelection,
                                        ),
                                ),
                            searchQuery = mainUIState.searchQuery,
                            onGameClick = onGamePlay,
                            onGameLongClick = onGameLongClick,
                            onOpenCoreSelection = { navController.navigateToRoute(MainRoute.SETTINGS_CORES_SELECTION) },
                        )
                    }
                    composable(MainRoute.SETTINGS) {
                        SettingsScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        SettingsViewModel.Factory(
                                            applicationContext,
                                            settingsInteractor,
                                            saveSyncManager,
                                            FlowSharedPreferences(
                                                SharedPreferencesHelper.getLegacySharedPreferences(
                                                    applicationContext,
                                                ),
                                            ),
                                        ),
                                ),
                            navController = navController,
                        )
                    }
                    composable(MainRoute.SETTINGS_ADVANCED) {
                        AdvancedSettingsScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        AdvancedSettingsViewModel.Factory(
                                            applicationContext,
                                            settingsInteractor,
                                        ),
                                ),
                            navController = navController,
                        )
                    }
                    composable(MainRoute.SETTINGS_BIOS) {
                        BiosScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory = BiosSettingsViewModel.Factory(biosManager),
                                ),
                        )
                    }
                    composable(MainRoute.SETTINGS_CORES_SELECTION) {
                        CoresSelectionScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        CoresSelectionViewModel.Factory(
                                            applicationContext,
                                            coresSelection,
                                        ),
                                ),
                        )
                    }
                    composable(MainRoute.SETTINGS_INPUT_DEVICES) {
                        InputDevicesSettingsScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        InputDevicesSettingsViewModel.Factory(
                                            applicationContext,
                                            inputDeviceManager,
                                        ),
                                ),
                        )
                    }
                    composable(MainRoute.SETTINGS_SAVE_SYNC) {
                        SaveSyncSettingsScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        SaveSyncSettingsViewModel.Factory(
                                            application,
                                            saveSyncManager,
                                        ),
                                ),
                            navController = navController,
                        )
                    }
                    composable(MainRoute.SETTINGS_SAVE_SYNC_CONFLICTS) {
                        SaveSyncConflictsScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        SaveSyncConflictsViewModel.Factory(
                                            application,
                                            saveSyncManager,
                                            retrogradeDb,
                                        ),
                                ),
                        )
                    }
                    composable(MainRoute.SETTINGS_CONTROLLER_SKINS) {
                        ControllerSkinsScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        ControllerSkinsViewModel.Factory(
                                            deltaSkinManager,
                                            controllerSkinPreferences,
                                        ),
                                ),
                            navController = navController,
                        )
                    }
                    composable(MainRoute.SETTINGS_CONTROLLER_SKIN_SYSTEM) { backStackEntry ->
                        val systemId = backStackEntry.arguments?.getString(ARG_SYSTEM_ID)
                        SystemSkinScreen(
                            systemId = systemId,
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        SystemSkinViewModel.Factory(
                                            deltaSkinManager,
                                            controllerSkinPreferences,
                                            systemId,
                                            isTabletDevice(),
                                        ),
                                ),
                            navController = navController,
                        )
                    }
                    composable(MainRoute.SETTINGS_CONTROLLER_SKIN_ORIENTATION) { backStackEntry ->
                        val systemId = backStackEntry.arguments?.getString(ARG_SYSTEM_ID)
                        val orientation =
                            TouchControllerSettingsManager.Orientation.valueOf(
                                backStackEntry.arguments?.getString(ARG_ORIENTATION)
                                    ?: TouchControllerSettingsManager.Orientation.PORTRAIT.name,
                            )
                        SkinOrientationScreen(
                            modifier = Modifier.padding(padding),
                            viewModel =
                                viewModel(
                                    factory =
                                        SkinOrientationViewModel.Factory(
                                            deltaSkinManager,
                                            controllerSkinPreferences,
                                            systemId,
                                            orientation,
                                            isTabletDevice(),
                                        ),
                                ),
                        )
                    }
                }
            }

            MainGameContextActions(
                selectedGameState = selectedGameState,
                shortcutSupported = gameInteractor.supportShortcuts(),
                onGamePlay = onGamePlay,
                onGameRestart = onGameRestart,
                onFavoriteToggle = { game: Game, isFavorite: Boolean ->
                    gameInteractor.onFavoriteToggle(game, isFavorite)
                },
                onCreateShortcut = { gameInteractor.onCreateShortcut(it) },
                onChangeArtwork = onChangeArtwork,
                loadDataSizes = { gameFilesManager.computeSizes(it) },
                onDeleteData = { game, types -> deleteGameData(game, types) },
            )

            GameLaunchConflictDialog(viewModel = gameLaunchConflictViewModel)

            // A launch which came through the dialog waits here for the sync it triggered to be out
            // of the way, since the emulator is about to read the very files it was writing.
            val approvedLaunch = gameLaunchConflictViewModel.approvedLaunch.collectAsState().value
            LaunchedEffect(approvedLaunch, mainUIState.operationInProgress) {
                if (approvedLaunch == null || mainUIState.operationInProgress) {
                    return@LaunchedEffect
                }
                gameLaunchConflictViewModel.consumeApprovedLaunch()
                if (approvedLaunch.loadSave) {
                    gameInteractor.onGamePlay(approvedLaunch.game)
                } else {
                    gameInteractor.onGameRestart(approvedLaunch.game)
                }
            }

            // A sync started from the dialog carries on after it is closed, and the progress bar
            // going away is the only sign it finished. This is the one thing which says how it went.
            val syncNotice = gameLaunchConflictViewModel.pendingNotice.collectAsState().value
            LaunchedEffect(syncNotice) {
                if (syncNotice == null) {
                    return@LaunchedEffect
                }
                gameLaunchConflictViewModel.consumeNotice()
                displayToast(
                    when (syncNotice) {
                        GameLaunchConflictViewModel.Notice.RESOLVED ->
                            R.string.game_launch_conflict_toast_resolved
                        GameLaunchConflictViewModel.Notice.UNRESOLVED ->
                            R.string.game_launch_conflict_toast_unresolved
                    },
                )
            }
        }
    }

    override fun activity(): Activity = this

    override fun isBusy(): Boolean = mainViewModel.state.value.operationInProgress ?: false

    private fun deleteGameData(
        game: Game,
        types: Set<GameFilesManager.GameDataType>,
    ) {
        GlobalScope.safeLaunch {
            val result = gameFilesManager.delete(game, types)

            val message =
                if (result.failedTypes.isNotEmpty()) {
                    getString(R.string.game_manage_data_delete_failed)
                } else {
                    val freed = Formatter.formatFileSize(this@MainActivity, result.freedBytes)
                    getString(R.string.game_manage_data_deleted, freed)
                }

            withContext(Dispatchers.Main) {
                displayToast(message)
            }
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            BaseGameActivity.REQUEST_PLAY_GAME -> {
                GlobalScope.safeLaunch {
                    gameLaunchTaskHandler.handleGameFinish(
                        true,
                        this@MainActivity,
                        resultCode,
                        data,
                    )
                }
            }
        }
    }

    @dagger.Module
    abstract class Module {
        @dagger.Module
        companion object {
            @Provides
            @PerActivity
            @JvmStatic
            fun settingsInteractor(
                activity: MainActivity,
                directoriesManager: DirectoriesManager,
            ) = SettingsInteractor(activity, directoriesManager)

            @Provides
            @PerActivity
            @JvmStatic
            fun gameInteractor(
                activity: MainActivity,
                retrogradeDb: RetrogradeDatabase,
                shortcutsGenerator: ShortcutsGenerator,
                gameLauncher: GameLauncher,
                lemuroidLibrary: LemuroidLibrary,
            ) = GameInteractor(activity, retrogradeDb, false, shortcutsGenerator, gameLauncher, lemuroidLibrary)
        }
    }
}
