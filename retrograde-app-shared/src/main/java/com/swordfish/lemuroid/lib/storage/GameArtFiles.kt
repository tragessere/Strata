package com.swordfish.lemuroid.lib.storage

/** Helpers for the image files backing custom game artwork. */
object GameArtFiles {
    /** Image extensions we recognize as custom cover art, in priority order. */
    val SUPPORTED_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")

    fun extensionForMimeType(mimeType: String?): String =
        when (mimeType?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
}
