package com.swiftbrowser.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ProfileRow(val id: Long, val name: String, val color: Int)

data class TabRow(
    val id: Long,
    val profileId: Long,
    val url: String,
    val title: String,
    val state: String?,
    val parentId: Long?,
    val lastUsed: Long,
    val desktop: Boolean,
    val groupId: Long?,
)

data class TabGroupRow(val id: Long, val profileId: Long, val name: String, val color: Int)

/** A tab closed recently, kept so it can be opened again with its history. */
data class ClosedTabRow(
    val id: Long,
    val profileId: Long,
    val url: String,
    val title: String,
    val state: String?,
    val closedAt: Long,
)

data class HistoryRow(val id: Long, val url: String, val title: String, val lastVisit: Long, val visits: Int)

data class BookmarkRow(val id: Long, val url: String, val title: String, val created: Long)

data class DownloadRow(
    val id: Long,
    val profileId: Long,
    val kind: String,
    val url: String,
    val filePath: String,
    val fileName: String,
    val mime: String?,
    val total: Long,
    val done: Long,
    val status: Int,
    val error: String?,
    val headers: String?,
    val pageUrl: String?,
    val extra: String?,
    val created: Long,
)

/**
 * Small hand-written SQLite layer: profiles, tabs (with their order and groups), recently closed
 * tabs, history, bookmarks, downloads, per-site ad levels and where videos were left off.
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "swift.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE profiles(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "color INTEGER NOT NULL, created INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE tabs(id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id INTEGER NOT NULL, " +
                "url TEXT NOT NULL, title TEXT, state TEXT, parent_id INTEGER, last_used INTEGER NOT NULL, " +
                "desktop INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id INTEGER NOT NULL, " +
                "url TEXT NOT NULL, title TEXT, last_visit INTEGER NOT NULL, visits INTEGER NOT NULL DEFAULT 1, " +
                "UNIQUE(profile_id, url))",
        )
        db.execSQL("CREATE INDEX history_recent ON history(profile_id, last_visit DESC)")
        db.execSQL(
            "CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id INTEGER NOT NULL, " +
                "url TEXT NOT NULL, title TEXT, created INTEGER NOT NULL, UNIQUE(profile_id, url))",
        )
        db.execSQL(
            "CREATE TABLE downloads(id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id INTEGER NOT NULL, " +
                "kind TEXT NOT NULL, url TEXT NOT NULL, file_path TEXT NOT NULL, file_name TEXT NOT NULL, " +
                "mime TEXT, total INTEGER NOT NULL DEFAULT -1, done INTEGER NOT NULL DEFAULT 0, " +
                "status INTEGER NOT NULL, error TEXT, headers TEXT, page_url TEXT, extra TEXT, created INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE site_levels(site TEXT PRIMARY KEY, level TEXT NOT NULL)")
        addVersion2(db)

        db.insert("profiles", null, ContentValues().apply {
            put("name", "Personal")
            put("color", Profiles.COLORS[0])
            put("created", System.currentTimeMillis())
        })
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            addVersion2(db)
            // Tabs keep the order they had: the order they were opened in.
            db.execSQL("UPDATE tabs SET position = id")
        }
    }

    /** 1.6: tab order and groups, recently closed tabs, where videos were left off. */
    private fun addVersion2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE tabs ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tabs ADD COLUMN group_id INTEGER")
        db.execSQL(
            "CREATE TABLE tab_groups(id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id INTEGER NOT NULL, " +
                "name TEXT NOT NULL, color INTEGER NOT NULL, created INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE closed_tabs(id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id INTEGER NOT NULL, " +
                "url TEXT NOT NULL, title TEXT, state TEXT, closed_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE video_positions(page TEXT PRIMARY KEY, position REAL NOT NULL, duration REAL NOT NULL, " +
                "updated INTEGER NOT NULL)",
        )
    }

    private inline fun <T> query(sql: String, args: Array<String>?, map: (Cursor) -> T): List<T> {
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out.add(map(c))
            return out
        }
    }

    private fun Cursor.str(i: Int): String = if (isNull(i)) "" else getString(i)
    private fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    // ---------------------------------------------------------------- profiles

    fun profiles(): List<ProfileRow> =
        query("SELECT id, name, color FROM profiles ORDER BY id", null) {
            ProfileRow(it.getLong(0), it.str(1), it.getInt(2))
        }

    fun addProfile(name: String, color: Int): Long =
        writableDatabase.insert("profiles", null, ContentValues().apply {
            put("name", name)
            put("color", color)
            put("created", System.currentTimeMillis())
        })

    fun renameProfile(id: Long, name: String) {
        writableDatabase.update("profiles", ContentValues().apply { put("name", name) }, "id=?", arrayOf("$id"))
    }

    fun deleteProfile(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val a = arrayOf("$id")
            db.delete("profiles", "id=?", a)
            db.delete("tabs", "profile_id=?", a)
            db.delete("tab_groups", "profile_id=?", a)
            db.delete("closed_tabs", "profile_id=?", a)
            db.delete("history", "profile_id=?", a)
            db.delete("bookmarks", "profile_id=?", a)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------------------------------------------------------- tabs

    fun tabs(): List<TabRow> =
        query(
            "SELECT id, profile_id, url, title, state, parent_id, last_used, desktop, group_id FROM tabs ORDER BY position, id",
            null,
        ) {
            TabRow(
                it.getLong(0), it.getLong(1), it.str(2), it.str(3), it.strOrNull(4),
                if (it.isNull(5)) null else it.getLong(5), it.getLong(6), it.getInt(7) != 0,
                if (it.isNull(8)) null else it.getLong(8),
            )
        }

    fun insertTab(profileId: Long, url: String, parentId: Long?, lastUsed: Long, desktop: Boolean): Long =
        writableDatabase.insert("tabs", null, ContentValues().apply {
            put("profile_id", profileId)
            put("url", url)
            put("title", "")
            if (parentId != null) put("parent_id", parentId) else putNull("parent_id")
            put("last_used", lastUsed)
            put("desktop", if (desktop) 1 else 0)
        })

    fun updateTab(id: Long, url: String, title: String, state: String?, lastUsed: Long, desktop: Boolean) {
        writableDatabase.update("tabs", ContentValues().apply {
            put("url", url)
            put("title", title)
            put("state", state)
            put("last_used", lastUsed)
            put("desktop", if (desktop) 1 else 0)
        }, "id=?", arrayOf("$id"))
    }

    fun deleteTab(id: Long) {
        writableDatabase.delete("tabs", "id=?", arrayOf("$id"))
    }

    /** Saves the order of the tabs and which group each is in: (id, group id) in order. */
    fun saveTabOrder(order: List<Pair<Long, Long?>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement("UPDATE tabs SET position = ?, group_id = ? WHERE id = ?").use { st ->
                order.forEachIndexed { i, (id, group) ->
                    st.bindLong(1, i.toLong())
                    if (group != null) st.bindLong(2, group) else st.bindNull(2)
                    st.bindLong(3, id)
                    st.executeUpdateDelete()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------------------------------------------------------- restoring a backup

    /** A tab from a backup, after the tabs already open. Its page loads when it is opened. */
    fun importTab(profileId: Long, url: String, title: String, groupId: Long?, lastUsed: Long) {
        val db = writableDatabase
        val position = db.compileStatement("SELECT IFNULL(MAX(position), 0) + 1 FROM tabs").use { it.simpleQueryForLong() }
        db.insert("tabs", null, ContentValues().apply {
            put("profile_id", profileId)
            put("url", url)
            put("title", title)
            put("last_used", lastUsed)
            put("desktop", 0)
            put("position", position)
            if (groupId != null) put("group_id", groupId) else putNull("group_id")
        })
    }

    fun importHistory(profileId: Long, url: String, title: String, lastVisit: Long, visits: Int) {
        writableDatabase.insertWithOnConflict("history", null, ContentValues().apply {
            put("profile_id", profileId)
            put("url", url)
            put("title", title)
            put("last_visit", lastVisit)
            put("visits", visits)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun importBookmark(profileId: Long, url: String, title: String, created: Long) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("profile_id", profileId)
            put("url", url)
            put("title", title)
            put("created", created)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // ---------------------------------------------------------------- tab groups

    fun tabGroups(): List<TabGroupRow> =
        query("SELECT id, profile_id, name, color FROM tab_groups ORDER BY id", null) {
            TabGroupRow(it.getLong(0), it.getLong(1), it.str(2), it.getInt(3))
        }

    fun insertTabGroup(profileId: Long, name: String, color: Int): Long =
        writableDatabase.insert("tab_groups", null, ContentValues().apply {
            put("profile_id", profileId)
            put("name", name)
            put("color", color)
            put("created", System.currentTimeMillis())
        })

    fun updateTabGroup(id: Long, name: String, color: Int) {
        writableDatabase.update("tab_groups", ContentValues().apply {
            put("name", name)
            put("color", color)
        }, "id=?", arrayOf("$id"))
    }

    fun deleteTabGroup(id: Long) {
        writableDatabase.delete("tab_groups", "id=?", arrayOf("$id"))
    }

    // ---------------------------------------------------------------- recently closed tabs

    fun closedTabs(profileId: Long, limit: Int): List<ClosedTabRow> =
        query(
            "SELECT id, profile_id, url, title, state, closed_at FROM closed_tabs WHERE profile_id = ? " +
                "ORDER BY closed_at DESC, id DESC LIMIT $limit",
            arrayOf("$profileId"),
        ) { ClosedTabRow(it.getLong(0), it.getLong(1), it.str(2), it.str(3), it.strOrNull(4), it.getLong(5)) }

    /** Remembers a closed tab; only the newest [keep] of the profile are kept. Returns its row id. */
    fun addClosedTab(profileId: Long, url: String, title: String, state: String?, keep: Int): Long {
        val db = writableDatabase
        val id = db.insert("closed_tabs", null, ContentValues().apply {
            put("profile_id", profileId)
            put("url", url)
            put("title", title)
            put("state", state)
            put("closed_at", System.currentTimeMillis())
        })
        db.execSQL(
            "DELETE FROM closed_tabs WHERE profile_id = ? AND id NOT IN " +
                "(SELECT id FROM closed_tabs WHERE profile_id = ? ORDER BY closed_at DESC, id DESC LIMIT $keep)",
            arrayOf<Any>(profileId, profileId),
        )
        return id
    }

    fun deleteClosedTab(id: Long) {
        writableDatabase.delete("closed_tabs", "id=?", arrayOf("$id"))
    }

    fun clearClosedTabs(profileId: Long) {
        writableDatabase.delete("closed_tabs", "profile_id=?", arrayOf("$profileId"))
    }

    // ---------------------------------------------------------------- where videos were left off

    /** Position and length in seconds of the video last watched on [page], if it wasn't finished. */
    fun videoPosition(page: String): Pair<Double, Double>? =
        query("SELECT position, duration FROM video_positions WHERE page = ?", arrayOf(page)) {
            it.getDouble(0) to it.getDouble(1)
        }.firstOrNull()

    fun setVideoPosition(page: String, position: Double, duration: Double) {
        val db = writableDatabase
        db.insertWithOnConflict("video_positions", null, ContentValues().apply {
            put("page", page)
            put("position", position)
            put("duration", duration)
            put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "DELETE FROM video_positions WHERE page NOT IN (SELECT page FROM video_positions ORDER BY updated DESC LIMIT 300)",
        )
    }

    fun deleteVideoPosition(page: String) {
        writableDatabase.delete("video_positions", "page=?", arrayOf(page))
    }

    fun clearVideoPositions() {
        writableDatabase.delete("video_positions", null, null)
    }

    // ---------------------------------------------------------------- history

    fun recordVisit(profileId: Long, url: String, title: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val updated = db.compileStatement(
            "UPDATE history SET visits = visits + 1, last_visit = ?, title = CASE WHEN ? = '' THEN title ELSE ? END " +
                "WHERE profile_id = ? AND url = ?",
        ).use { st ->
            st.bindLong(1, now)
            st.bindString(2, title)
            st.bindString(3, title)
            st.bindLong(4, profileId)
            st.bindString(5, url)
            st.executeUpdateDelete()
        }
        if (updated == 0) {
            db.insert("history", null, ContentValues().apply {
                put("profile_id", profileId)
                put("url", url)
                put("title", title)
                put("last_visit", now)
                put("visits", 1)
            })
        }
    }

    fun setHistoryTitle(profileId: Long, url: String, title: String) {
        if (title.isBlank()) return
        writableDatabase.update("history", ContentValues().apply { put("title", title) },
            "profile_id=? AND url=?", arrayOf("$profileId", url))
    }

    fun history(profileId: Long, search: String?, limit: Int = 500): List<HistoryRow> {
        val base = "SELECT id, url, title, last_visit, visits FROM history WHERE profile_id = ?"
        return if (search.isNullOrBlank()) {
            query("$base ORDER BY last_visit DESC LIMIT $limit", arrayOf("$profileId")) { rowToHistory(it) }
        } else {
            val like = "%${search.trim()}%"
            query("$base AND (url LIKE ? OR title LIKE ?) ORDER BY last_visit DESC LIMIT $limit",
                arrayOf("$profileId", like, like)) { rowToHistory(it) }
        }
    }

    /** Best matches for the address bar: frequently and recently visited pages. */
    fun historyMatches(profileId: Long, text: String, limit: Int): List<HistoryRow> {
        val like = "%${text.trim()}%"
        return query(
            "SELECT id, url, title, last_visit, visits FROM history WHERE profile_id = ? AND (url LIKE ? OR title LIKE ?) " +
                "ORDER BY visits DESC, last_visit DESC LIMIT $limit",
            arrayOf("$profileId", like, like),
        ) { rowToHistory(it) }
    }

    /** The most recently visited pages, for the home page shortcuts. */
    fun recentVisits(profileId: Long, limit: Int): List<HistoryRow> =
        query(
            "SELECT id, url, title, last_visit, visits FROM history WHERE profile_id = ? ORDER BY last_visit DESC LIMIT $limit",
            arrayOf("$profileId"),
        ) { rowToHistory(it) }

    private fun rowToHistory(c: Cursor) = HistoryRow(c.getLong(0), c.str(1), c.str(2), c.getLong(3), c.getInt(4))

    fun deleteHistory(id: Long) {
        writableDatabase.delete("history", "id=?", arrayOf("$id"))
    }

    fun clearHistory(profileId: Long) {
        writableDatabase.delete("history", "profile_id=?", arrayOf("$profileId"))
    }

    // ---------------------------------------------------------------- bookmarks

    fun bookmarks(profileId: Long): List<BookmarkRow> =
        query("SELECT id, url, title, created FROM bookmarks WHERE profile_id = ? ORDER BY created DESC",
            arrayOf("$profileId")) { BookmarkRow(it.getLong(0), it.str(1), it.str(2), it.getLong(3)) }

    fun bookmarkMatches(profileId: Long, text: String, limit: Int): List<BookmarkRow> {
        val like = "%${text.trim()}%"
        return query(
            "SELECT id, url, title, created FROM bookmarks WHERE profile_id = ? AND (url LIKE ? OR title LIKE ?) " +
                "ORDER BY created DESC LIMIT $limit",
            arrayOf("$profileId", like, like),
        ) { BookmarkRow(it.getLong(0), it.str(1), it.str(2), it.getLong(3)) }
    }

    fun isBookmarked(profileId: Long, url: String): Boolean =
        query("SELECT 1 FROM bookmarks WHERE profile_id = ? AND url = ? LIMIT 1", arrayOf("$profileId", url)) { 1 }
            .isNotEmpty()

    fun addBookmark(profileId: Long, url: String, title: String) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("profile_id", profileId)
            put("url", url)
            put("title", title)
            put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeBookmark(profileId: Long, url: String) {
        writableDatabase.delete("bookmarks", "profile_id=? AND url=?", arrayOf("$profileId", url))
    }

    fun updateBookmark(id: Long, title: String, url: String) {
        writableDatabase.update("bookmarks", ContentValues().apply {
            put("title", title)
            put("url", url)
        }, "id=?", arrayOf("$id"))
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id=?", arrayOf("$id"))
    }

    // ---------------------------------------------------------------- downloads

    fun downloads(): List<DownloadRow> =
        query(
            "SELECT id, profile_id, kind, url, file_path, file_name, mime, total, done, status, error, headers, " +
                "page_url, extra, created FROM downloads ORDER BY id DESC", null,
        ) {
            DownloadRow(
                it.getLong(0), it.getLong(1), it.str(2), it.str(3), it.str(4), it.str(5), it.strOrNull(6),
                it.getLong(7), it.getLong(8), it.getInt(9), it.strOrNull(10), it.strOrNull(11),
                it.strOrNull(12), it.strOrNull(13), it.getLong(14),
            )
        }

    fun insertDownload(r: DownloadRow): Long =
        writableDatabase.insert("downloads", null, ContentValues().apply {
            put("profile_id", r.profileId)
            put("kind", r.kind)
            put("url", r.url)
            put("file_path", r.filePath)
            put("file_name", r.fileName)
            put("mime", r.mime)
            put("total", r.total)
            put("done", r.done)
            put("status", r.status)
            put("error", r.error)
            put("headers", r.headers)
            put("page_url", r.pageUrl)
            put("extra", r.extra)
            put("created", r.created)
        })

    fun updateDownload(
        id: Long, filePath: String, fileName: String, total: Long, done: Long, status: Int, error: String?, extra: String?,
    ) {
        writableDatabase.update("downloads", ContentValues().apply {
            put("file_path", filePath)
            put("file_name", fileName)
            put("total", total)
            put("done", done)
            put("status", status)
            put("error", error)
            put("extra", extra)
        }, "id=?", arrayOf("$id"))
    }

    fun deleteDownload(id: Long) {
        writableDatabase.delete("downloads", "id=?", arrayOf("$id"))
    }

    // ---------------------------------------------------------------- per-site ad levels

    fun siteLevels(): Map<String, String> =
        query("SELECT site, level FROM site_levels", null) { it.str(0) to it.str(1) }.toMap()

    fun setSiteLevel(site: String, level: String?) {
        if (level == null) {
            writableDatabase.delete("site_levels", "site=?", arrayOf(site))
        } else {
            writableDatabase.insertWithOnConflict("site_levels", null, ContentValues().apply {
                put("site", site)
                put("level", level)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
}
