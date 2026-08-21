package com.swordfish.touchinput.deltaskin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

/**
 * Loads and rasterises Delta skin background assets (PDF or PNG) into bitmaps.
 *
 * Two caching tiers keep this fast:
 * - an in-memory [LruCache], process-wide, so an asset is decoded once per session; and
 * - a persistent PNG disk cache under [cacheDir], so the (expensive) PDF rasterisation survives across
 *   game launches. The emulator runs in its own short-lived process, so without the disk cache every
 *   launch would re-render the vector PDF from scratch.
 *
 * PDF assets are rendered with [PdfRenderer], which is not thread-safe, so all PDF work is serialised
 * behind a [Mutex].
 */
class DeltaSkinAssetLoader(
    private val cacheDir: File,
) {
    suspend fun loadBitmap(
        skinDir: File,
        assetName: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Bitmap? {
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return null

        val key = "${skinDir.name}/$assetName@${targetWidthPx}x${targetHeightPx}v$CACHE_FORMAT_VERSION"
        memoryCache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val diskFile = File(diskCacheDir(), diskFileName(key))
            if (diskFile.exists()) {
                BitmapFactory.decodeFile(diskFile.absolutePath)?.let { cached ->
                    memoryCache.put(key, cached)
                    return@withContext cached
                }
            }

            val file = File(skinDir, assetName)
            if (!file.exists()) {
                Timber.w("Delta skin asset not found: %s", file.absolutePath)
                return@withContext null
            }

            val bitmap =
                runCatching {
                    if (file.extension.equals("pdf", ignoreCase = true)) {
                        renderPdf(file, targetWidthPx, targetHeightPx)
                    } else {
                        decodePng(file, targetWidthPx, targetHeightPx)
                    }
                }.onFailure { Timber.e(it, "Failed to load skin asset %s", assetName) }
                    .getOrNull()

            bitmap?.also {
                memoryCache.put(key, it)
                writeToDisk(it, diskFile)
            }
        }
    }

    private fun diskCacheDir(): File = File(cacheDir, DISK_CACHE_SUBFOLDER).apply { mkdirs() }

    // Prefix the file with the skin's hash so every asset of a skin can be located (and evicted) together.
    private fun diskFileName(key: String): String =
        "${skinPrefix(key.substringBefore('/'))}_${key.hashCode().toUInt().toString(16)}.png"

    private fun writeToDisk(
        bitmap: Bitmap,
        target: File,
    ) {
        runCatching {
            // Write to a temp file then rename, so a crash mid-write can't leave a corrupt cache entry.
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(target)
        }.onFailure { Timber.w(it, "Failed to write skin asset to disk cache") }
    }

    private fun decodePng(
        file: File,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Bitmap? {
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        if (decoded.width == targetWidthPx && decoded.height == targetHeightPx) return decoded
        return decoded.scale(targetWidthPx, targetHeightPx).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private suspend fun renderPdf(
        file: File,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Bitmap =
        pdfMutex.withLock {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val bitmap =
                            createBitmap(targetWidthPx, targetHeightPx)
                        // transform == null scales the page to fill the destination bitmap.
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        premultiplyInPlace(bitmap)
                        bitmap
                    }
                }
            }
        }

    /**
     * Converts a freshly rendered page to premultiplied alpha, in place.
     *
     * [PdfRenderer] hands the page straight to PDFium, which writes *straight* (non premultiplied)
     * alpha into a bitmap that Android has already flagged as premultiplied. Nothing notices while
     * the colour hiding under a transparent pixel is black, which is the common case.
     *
     * Delta artwork is exported from Photoshop against a white matte (`/Matte [1 1 1]` on the soft
     * mask), so its transparent pixels carry white instead. Read as premultiplied, source over
     * becomes `white + dst * (1 - 0)`, which saturates: the transparent part of the skin paints
     * itself opaque white over whatever is behind it.
     */
    private fun premultiplyInPlace(bitmap: Bitmap) {
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        val pixels = buffer.array()

        var index = 0
        while (index < pixels.size) {
            // ARGB_8888 is laid out R, G, B, A in memory.
            val alpha = pixels[index + 3].toInt() and 0xFF
            if (alpha != OPAQUE) {
                pixels[index] = scaleByAlpha(pixels[index], alpha)
                pixels[index + 1] = scaleByAlpha(pixels[index + 1], alpha)
                pixels[index + 2] = scaleByAlpha(pixels[index + 2], alpha)
            }
            index += BYTES_PER_PIXEL
        }

        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
    }

    private fun scaleByAlpha(
        channel: Byte,
        alpha: Int,
    ): Byte = (((channel.toInt() and 0xFF) * alpha) / OPAQUE).toByte()

    companion object {
        private const val DISK_CACHE_SUBFOLDER = "skin-cache"
        private const val BYTES_PER_PIXEL = 4
        private const val OPAQUE = 255

        // Part of every cache key, so that entries rasterised by an older build are ignored rather
        // than served. Bump it whenever a change alters the pixels a given asset and size produce.
        private const val CACHE_FORMAT_VERSION = 2

        private val pdfMutex = Mutex()

        // Stable filename prefix identifying every cached asset of a skin. The trailing separator in the
        // eviction filter guards against one skin's prefix being a prefix of another's.
        private fun skinPrefix(skinDirName: String): String = skinDirName.hashCode().toUInt().toString(16)

        /** Drops every in-memory and on-disk cached asset belonging to the given skin directory. */
        suspend fun evictSkin(
            cacheDir: File,
            skinDirName: String,
        ) = withContext(Dispatchers.IO) {
            memoryCache
                .snapshot()
                .keys
                .filter { it.startsWith("$skinDirName/") }
                .forEach { memoryCache.remove(it) }
            val prefix = "${skinPrefix(skinDirName)}_"
            File(cacheDir, DISK_CACHE_SUBFOLDER)
                .listFiles { file -> file.name.startsWith(prefix) }
                ?.forEach { it.delete() }
        }

        // Process-wide cache so bitmaps survive recomposition and orientation changes.
        private val memoryCache: LruCache<String, Bitmap> =
            object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
                override fun sizeOf(
                    key: String,
                    value: Bitmap,
                ): Int = value.byteCount
            }
    }
}
