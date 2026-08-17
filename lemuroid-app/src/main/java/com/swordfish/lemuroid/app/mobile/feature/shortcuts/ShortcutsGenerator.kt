package com.swordfish.lemuroid.app.mobile.feature.shortcuts

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import com.swordfish.lemuroid.app.shared.covers.CoverUtils
import com.swordfish.lemuroid.app.shared.deeplink.DeepLink
import com.swordfish.lemuroid.common.bitmap.cropToSquare
import com.swordfish.lemuroid.common.bitmap.toBitmap
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.db.entity.displayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShortcutsGenerator(
    private val appContext: Context,
) {
    suspend fun pinShortcutForGame(game: Game) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val shortcutManager = appContext.getSystemService(ShortcutManager::class.java)!!
        val bitmap = retrieveBitmap(game)

        val shortcutInfo =
            ShortcutInfo
                .Builder(appContext, "game_${game.id}")
                .setShortLabel(game.displayTitle)
                .setLongLabel(game.displayTitle)
                .setIntent(DeepLink.launchIntentForGame(appContext, game))
                .setIcon(Icon.createWithBitmap(bitmap))
                .build()

        shortcutManager.requestPinShortcut(shortcutInfo, null)
    }

    /**
     * Covers are loaded through Coil rather than fetched directly, so artwork the user picked
     * themselves (stored locally and pointed at with a file uri) works just as well as a remote
     * libretro cover.
     */
    private suspend fun retrieveBitmap(game: Game): Bitmap =
        withContext(Dispatchers.IO) {
            val result = runCatching { loadCoverBitmap(game) }
            result.getOrNull() ?: retrieveFallbackBitmap(game)
        }

    private suspend fun loadCoverBitmap(game: Game): Bitmap? {
        val coverUrl = game.coverFrontUrl ?: return null
        val desiredIconSize = getDesiredIconSize()

        val request =
            ImageRequest
                .Builder(appContext)
                .data(coverUrl)
                .size(desiredIconSize)
                .scale(Scale.FILL)
                // Hardware bitmaps cannot be read back, which Icon.createWithBitmap needs to do.
                .allowHardware(false)
                .build()

        val drawable = appContext.imageLoader.execute(request).drawable ?: return null

        return (drawable as? BitmapDrawable)
            ?.bitmap
            ?.cropToSquare()
            ?: drawable.toBitmap(desiredIconSize, desiredIconSize)
    }

    private fun retrieveFallbackBitmap(game: Game): Bitmap {
        val desiredIconSize = getDesiredIconSize()
        return CoverUtils.getFallbackDrawable(game).toBitmap(desiredIconSize, desiredIconSize)
    }

    private fun getDesiredIconSize(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
        return am?.launcherLargeIconSize ?: 256
    }

    fun supportShortcuts(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        val shortcutManager = appContext.getSystemService(ShortcutManager::class.java)!!
        return shortcutManager.isRequestPinShortcutSupported
    }
}
