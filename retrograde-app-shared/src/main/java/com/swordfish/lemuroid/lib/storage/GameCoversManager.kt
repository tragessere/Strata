package com.swordfish.lemuroid.lib.storage

import android.net.Uri
import androidx.core.net.toUri
import com.swordfish.lemuroid.common.files.safeDelete
import com.swordfish.lemuroid.lib.library.db.entity.Game
import java.io.File
import java.io.InputStream

/**
 * Custom cover images the user picked for their games.
 *
 * They are kept in the app's own storage, laid out as `covers/<system>/<game name>.<extension>`,
 * rather than next to the game file. That keeps the rom folder free of files Lemuroid created, and
 * means writing artwork never needs write access to the folder the user granted.
 *
 * Games are keyed on their system and file name instead of their database id, so artwork survives
 * the library being rebuilt from scratch.
 */
class GameCoversManager(
    private val directoriesManager: DirectoriesManager,
) {
    fun getCoverUri(game: Game): Uri? = getCoverUri(game.systemId, game.fileName)

    fun getCoverUri(
        systemId: String,
        fileName: String,
    ): Uri? =
        coverFiles(systemId, fileName)
            .firstOrNull { it.isFile }
            ?.toUri()

    fun getCoverSize(game: Game): Long =
        coverFiles(game.systemId, game.fileName)
            .firstOrNull { it.isFile }
            ?.length() ?: 0L

    fun writeCover(
        game: Game,
        imageExtension: String,
        inputStream: InputStream,
    ): Uri? {
        val directory = coverDirectory(game.systemId).apply { mkdirs() }

        // Remove any pre-existing artwork so a single image (with the chosen extension) remains.
        deleteCover(game)

        val target = File(directory, "${coverBaseName(game.fileName)}.$imageExtension")
        inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return target.toUri()
    }

    /**
     * Every cover currently stored, keyed by [coverKey].
     *
     * Reading the whole folder in one go lets callers which need to look at many games avoid a
     * filesystem lookup per game.
     */
    fun listCovers(): Map<String, Uri> {
        val systemDirectories =
            directoriesManager
                .getCoversDirectory()
                .listFiles()
                ?.filter { it.isDirectory } ?: return emptyMap()

        val covers = mutableMapOf<String, File>()

        systemDirectories.forEach { systemDirectory ->
            val files = systemDirectory.listFiles() ?: return@forEach

            files
                .filter { it.isFile && extensionPriority(it) >= 0 }
                .forEach { file ->
                    val key = coverKey(systemDirectory.name, file.name)
                    val current = covers[key]

                    // Only one image per game is ever written, but were several to exist the same
                    // one getCoverUri would pick has to win here too.
                    if (current == null || extensionPriority(file) < extensionPriority(current)) {
                        covers[key] = file
                    }
                }
        }

        return covers.mapValues { (_, file) -> file.toUri() }
    }

    fun coverKey(game: Game) = coverKey(game.systemId, game.fileName)

    /**
     * Whether [coverFrontUrl] points at artwork stored here, as opposed to a remote cover url or
     * nothing at all.
     */
    fun isStoredCover(coverFrontUrl: String?): Boolean {
        val path =
            coverFrontUrl
                ?.toUri()
                ?.takeIf { it.scheme == "file" }
                ?.path ?: return false

        return File(path).startsWith(directoriesManager.getCoversDirectory())
    }

    /** Returns false only if artwork is present and could not be removed. */
    fun deleteCover(game: Game): Boolean =
        coverFiles(game.systemId, game.fileName)
            .filter { it.isFile }
            .map { it.safeDelete() }
            .all { it }

    /** Every path artwork for the given game could be sitting at, whether present or not. */
    private fun coverFiles(
        systemId: String,
        fileName: String,
    ): List<File> {
        val directory = coverDirectory(systemId)
        val baseName = coverBaseName(fileName)
        return GameArtFiles.SUPPORTED_EXTENSIONS.map { File(directory, "$baseName.$it") }
    }

    private fun coverDirectory(systemId: String) = File(directoriesManager.getCoversDirectory(), systemId)

    private fun coverKey(
        systemId: String,
        fileName: String,
    ) = "$systemId/${coverBaseName(fileName)}"

    /** Position in [GameArtFiles.SUPPORTED_EXTENSIONS], or -1 if the file is not a supported image. */
    private fun extensionPriority(file: File) = GameArtFiles.SUPPORTED_EXTENSIONS.indexOf(file.extension.lowercase())

    private fun coverBaseName(fileName: String) = fileName.substringBeforeLast(".")
}
