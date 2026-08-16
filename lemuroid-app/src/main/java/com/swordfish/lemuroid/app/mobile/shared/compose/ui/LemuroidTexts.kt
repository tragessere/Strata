package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.app.utils.games.GameUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.db.entity.displayTitle

@Composable
fun LemuroidGameTexts(
    modifier: Modifier = Modifier,
    game: Game,
) {
    val context = LocalContext.current
    val subtitle =
        remember(game.id) {
            GameUtils.getGameSubtitle(context, game)
        }

    LemuroidTexts(modifier, game.displayTitle, subtitle)
}

@Composable
fun LemuroidGameGridTexts(
    modifier: Modifier = Modifier,
    game: Game,
) {
    Column(
        modifier = modifier.padding(8.dp),
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = game.displayTitle,
            style = MaterialTheme.typography.titleSmall,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun LemuroidTexts(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier.padding(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
