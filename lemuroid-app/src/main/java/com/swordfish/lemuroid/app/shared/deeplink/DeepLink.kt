package com.swordfish.lemuroid.app.shared.deeplink

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.swordfish.lemuroid.lib.library.db.entity.Game

object DeepLink {
    fun openLeanbackUri(appContext: Context): Uri = "lemuroid://${appContext.packageName}/open-leanback".toUri()

    private fun uriForGame(
        appContext: Context,
        game: Game,
    ): Uri = "lemuroid://${appContext.packageName}/play-game/id/${game.id}".toUri()

    fun launchIntentForGame(
        appContext: Context,
        game: Game,
    ) = Intent(Intent.ACTION_VIEW, uriForGame(appContext, game))
}
