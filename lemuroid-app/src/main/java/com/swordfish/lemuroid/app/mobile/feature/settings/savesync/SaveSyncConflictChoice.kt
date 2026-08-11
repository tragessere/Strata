package com.swordfish.lemuroid.app.mobile.feature.settings.savesync

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.savesync.ConflictResolution
import java.text.SimpleDateFormat

/**
 * The two copies of one conflicted save, side by side with what tells them apart.
 *
 * Kept apart from any particular screen because the same question is asked in two places: on the
 * conflicts settings page, and again when a game with an unresolved save is about to be opened.
 * Wording the choice differently in the two would be a good way to have it answered differently.
 */
@Composable
fun SaveSyncConflictChoice(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    group: SaveSyncConflictGroup,
    selected: ConflictResolution?,
    enabled: Boolean,
    onSelected: (ConflictResolution) -> Unit,
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat.getDateTimeInstance() }

    fun detail(
        size: Long,
        modifiedAt: Long,
    ) = context.getString(
        R.string.save_sync_conflicts_detail,
        dateFormat.format(modifiedAt),
        Formatter.formatShortFileSize(context, size),
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp, 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(modifier = Modifier.selectableGroup()) {
            ResolutionOption(
                title = stringResource(id = R.string.save_sync_conflicts_keep_local),
                detail = detail(group.localSize, group.localModifiedAt),
                isSelected = selected == ConflictResolution.KEEP_LOCAL,
                enabled = enabled,
                onClick = { onSelected(ConflictResolution.KEEP_LOCAL) },
            )
            ResolutionOption(
                title = stringResource(id = R.string.save_sync_conflicts_keep_remote),
                detail = detail(group.remoteSize, group.remoteModifiedAt),
                isSelected = selected == ConflictResolution.KEEP_REMOTE,
                enabled = enabled,
                onClick = { onSelected(ConflictResolution.KEEP_REMOTE) },
            )
        }
    }
}

@Composable
private fun ResolutionOption(
    title: String,
    detail: String?,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    role = Role.RadioButton,
                    selected = isSelected,
                    enabled = enabled,
                    onClick = onClick,
                ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isSelected,
            enabled = enabled,
            onClick = null,
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** What kind of save a group holds, e.g. "Save data" or "Slot 2". */
@Composable
fun saveSyncConflictKindLabel(group: SaveSyncConflictGroup): String =
    when (group.kind) {
        SaveSyncConflictGroup.Kind.SAVE_DATA -> {
            stringResource(id = R.string.save_sync_conflicts_kind_save_data)
        }

        SaveSyncConflictGroup.Kind.SLOT -> {
            stringResource(id = R.string.save_sync_conflicts_kind_slot, group.slotNumber ?: 0)
        }

        SaveSyncConflictGroup.Kind.COVER -> {
            stringResource(id = R.string.save_sync_conflicts_kind_cover)
        }

        SaveSyncConflictGroup.Kind.OTHER -> {
            stringResource(id = R.string.save_sync_conflicts_kind_other)
        }
    }
