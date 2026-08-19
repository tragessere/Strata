package com.swordfish.lemuroid.app.shared.covers

import android.content.Context
import android.widget.ImageView
import coil.ImageLoader
import coil.disk.DiskCache
import coil.imageLoader
import coil.load
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.swordfish.lemuroid.common.drawable.TextDrawable
import com.swordfish.lemuroid.common.graphics.ColorUtils
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.library.db.entity.displayTitle
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

private val PARENTHESISED_SUFFIX = Regex("\\(.*\\)")

object CoverUtils {
    fun loadCover(
        game: Game,
        imageView: ImageView?,
    ) {
        if (imageView == null) return

        imageView.load(game.coverFrontUrl, imageView.context.imageLoader) {
            val fallbackDrawable = getFallbackDrawable(game)
            fallback(fallbackDrawable)
            error(fallbackDrawable)
        }
    }

    fun buildImageLoader(applicationContext: Context): ImageLoader =
        ImageLoader
            .Builder(applicationContext)
            .diskCache(
                DiskCache
                    .Builder()
                    .directory(applicationContext.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.20)
                    .build(),
            ).memoryCache {
                MemoryCache
                    .Builder(applicationContext)
                    .maxSizePercent(0.20)
                    .build()
            }.okHttpClient {
                OkHttpClient
                    .Builder()
                    .addNetworkInterceptor(ThrottleFailedThumbnailsInterceptor)
                    .build()
            }.crossfade(true)
            .interceptorDispatcher(Dispatchers.IO)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .build()

    fun getFallbackDrawable(game: Game) = TextDrawable(computeTitle(game), computeColor(game))

    fun getFallbackRemoteUrl(game: Game): String {
        val color = Integer.toHexString(computeColor(game)).substring(2)
        val title = computeTitle(game)
        return "https://fakeimg.pl/512x512/$color/fff/?font=bebas&text=$title"
    }

    // The placeholder stands in for the name on screen, so it follows a game which has been renamed
    // rather than keeping the initials of the name it was indexed under.
    private fun computeTitle(game: Game): String {
        val displayTitle = game.displayTitle

        val sanitizedName =
            displayTitle
                .replace(PARENTHESISED_SUFFIX, "")

        return sanitizedName
            .asSequence()
            .filter { it.isDigit() or it.isUpperCase() or (it == '&') }
            .take(3)
            .joinToString("")
            .ifBlank { displayTitle.first().toString() }
            .capitalize()
    }

    private fun computeColor(game: Game): Int = ColorUtils.randomColor(game.displayTitle)
}
