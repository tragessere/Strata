package com.swordfish.lemuroid.app.mobile.feature.gamemenu

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.coreoptions.GameMenuCoreOptionsScreen
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.coreoptions.GameMenuCoreOptionsViewModel
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.states.GameMenuStatesScreen
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.states.GameMenuStatesViewModel
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.shared.coreoptions.LemuroidCoreOption
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.common.kotlin.serializable
import com.swordfish.lemuroid.lib.android.RetrogradeComponentActivity
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import kotlinx.coroutines.launch
import java.security.InvalidParameterException
import javax.inject.Inject

/** Keeps a sliver of the game visible above the sheet even when every option is shown. */
private const val MAX_SHEET_HEIGHT_FRACTION = 0.85f

private const val ROUTE_ANIM_DURATION = 250

private val HEADER_HEIGHT = 56.dp
private val HEADER_HORIZONTAL_PADDING = 4.dp

/** Keeps the centered title from running underneath the "Quit" and "Resume" buttons. */
private val HEADER_TITLE_PADDING = 92.dp

class GameMenuActivity : RetrogradeComponentActivity() {
    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    @Inject
    lateinit var statesManager: StatesManager

    @Inject
    lateinit var statesPreviewManager: StatesPreviewManager

    data class GameMenuRequest(
        val coreOptions: List<LemuroidCoreOption>,
        val advancedCoreOptions: List<LemuroidCoreOption>,
        val game: Game,
        val coreConfig: SystemCoreConfig,
        val audioEnabled: Boolean,
        val fastForwardSupported: Boolean,
        val fastForwardEnabled: Boolean,
        val numDisks: Int,
        val currentDisk: Int,
        val currentTiltConfiguration: TiltConfiguration,
        val allTiltConfigurations: List<TiltConfiguration>,
        val controllerSkinActive: Boolean,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        disableActivityTransitions()

        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val extras = intent.extras

        val gameMenuRequest =
            GameMenuRequest(
                coreOptions =
                    intent
                        .serializable<Array<LemuroidCoreOption>>(GameMenuContract.EXTRA_CORE_OPTIONS)
                        ?.toList()
                        ?: throw InvalidParameterException("Missing EXTRA_CORE_OPTIONS"),
                advancedCoreOptions =
                    intent
                        .serializable<Array<LemuroidCoreOption>>(GameMenuContract.EXTRA_ADVANCED_CORE_OPTIONS)
                        ?.toList()
                        ?: throw InvalidParameterException("Missing EXTRA_ADVANCED_CORE_OPTIONS"),
                game =
                    intent.serializable<Game>(GameMenuContract.EXTRA_GAME)
                        ?: throw InvalidParameterException("Missing EXTRA_GAME"),
                coreConfig =
                    intent.serializable<SystemCoreConfig>(GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG)
                        ?: throw InvalidParameterException("Missing EXTRA_SYSTEM_CORE_CONFIG"),
                audioEnabled =
                    extras?.getBoolean(GameMenuContract.EXTRA_AUDIO_ENABLED, false) ?: false,
                fastForwardSupported =
                    extras?.getBoolean(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED, false) ?: false,
                fastForwardEnabled =
                    extras?.getBoolean(GameMenuContract.EXTRA_FAST_FORWARD, false) ?: false,
                numDisks =
                    extras?.getInt(GameMenuContract.EXTRA_DISKS, 0) ?: 0,
                currentDisk =
                    extras?.getInt(GameMenuContract.EXTRA_CURRENT_DISK, 0) ?: 0,
                currentTiltConfiguration =
                    intent.serializable<TiltConfiguration>(GameMenuContract.EXTRA_CURRENT_TILT_CONFIG)
                        ?: TiltConfiguration.Disabled,
                allTiltConfigurations =
                    intent
                        .serializable<Array<TiltConfiguration>>(GameMenuContract.EXTRA_TILT_ALL_CONFIGS)
                        ?.toList()
                        ?: emptyList(),
                controllerSkinActive =
                    extras?.getBoolean(GameMenuContract.EXTRA_CONTROLLER_SKIN_ACTIVE, false) ?: false,
            )

        setContent {
            GameMenuScreen(gameMenuRequest)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun GameMenuScreen(gameMenuRequest: GameMenuRequest) {
        AppTheme {
            val navController = rememberNavController()
            val navBackStackEntry = navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry.value?.destination

            val currentRoute =
                currentDestination
                    ?.route
                    ?.let { GameMenuRoute.findByRoute(it) }
                    ?: GameMenuRoute.HOME

            // The sheet is sized to its content and sits flush with the bottom of the screen, so
            // this only bounds how tall the content may grow before it starts scrolling.
            val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * MAX_SHEET_HEIGHT_FRACTION

            val sheetState = rememberGameMenuSheetState()
            val coroutineScope = rememberCoroutineScope()

            // Every action finishes the activity, which would cut the sheet off mid screen. Slide
            // it back down first, then deliver the result.
            val dismissWithResult: (Intent.() -> Unit) -> Unit = { block ->
                coroutineScope.launch {
                    sheetState.hide()
                    onResult(block)
                }
            }

            // Back returns to the menu from a sub screen, and closes the menu from the menu itself.
            BackHandler {
                if (currentRoute.canGoBack()) {
                    navController.popBackStack()
                } else {
                    dismissWithResult { }
                }
            }

            GameMenuSheet(
                state = sheetState,
                onDismissRequest = { dismissWithResult { } },
                sheetMaxWidth = if (isGameMenuLandscape()) Dp.Unspecified else BottomSheetDefaults.SheetMaxWidth,
            ) {
                Column(modifier = Modifier.heightIn(max = maxContentHeight)) {
                    GameMenuSheetHeader(
                        currentRoute = currentRoute,
                        onBack = { navController.popBackStack() },
                        onQuit = { dismissWithResult { putExtra(GameMenuContract.RESULT_QUIT, true) } },
                        onResume = { dismissWithResult { } },
                    )
                    if (currentRoute.canGoBack()) {
                        HorizontalDivider(modifier = Modifier.fillMaxWidth())
                    }
                    // The routes have very different heights, so the sheet animates between them
                    // rather than snapping to the size of whichever one just arrived.
                    NavHost(
                        modifier = Modifier.fillMaxWidth(),
                        navController = navController,
                        startDestination = GameMenuRoute.HOME.route,
                        enterTransition = { fadeIn(tween(ROUTE_ANIM_DURATION)) },
                        exitTransition = { fadeOut(tween(ROUTE_ANIM_DURATION)) },
                        sizeTransform = { SizeTransform { _, _ -> tween(ROUTE_ANIM_DURATION) } },
                    ) {
                        composable(GameMenuRoute.HOME) {
                            GameMenuHomeScreen(navController, gameMenuRequest, dismissWithResult)
                        }
                        composable(GameMenuRoute.SAVE) {
                            GameMenuStatesScreen(
                                viewModel(
                                    factory =
                                        GameMenuStatesViewModel.Factory(
                                            application,
                                            gameMenuRequest,
                                            statesManager,
                                            false,
                                            statesPreviewManager,
                                        ),
                                ),
                                onStateClicked = {
                                    dismissWithResult { putExtra(GameMenuContract.RESULT_SAVE, it) }
                                },
                            )
                        }
                        composable(GameMenuRoute.LOAD) {
                            GameMenuStatesScreen(
                                viewModel(
                                    factory =
                                        GameMenuStatesViewModel.Factory(
                                            application,
                                            gameMenuRequest,
                                            statesManager,
                                            true,
                                            statesPreviewManager,
                                        ),
                                ),
                                onStateClicked = {
                                    dismissWithResult { putExtra(GameMenuContract.RESULT_LOAD, it) }
                                },
                            )
                        }
                        composable(GameMenuRoute.OPTIONS) {
                            GameMenuCoreOptionsScreen(
                                viewModel(
                                    factory = GameMenuCoreOptionsViewModel.Factory(inputDeviceManager),
                                ),
                                gameMenuRequest,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * On the home route the header holds "Quit" on the left, the "Paused" title in the middle and
     * "Resume" on the right. Sub routes swap "Quit" for a back arrow and drop "Resume".
     */
    @Composable
    private fun GameMenuSheetHeader(
        currentRoute: GameMenuRoute,
        onBack: () -> Unit,
        onQuit: () -> Unit,
        onResume: () -> Unit,
    ) {
        AnimatedContent(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(HEADER_HEIGHT)
                    .padding(horizontal = HEADER_HORIZONTAL_PADDING),
            targetState = currentRoute,
            label = "Header",
        ) { route ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (route.canGoBack()) {
                    IconButton(
                        modifier = Modifier.align(Alignment.CenterStart),
                        onClick = onBack,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.back),
                        )
                    }
                } else {
                    TextButton(
                        modifier = Modifier.align(Alignment.CenterStart),
                        onClick = onQuit,
                    ) {
                        Text(
                            text = stringResource(R.string.game_menu_quit),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onClick = onResume,
                    ) {
                        Text(
                            text = stringResource(R.string.game_menu_resume),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Text(
                    modifier = Modifier.padding(horizontal = HEADER_TITLE_PADDING),
                    text = stringResource(route.titleId),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }

    /**
     * The theme clears the activity animations too, but skins vary in how well they honour that,
     * and any surviving fade would cross fade the sheet as it slides. The menu's only motion is
     * the sheet sliding and its own scrim fading, both drawn inside this window.
     */
    private fun disableActivityTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun onResult(block: Intent.() -> Unit) {
        val resultIntent = Intent()
        resultIntent.block()
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    @dagger.Module
    abstract class Module
}
