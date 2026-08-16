package com.swordfish.lemuroid.app.mobile.feature.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderDelete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameTexts
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidSmallGameImage
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.GameFilesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainGameContextActions(
    selectedGameState: MutableState<Game?>,
    shortcutSupported: Boolean,
    onGamePlay: (Game) -> Unit,
    onGameRestart: (Game) -> Unit,
    onFavoriteToggle: (Game, Boolean) -> Unit,
    onCreateShortcut: (Game) -> Unit,
    onChangeArtwork: (Game) -> Unit,
    onImportSave: (Game) -> Unit,
    loadDataSizes: suspend (Game) -> Map<GameFilesManager.GameDataType, Long>,
    onDeleteData: (Game, Set<GameFilesManager.GameDataType>) -> Unit,
) {
    val modalSheetState = rememberModalBottomSheetState(true)
    val selectedGame = selectedGameState.value

    LaunchedEffect(selectedGame) {
        if (selectedGame != null) {
            modalSheetState.show()
        } else {
            modalSheetState.hide()
        }
    }

    if (selectedGame != null) {
        // Measuring starts when the sheet opens rather than when the data page is reached, so the
        // sizes are usually ready by then and the sheet doesn't have to resize a second time.
        val dataSizes by produceState<Map<GameFilesManager.GameDataType, Long>?>(null, selectedGame) {
            value = loadDataSizes(selectedGame)
        }

        ModalBottomSheet(
            sheetState = modalSheetState,
            onDismissRequest = { selectedGameState.value = null },
        ) {
            // Reset every time the sheet is opened, since it leaves composition when dismissed.
            var page by remember { mutableStateOf(ContextPage.ACTIONS) }

            // Lets the data page hold the sheet at its current size while sizes are still loading.
            var actionsHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current

            BackHandler(enabled = page != ContextPage.ACTIONS) {
                page = ContextPage.ACTIONS
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom)),
            ) {
                // Shared by every page, so the game being acted on stays visible.
                ContextActionHeader(game = selectedGame)
                HorizontalDivider()

                // The pages have very different heights, so the swap animates the sheet between
                // them instead of letting it snap to the new size.
                AnimatedContent(
                    targetState = page,
                    transitionSpec = { contextPageTransition() },
                    label = "gameContextPage",
                ) { currentPage ->
                    when (currentPage) {
                        ContextPage.ACTIONS ->
                            ContextActionContent(
                                modifier =
                                    Modifier.onSizeChanged {
                                        actionsHeight = with(density) { it.height.toDp() }
                                    },
                                selectedGame = selectedGame,
                                onGamePlay = onGamePlay,
                                selectedGameState = selectedGameState,
                                onGameRestart = onGameRestart,
                                onFavoriteToggle = onFavoriteToggle,
                                shortcutSupported = shortcutSupported,
                                onCreateShortcut = onCreateShortcut,
                                onChangeArtwork = onChangeArtwork,
                                onImportSave = onImportSave,
                                onManageData = { page = ContextPage.MANAGE_DATA },
                            )
                        ContextPage.MANAGE_DATA ->
                            GameManageDataContent(
                                game = selectedGame,
                                sizes = dataSizes,
                                loadingHeight = actionsHeight,
                                onDeleteData = onDeleteData,
                                onBack = { page = ContextPage.ACTIONS },
                                onDismiss = { selectedGameState.value = null },
                            )
                    }
                }
            }
        }
    }
}

private const val PAGE_ANIM_DURATION = 250

private enum class ContextPage {
    ACTIONS,
    MANAGE_DATA,
}

private fun AnimatedContentTransitionScope<ContextPage>.contextPageTransition(): ContentTransform {
    val spec = tween<Float>(PAGE_ANIM_DURATION)
    val forward = targetState.ordinal > initialState.ordinal
    val slide = { width: Int -> if (forward) width / 5 else -width / 5 }

    return (
        slideInHorizontally(tween(PAGE_ANIM_DURATION)) { slide(it) } + fadeIn(spec)
    ) togetherWith (
        slideOutHorizontally(tween(PAGE_ANIM_DURATION)) { -slide(it) } + fadeOut(spec)
    ) using SizeTransform { _, _ -> tween(PAGE_ANIM_DURATION) }
}

@Composable
private fun ContextActionContent(
    modifier: Modifier = Modifier,
    selectedGame: Game,
    onGamePlay: (Game) -> Unit,
    selectedGameState: MutableState<Game?>,
    onGameRestart: (Game) -> Unit,
    onFavoriteToggle: (Game, Boolean) -> Unit,
    shortcutSupported: Boolean,
    onCreateShortcut: (Game) -> Unit,
    onChangeArtwork: (Game) -> Unit,
    onImportSave: (Game) -> Unit,
    onManageData: () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ContextActionEntry(
            label = stringResource(id = R.string.game_context_menu_resume),
            icon = Icons.Default.PlayArrow,
            onClick = {
                onGamePlay(selectedGame)
                selectedGameState.value = null
            },
        )
        ContextActionEntry(
            label = stringResource(id = R.string.game_context_menu_restart),
            icon = Icons.Default.RestartAlt,
            onClick = {
                onGameRestart(selectedGame)
                selectedGameState.value = null
            },
        )

        if (selectedGame.isFavorite) {
            ContextActionEntry(
                label = stringResource(id = R.string.game_context_menu_remove_from_favorites),
                icon = Icons.Default.FavoriteBorder,
                onClick = {
                    onFavoriteToggle(selectedGame, false)
                    selectedGameState.value = null
                },
            )
        } else {
            ContextActionEntry(
                label = stringResource(id = R.string.game_context_menu_add_to_favorites),
                icon = Icons.Default.Favorite,
                onClick = {
                    onFavoriteToggle(selectedGame, true)
                    selectedGameState.value = null
                },
            )
        }

        ContextActionEntry(
            label = stringResource(id = R.string.game_context_menu_change_artwork),
            icon = Icons.Default.Image,
            onClick = {
                onChangeArtwork(selectedGame)
                selectedGameState.value = null
            },
        )

        ContextActionEntry(
            label = stringResource(id = R.string.game_context_menu_import_save),
            icon = Icons.Default.SaveAlt,
            onClick = {
                onImportSave(selectedGame)
                selectedGameState.value = null
            },
        )

        if (shortcutSupported) {
            ContextActionEntry(
                label = stringResource(id = R.string.game_context_menu_create_shortcut),
                icon = Icons.Default.AppShortcut,
                onClick = {
                    onCreateShortcut(selectedGame)
                    selectedGameState.value = null
                },
            )
        }

        ContextActionEntry(
            label = stringResource(id = R.string.game_context_menu_manage_data),
            icon = Icons.Default.FolderDelete,
            onClick = onManageData,
        )
    }
}

@Composable
private fun ContextActionHeader(game: Game) {
    Row(
        modifier =
            Modifier.padding(
                start = 16.dp,
                top = 8.dp,
                bottom = 8.dp,
                end = 16.dp,
            ),
    ) {
        LemuroidSmallGameImage(
            modifier =
                Modifier
                    .width(40.dp)
                    .height(40.dp)
                    .align(Alignment.CenterVertically),
            game = game,
        )
        LemuroidGameTexts(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            game = game,
        )
    }
}

@Composable
private fun ContextActionEntry(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.padding(start = 16.dp),
            imageVector = icon,
            contentDescription = label,
        )
        Text(
            modifier = Modifier.padding(start = 16.dp),
            text = label,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FakeScrim(modalSheetState: SheetState) {
    AnimatedVisibility(
        visible = modalSheetState.targetValue != SheetValue.Hidden,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BottomSheetDefaults.ScrimColor),
        )
    }
}
