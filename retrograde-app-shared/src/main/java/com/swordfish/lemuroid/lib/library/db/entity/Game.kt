/*
 * Game.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.lib.library.db.entity

import androidx.recyclerview.widget.DiffUtil
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "games",
    indices = [
        Index("id", unique = true),
        Index("fileUri", unique = true),
        Index("title"),
        Index("systemId"),
        Index("lastIndexedAt"),
        Index("lastPlayedAt"),
        Index("isFavorite"),
    ],
)
data class Game(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val fileName: String,
    val fileUri: String,
    val title: String,
    val customTitle: String? = null,
    val systemId: String,
    val developer: String?,
    val coverFrontUrl: String?,
    val lastIndexedAt: Long,
    val lastPlayedAt: Long? = null,
    val isFavorite: Boolean = false,
) : Serializable {
    companion object {
        val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<Game>() {
                override fun areItemsTheSame(
                    oldItem: Game,
                    newItem: Game,
                ): Boolean = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: Game,
                    newItem: Game,
                ): Boolean = oldItem == newItem
            }
    }
}

/**
 * The name to show for a game: the one the user gave it, falling back to the one it was indexed
 * under.
 *
 * Everything which puts a game in front of the user should read this rather than [Game.title]. The
 * exceptions are the places which use the name to look something up, such as the cover urls in
 * `LemuroidLibrary` and the bios matching, since those have to keep asking about the original rom.
 *
 * A blank custom name reads as no custom name, so clearing the field in the rename dialog puts the
 * indexed name back rather than leaving the game with nothing to show.
 */
val Game.displayTitle: String
    get() = customTitle?.takeIf { it.isNotBlank() } ?: title
