package com.swordfish.lemuroid.lib.library.metadata

import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID

object LibretroCoverUrls {
    private val THUMB_REPLACE = Regex("[&*/:`<>?\\\\|]")

    /**
     * Url of the boxart libretro publishes for a game.
     *
     * It is derived from the system and the game name alone, which is what makes it possible to
     * rebuild for a game already sitting in the library, without going through a metadata lookup
     * (and the full file read it needs to compute a crc).
     *
     * Games which were matched without a name from the libretro database never had a boxart to
     * begin with, so the url built for those points at an image which does not exist. That renders
     * exactly like a missing cover, which is what they were already showing.
     */
    fun forGameName(
        system: GameSystem,
        name: String?,
    ): String? {
        if (name == null) {
            return null
        }

        // Specific mame version don't have any thumbnails in Libretro database
        val systemName =
            if (system.id == SystemID.MAME2003PLUS) {
                "MAME"
            } else {
                system.libretroFullName
            }

        val imageType = "Named_Boxarts"
        val thumbGameName = name.replace(THUMB_REPLACE, "_")

        return "http://thumbnails.libretro.com/$systemName/$imageType/$thumbGameName.png"
    }
}
