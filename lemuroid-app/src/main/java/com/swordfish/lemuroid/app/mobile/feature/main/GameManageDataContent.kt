package com.swordfish.lemuroid.app.mobile.feature.main

import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidDelayedLoading
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.GameFilesManager.GameDataType

private val HEADER_HEIGHT = 56.dp
private val PLACEHOLDER_HEIGHT = 96.dp

@Composable
fun GameManageDataContent(
    game: Game,
    sizes: Map<GameDataType, Long>?,
    loadingHeight: Dp,
    onDeleteData: (Game, Set<GameDataType>) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<GameDataType>()) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // Sizes are normally ready before this page is shown. When they are not, the page holds the
    // height of the one it replaced and grows into the entries once they arrive, so the sheet
    // animates a single time instead of snapping down and back up.
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        ManageDataHeader(onBack = onBack)

        val currentSizes = sizes
        when {
            currentSizes == null ->
                LemuroidDelayedLoading(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height((loadingHeight - HEADER_HEIGHT).coerceAtLeast(PLACEHOLDER_HEIGHT)),
                )
            currentSizes.isEmpty() -> ManageDataEmpty()
            else ->
                ManageDataEntries(
                    sizes = currentSizes,
                    selected = selected,
                    onToggle = { type ->
                        selected =
                            if (type in selected) {
                                selected - type
                            } else {
                                selected + type
                            }
                    },
                    onDeleteClick = { confirmingDelete = true },
                )
        }
    }

    if (confirmingDelete) {
        val selectedBytes = sizes?.filterKeys { it in selected }?.values?.sum() ?: 0L
        DeleteConfirmationDialog(
            selectedBytes = selectedBytes,
            onConfirm = {
                confirmingDelete = false
                onDeleteData(game, selected)
                onDismiss()
            },
            onCancel = { confirmingDelete = false },
        )
    }
}

@Composable
private fun ManageDataHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(id = R.string.game_manage_data_back),
            )
        }
        Text(
            text = stringResource(id = R.string.game_manage_data_title),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ManageDataEmpty() {
    Box(
        modifier = Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT).padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(id = R.string.game_manage_data_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManageDataEntries(
    sizes: Map<GameDataType, Long>,
    selected: Set<GameDataType>,
    onToggle: (GameDataType) -> Unit,
    onDeleteClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Iterating over the enum rather than the map keeps the entries in a stable order.
        GameDataType.entries.forEach { type ->
            val size = sizes[type] ?: return@forEach
            ManageDataEntry(
                type = type,
                sizeBytes = size,
                checked = type in selected,
                onToggle = { onToggle(type) },
            )
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onDeleteClick,
                enabled = selected.isNotEmpty(),
            ) {
                Text(
                    text = stringResource(id = R.string.game_manage_data_delete),
                    color =
                        if (selected.isNotEmpty()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

@Composable
private fun ManageDataEntry(
    type: GameDataType,
    sizeBytes: Long,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val label = stringResource(id = type.labelResId())

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.padding(start = 16.dp),
            imageVector = type.icon(),
            contentDescription = label,
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
        ) {
            Text(text = label)
            Text(
                text = Formatter.formatFileSize(LocalContext.current, sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(
            modifier = Modifier.padding(end = 8.dp),
            checked = checked,
            onCheckedChange = { onToggle() },
        )
    }
}

@Composable
private fun DeleteConfirmationDialog(
    selectedBytes: Long,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = stringResource(id = R.string.game_manage_data_confirm_title)) },
        text = {
            Text(
                text =
                    stringResource(
                        id = R.string.game_manage_data_confirm_message,
                        Formatter.formatFileSize(LocalContext.current, selectedBytes),
                    ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(id = R.string.game_manage_data_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(id = R.string.cancel))
            }
        },
    )
}

private fun GameDataType.labelResId(): Int =
    when (this) {
        GameDataType.ARTWORK -> R.string.game_manage_data_artwork
        GameDataType.SAVES -> R.string.game_manage_data_saves
        GameDataType.STATES -> R.string.game_manage_data_states
        GameDataType.EXTRACTED_ROM -> R.string.game_manage_data_extracted_rom
    }

private fun GameDataType.icon(): ImageVector =
    when (this) {
        GameDataType.ARTWORK -> Icons.Default.Image
        GameDataType.SAVES -> Icons.Default.Save
        GameDataType.STATES -> Icons.Default.Bookmarks
        GameDataType.EXTRACTED_ROM -> Icons.Default.FolderZip
    }
