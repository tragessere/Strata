package com.swordfish.lemuroid.lib.library.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import com.swordfish.lemuroid.lib.library.db.entity.Game

class GameSearchDao(
    private val internalDao: Internal,
) {
    object CALLBACK : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            MIGRATION.migrate(db)

            // A database created from scratch is already at the current version, so the index is
            // brought to its latest shape here rather than being left at the one version 8 had.
            rebuildIndexForCustomTitle(db)
        }
    }

    object MIGRATION : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE VIRTUAL TABLE fts_games USING FTS4(
                  tokenize=unicode61 "remove_diacritics=1",
                  content="games",
                  title);
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_bu BEFORE UPDATE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_bd BEFORE DELETE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_au AFTER UPDATE ON games BEGIN
                  INSERT INTO fts_games(docid, title) VALUES(new.id, new.title);
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_ai AFTER INSERT ON games BEGIN
                  INSERT INTO fts_games(docid, title) VALUES(new.id, new.title);
                END;
                """,
            )
            database.execSQL(
                """
                INSERT INTO fts_games(docid, title) SELECT id, title FROM games;
                """,
            )
        }
    }

    companion object {
        /**
         * Points the index at the name being displayed, so a renamed game is found under the name
         * the user gave it rather than only the one it was indexed under.
         *
         * The table is rebuilt rather than merely re-triggered, because it dropping `content=games`
         * is what makes indexing a different value than `games.title` safe at all. An external
         * content table keeps no copy of what it indexed, so `DELETE FROM fts_games WHERE docid=...`
         * works the terms to take out back out of `games.title`. That was the indexed value until
         * now, but stops being it the moment a custom name is in play, and the removals then miss:
         * clearing a custom name would take out the terms of the indexed name and leave the custom
         * one's behind, so the game would go on answering to a name it no longer has. Holding its
         * own copy costs a second store of the titles, which against a library of any size is
         * nothing, and lets the removals go on being written by docid alone.
         */
        fun rebuildIndexForCustomTitle(database: SupportSQLiteDatabase) {
            database.execSQL("DROP TRIGGER IF EXISTS games_bu")
            database.execSQL("DROP TRIGGER IF EXISTS games_bd")
            database.execSQL("DROP TRIGGER IF EXISTS games_au")
            database.execSQL("DROP TRIGGER IF EXISTS games_ai")
            database.execSQL("DROP TABLE IF EXISTS fts_games")

            database.execSQL(
                """
                CREATE VIRTUAL TABLE fts_games USING FTS4(
                  tokenize=unicode61 "remove_diacritics=1",
                  title);
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_bu BEFORE UPDATE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_bd BEFORE DELETE ON games BEGIN
                  DELETE FROM fts_games WHERE docid=old.id;
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_au AFTER UPDATE ON games BEGIN
                  INSERT INTO fts_games(docid, title)
                    VALUES(new.id, COALESCE(NULLIF(new.customTitle, ''), new.title));
                END;
                """,
            )
            database.execSQL(
                """
                CREATE TRIGGER games_ai AFTER INSERT ON games BEGIN
                  INSERT INTO fts_games(docid, title)
                    VALUES(new.id, COALESCE(NULLIF(new.customTitle, ''), new.title));
                END;
                """,
            )
            database.execSQL(
                """
                INSERT INTO fts_games(docid, title)
                  SELECT id, COALESCE(NULLIF(customTitle, ''), title) FROM games;
                """,
            )
        }
    }

    fun search(query: String): PagingSource<Int, Game> =
        internalDao.rawSearch(
            SimpleSQLiteQuery(
                """
                SELECT games.*
                    FROM fts_games
                    JOIN games ON games.id = fts_games.docid
                    WHERE fts_games MATCH ?
                """,
                arrayOf(query),
            ),
        )

    @Dao
    interface Internal {
        @RawQuery(observedEntities = [(Game::class)])
        fun rawSearch(query: SupportSQLiteQuery): PagingSource<Int, Game>
    }
}
