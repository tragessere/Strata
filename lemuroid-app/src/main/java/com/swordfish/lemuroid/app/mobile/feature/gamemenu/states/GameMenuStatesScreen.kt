package com.swordfish.lemuroid.app.mobile.feature.gamemenu.states

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidDelayedLoading
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink
import com.swordfish.lemuroid.lib.saves.StatesManager

/** Material's two line list item height, pinned so the screen can be sized before it has loaded. */
private val STATE_ROW_HEIGHT = 72.dp

private val PREVIEW_SIZE = 48.dp

private const val CONTENT_FADE_DURATION = 150

/**
 * There is always one row per save slot, and every row is the same height, so the screen occupies
 * the same space whether or not the slots have been read yet. That lets the sheet animate to its
 * final height as the screen slides in, instead of resizing again once the previews arrive.
 */
private val CONTENT_HEIGHT = STATE_ROW_HEIGHT * StatesManager.MAX_STATES

@Composable
fun GameMenuStatesScreen(
    viewModel: GameMenuStatesViewModel,
    onStateClicked: (Int) -> Unit,
) {
    val state by viewModel.uiStates.collectAsState(initial = null)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                // Only ever needed at font scales large enough to push a row past its usual height.
                .animateContentSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Crossfade(
            targetState = state?.entries,
            animationSpec = tween(CONTENT_FADE_DURATION),
            label = "gameMenuStates",
        ) { entries ->
            if (entries == null) {
                LemuroidDelayedLoading(
                    modifier = Modifier.fillMaxWidth().height(CONTENT_HEIGHT),
                )
            } else {
                // A minimum rather than a fixed height, so a row that needs more room for its text
                // grows instead of clipping it. At normal font scales it never has to.
                Column(modifier = Modifier.fillMaxWidth().heightIn(min = CONTENT_HEIGHT)) {
                    entries.forEachIndexed { index, entry ->
                        LemuroidSettingsMenuLink(
                            modifier = Modifier.heightIn(min = STATE_ROW_HEIGHT),
                            title = { Text(text = entry.title) },
                            subtitle = { Text(text = entry.description) },
                            enabled = entry.enabled,
                            icon = {
                                if (entry.preview != null) {
                                    Image(
                                        modifier = Modifier.size(PREVIEW_SIZE),
                                        bitmap = entry.preview.asImageBitmap(),
                                        contentScale = ContentScale.Crop,
                                        contentDescription = null,
                                    )
                                }
                            },
                            onClick = { onStateClicked(index) },
                        )
                    }
                }
            }
        }
    }
}
