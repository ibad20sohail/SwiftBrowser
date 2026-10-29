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

data class BookmarkRow(val id: Long, val url: String, val title: String, val created: Long, val folder: String = "")

/** A saved sign-in (2.6); [password] is encrypted (Passwords), never stored as typed. */
class LoginRow(
    val guid: String, val profileId: Long, val origin: String, val formAction: String?, val realm: String?,
    val username: String, val password: String, val created: Long, val used: Long, val times: Int,
)

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
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "swift.db", null, 7) {

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
        addVersion3(db)
        addVersion4(db)
        addVersion5(db)
        addVersion6(db)
        addVersion7(db)

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
        if (oldVersion < 3) addVersion3(db)
        if (oldVersion < 4) addVersion4(db)
        if (oldVersion < 5) addVersion5(db)
        if (oldVersion < 6) addVersion6(db)
        if (oldVersion < 7) addVersion7(db)
    }

    /** 2.4: documents opened in Swift's viewer, where each is, and where it was left. */
    private fun addVersion5(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE documents(id TEXT PRIMARY KEY, uri TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, " +
                "size INTEGER NOT NULL DEFAULT -1, web TEXT, profile_id INTEGER NOT NULL, place TEXT, " +
                "progress REAL NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, opened INTEGER NOT NULL, updated INTEGER NOT NULL)",
        )
    }

    /** 2.3: a picture for each video and article left half-way (the "Continue" cards). */
    private fun addVersion4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE video_positions ADD COLUMN image TEXT")
        db.execSQL("ALTER TABLE reading_positions ADD COLUMN image TEXT")
    }

    /** 2.3: where articles in reader view were left off (the home page's "Continue"). */
    private fun addVersion3(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE reading_positions(page TEXT PRIMARY KEY, title TEXT, share REAL NOT NULL, updated INTEGER NOT NULL)",
        )
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
            // 2.6: its documents and stars go with it.
            db.delete("documents", "profile_id=?", a)
            db.delete("starred_documents", "profile_id=?", a)
            db.delete("logins", "profile_id=?", a)
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

    /**
     * The tabs without their saved history (2.6): what Swift reads at start. A tab's history is
     * read with [tabState] when the tab is opened. [TabRow.state] is "" for a tab that has one.
     */
    fun tabsWithoutState(): List<TabRow> =
        query(
            "SELECT id, profile_id, url, title, state IS NOT NULL, parent_id, last_used, desktop, group_id FROM tabs ORDER BY position, id",
            null,
        ) {
            TabRow(
                it.getLong(0), it.getLong(1), it.str(2), it.str(3), if (it.getInt(4) != 0) "" else null,
                if (it.isNull(5)) null else it.getLong(5), it.getLong(6), it.getInt(7) != 0,
                if (it.isNull(8)) null else it.getLong(8),
            )
        }

    fun tabState(id: Long): String? =
        query("SELECT state FROM tabs WHERE id = ?", arrayOf("$id")) { it.strOrNull(0) }.firstOrNull()

    /** The highest tab id handed out so far, deleted tabs included (new tabs get the next ones, 2.6). */
    fun lastTabId(): Long = try {
        readableDatabase.compileStatement("SELECT seq FROM sqlite_sequence WHERE name = 'tabs'").use { it.simpleQueryForLong() }
    } catch (_: Exception) {
        0L
    }

    /** A new tab with the id Swift gave it (written off the main thread, 2.6). */
    fun insertTab(id: Long, profileId: Long, url: String, title: String, state: String?, parentId: Long?, lastUsed: Long, desktop: Boolean) {
        val done = writableDatabase.insertWithOnConflict("tabs", null, ContentValues().apply {
            put("id", id)
            put("profile_id", profileId)
            put("url", url)
            put("title", title)
            put("state", state)
            if (parentId != null) put("parent_id", parentId) else putNull("parent_id")
            put("last_used", lastUsed)
            put("desktop", if (desktop) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (done < 0) L.w("Tab $id wasn't saved: the id is taken")
    }

    /** Only when the tab was last used (opening a tab, 2.6): nothing else is written. */
    fun setTabLastUsed(id: Long, lastUsed: Long) {
        writableDatabase.update("tabs", ContentValues().apply { put("last_used", lastUsed) }, "id=?", arrayOf("$id"))
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

    /** [keepState]: the tab's history wasn't read (it never opened since Swift started), so it stays as stored. */
    fun updateTab(id: Long, url: String, title: String, state: String?, lastUsed: Long, desktop: Boolean, keepState: Boolean = false) {
        writableDatabase.update("tabs", ContentValues().apply {
            put("url", url)
            put("title", title)
            if (!keepState) put("state", state)
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

    fun importBookmark(profileId: Long, url: String, title: String, created: Long, folder: String = "") {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("profile_id", profileId)
            put("url", url)
            put("title", title)
            put("created", created)
            put("folder", folder)
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

    fun closedTabState(id: Long): String? =
        query("SELECT state FROM closed_tabs WHERE id = ?", arrayOf("$id")) { it.strOrNull(0) }.firstOrNull()

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

    /**
     * [image]: a picture of it, when the page has one (an earlier one is kept otherwise). [trim]: also
     * forget all but the last 300 videos; asked once per run rather than with every save (2.6).
     */
    fun setVideoPosition(page: String, position: Double, duration: Double, image: String? = null, trim: Boolean = false) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("position", position)
            put("duration", duration)
            put("updated", System.currentTimeMillis())
            if (image != null) put("image", image)
        }
        // One transaction, so one write to the phone's storage (2.6).
        db.beginTransaction()
        try {
            if (db.update("video_positions", values, "page=?", arrayOf(page)) == 0) {
                values.put("page", page)
                db.insert("video_positions", null, values)
            }
            if (trim) {
                db.execSQL(
                    "DELETE FROM video_positions WHERE page NOT IN (SELECT page FROM video_positions ORDER BY updated DESC LIMIT 300)",
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteVideoPosition(page: String) {
        writableDatabase.delete("video_positions", "page=?", arrayOf(page))
    }

    fun clearVideoPositions() {
        writableDatabase.delete("video_positions", null, null)
        writableDatabase.delete("reading_positions", null, null)
        // Where documents were left (they stay in the recent list, from the start).
        writableDatabase.execSQL("UPDATE documents SET place = NULL, progress = 0")
    }

    /** 2.5: starred documents (Documents' first section), whether opened in Swift or not. */
    /** 2.6: bookmark folders, and saved sign-ins. */
    private fun addVersion7(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE logins(guid TEXT PRIMARY KEY, profile_id INTEGER NOT NULL, origin TEXT NOT NULL, form_action TEXT, " +
                "realm TEXT, username TEXT NOT NULL, password TEXT NOT NULL, created INTEGER NOT NULL, used INTEGER NOT NULL, " +
                "times INTEGER NOT NULL DEFAULT 0)",
        )
    }

    private fun addVersion6(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE starred_documents(uri TEXT NOT NULL, profile_id INTEGER NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, " +
                "size INTEGER NOT NULL DEFAULT -1, added INTEGER NOT NULL, PRIMARY KEY(uri, profile_id))",
        )
    }

    // ---------------------------------------------------------------- documents (2.4)

    /** A document opened (again): its place is kept, what it is and when is brought up to date. */
    fun saveDocument(id: String, uri: String, name: String, kind: String, size: Long, web: String?, profileId: Long) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("uri", uri)
            put("name", name)
            put("kind", kind)
            put("size", size)
            if (web != null) put("web", web)
            put("profile_id", profileId)
            put("opened", now)
        }
        if (db.update("documents", values, "id=?", arrayOf(id)) == 0) {
            values.put("id", id)
            values.put("updated", now)
            db.insert("documents", null, values)
        }
        db.execSQL("DELETE FROM documents WHERE id NOT IN (SELECT id FROM documents ORDER BY opened DESC LIMIT 200)")
    }

    /** The file of a document changed size (another app edited it): the viewer must ask for the new length. */
    fun setDocumentSize(id: String, size: Long) {
        writableDatabase.execSQL("UPDATE documents SET size = ? WHERE id = ?", arrayOf<Any>(size, id))
    }

    fun touchDocument(id: String) {
        writableDatabase.execSQL("UPDATE documents SET opened = ? WHERE id = ?", arrayOf<Any>(System.currentTimeMillis(), id))
    }

    fun setDocumentPlace(id: String, place: String, progress: Float, total: Int) {
        writableDatabase.update("documents", ContentValues().apply {
            put("place", place)
            put("progress", progress)
            if (total > 0) put("total", total)
            put("updated", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
    }

    private val documentColumns = "id, uri, name, kind, size, web, place, progress, total, opened, updated"

    private fun readDocument(c: Cursor): com.swiftbrowser.docs.Docs.Doc? {
        val kind = com.swiftbrowser.docs.Docs.Kind.ofKey(c.getString(3)) ?: return null
        return com.swiftbrowser.docs.Docs.Doc(
            c.getString(0), c.getString(1), c.getString(2), kind, c.getLong(4), c.getString(5),
            c.getString(6), c.getFloat(7), c.getInt(8), c.getLong(9), c.getLong(10),
        )
    }

    fun document(id: String): com.swiftbrowser.docs.Docs.Doc? =
        query("SELECT $documentColumns FROM documents WHERE id = ?", arrayOf(id)) { readDocument(it) }.firstOrNull()

    /** Documents opened in [profileId], latest first. */
    fun recentDocuments(profileId: Long, limit: Int): List<com.swiftbrowser.docs.Docs.Doc> =
        query("SELECT $documentColumns FROM documents WHERE profile_id = ? ORDER BY opened DESC LIMIT ?", arrayOf("$profileId", "$limit")) {
            readDocument(it)
        }.filterNotNull()

    /** Documents left part-way since [since] (the home page's Continue). */
    fun unfinishedDocuments(profileId: Long, since: Long, limit: Int): List<com.swiftbrowser.docs.Docs.Doc> =
        query(
            "SELECT $documentColumns FROM documents WHERE profile_id = ? AND updated >= ? AND place IS NOT NULL " +
                "AND progress > 0.02 AND progress < 0.97 ORDER BY updated DESC LIMIT ?",
            arrayOf("$profileId", "$since", "$limit"),
        ) { readDocument(it) }.filterNotNull()

    fun forgetDocument(id: String) {
        writableDatabase.delete("documents", "id=?", arrayOf(id))
    }

    /** A document's file was renamed (2.5): its record and star follow it; [newId] is the id of its new place. */
    fun renameDocument(oldUri: String, newUri: String, newName: String, newId: String) {
        val db = writableDatabase
        // A record already at the new place (opened before under that name) gives way.
        db.updateWithOnConflict("documents", ContentValues().apply {
            put("id", newId)
            put("uri", newUri)
            put("name", newName)
        }, "uri=?", arrayOf(oldUri), SQLiteDatabase.CONFLICT_REPLACE)
        db.updateWithOnConflict("starred_documents", ContentValues().apply {
            put("uri", newUri)
            put("name", newName)
        }, "uri=?", arrayOf(oldUri), SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** A document opened from Swift's cache was kept (Save a copy): the record follows the kept file. */
    fun moveDocument(id: String, uri: String) {
        writableDatabase.update("documents", ContentValues().apply { put("uri", uri) }, "id=?", arrayOf(id))
    }

    /** A document's file was deleted: Swift forgets it everywhere. */
    fun forgetDocumentUri(uri: String) {
        writableDatabase.delete("documents", "uri=?", arrayOf(uri))
        writableDatabase.delete("starred_documents", "uri=?", arrayOf(uri))
    }

    class StarredDocument(val uri: String, val name: String, val kind: String, val size: Long, val added: Long)

    fun starDocument(profileId: Long, uri: String, name: String, kind: String, size: Long) {
        writableDatabase.insertWithOnConflict("starred_documents", null, ContentValues().apply {
            put("uri", uri)
            put("profile_id", profileId)
            put("name", name)
            put("kind", kind)
            put("size", size)
            put("added", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unstarDocument(profileId: Long, uri: String) {
        writableDatabase.delete("starred_documents", "uri=? AND profile_id=?", arrayOf(uri, "$profileId"))
    }

    fun starredDocuments(profileId: Long): List<StarredDocument> =
        query("SELECT uri, name, kind, size, added FROM starred_documents WHERE profile_id = ? ORDER BY added DESC", arrayOf("$profileId")) {
            StarredDocument(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4))
        }

    /** Long videos left off since [since] that are in the profile's history: page, title, position, length, when. */
    fun unfinishedVideos(profileId: Long, since: Long, limit: Int): List<Array<Any?>> =
        query(
            "SELECT v.page, h.title, v.position, v.duration, v.updated, v.image FROM video_positions v " +
                "JOIN history h ON h.url = v.page AND h.profile_id = ? WHERE v.updated >= ? ORDER BY v.updated DESC LIMIT ?",
            arrayOf("$profileId", "$since", "$limit"),
        ) { arrayOf(it.getString(0), it.getString(1), it.getDouble(2), it.getDouble(3), it.getLong(4), it.getString(5)) }

    // ---------------------------------------------------------------- where articles were left off (2.3)

    fun setReadingPosition(page: String, title: String?, share: Double, image: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("title", title)
            put("share", share)
            put("updated", System.currentTimeMillis())
            if (image != null) put("image", image)
        }
        if (db.update("reading_positions", values, "page=?", arrayOf(page)) == 0) {
            values.put("page", page)
            db.insert("reading_positions", null, values)
        }
        db.execSQL(
            "DELETE FROM reading_positions WHERE page NOT IN (SELECT page FROM reading_positions ORDER BY updated DESC LIMIT 100)",
        )
    }

    fun deleteReadingPosition(page: String) {
        writableDatabase.delete("reading_positions", "page=?", arrayOf(page))
    }

    /** Articles left off since [since] that are in the profile's history: page, title, share read, when. */
    fun unfinishedArticles(profileId: Long, since: Long, limit: Int): List<Array<Any?>> =
        query(
            "SELECT r.page, IFNULL(r.title, h.title), r.share, r.updated, r.image FROM reading_positions r " +
                "JOIN history h ON h.url = r.page AND h.profile_id = ? WHERE r.updated >= ? ORDER BY r.updated DESC LIMIT ?",
            arrayOf("$profileId", "$since", "$limit"),
        ) { arrayOf(it.getString(0), it.getString(1), it.getDouble(2), it.getLong(3), it.getString(4)) }

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
        // Among the newest 5,000 pages (2.6, through the history_recent index): typing stays quick
        // however long the history gets.
        return query(
            "SELECT id, url, title, last_visit, visits FROM (SELECT id, url, title, last_visit, visits FROM history " +
                "WHERE profile_id = ? ORDER BY last_visit DESC LIMIT 5000) WHERE url LIKE ? OR title LIKE ? " +
                "ORDER BY visits DESC, last_visit DESC LIMIT $limit",
            arrayOf("$profileId", like, like),
        ) { rowToHistory(it) }
    }

    /** Pages visited most, for completing addresses as they're typed (2.6). */
    fun visitedUrls(profileId: Long, limit: Int): List<String> =
        query(
            "SELECT url FROM history WHERE profile_id = ? ORDER BY visits DESC, last_visit DESC LIMIT $limit",
            arrayOf("$profileId"),
        ) { it.str(0) }

    /** When each address was last visited, in any profile (for clearing data of sites not visited for a while). */
    fun lastVisits(): List<Pair<String, Long>> =
        query("SELECT url, MAX(last_visit) FROM history GROUP BY url", null) { it.getString(0) to it.getLong(1) }

    fun recentVisits(profileId: Long, limit: Int): List<HistoryRow> =
        query(
            "SELECT id, url, title, last_visit, visits FROM history WHERE profile_id = ? ORDER BY last_visit DESC LIMIT $limit",
            arrayOf("$profileId"),
        ) { rowToHistory(it) }

    private fun rowToHistory(c: Cursor) = HistoryRow(c.getLong(0), c.str(1), c.str(2), c.getLong(3), c.getInt(4))

    fun deleteHistory(id: Long) {
        writableDatabase.delete("history", "id=?", arrayOf("$id"))
    }

    /** One day's history (visits from [from] until [to]). */
    fun deleteHistoryBetween(profileId: Long, from: Long, to: Long) {
        writableDatabase.delete("history", "profile_id=? AND last_visit>=? AND last_visit<?", arrayOf("$profileId", "$from", "$to"))
    }

    fun clearHistory(profileId: Long) {
        writableDatabase.delete("history", "profile_id=?", arrayOf("$profileId"))
    }

    // ---------------------------------------------------------------- clearing by time (2.6)

    /** The sites (hosts) of [profileId] visited since [since], for clearing their cookies and caches. */
    fun historyHostsSince(profileId: Long, since: Long): Set<String> =
        query("SELECT url FROM history WHERE profile_id = ? AND last_visit >= ?", arrayOf("$profileId", "$since")) { it.getString(0) }
            .mapNotNull { com.swiftbrowser.core.UrlUtils.host(it) }.toSet()

    fun deleteHistorySince(profileId: Long, since: Long) {
        writableDatabase.delete("history", "profile_id=? AND last_visit>=?", arrayOf("$profileId", "$since"))
    }

    fun deleteClosedTabsSince(profileId: Long, since: Long) {
        writableDatabase.delete("closed_tabs", "profile_id=? AND closed_at>=?", arrayOf("$profileId", "$since"))
    }

    /** Documents opened since [since] leave Documents' list (the files stay). */
    fun deleteDocumentsSince(profileId: Long, since: Long) {
        writableDatabase.delete("documents", "profile_id=? AND opened>=?", arrayOf("$profileId", "$since"))
    }

    /** Where videos, articles (any profile: they are kept by page) and [profileId]'s documents were left, since [since]. */
    fun clearPositionsSince(profileId: Long, since: Long) {
        val db = writableDatabase
        db.delete("video_positions", "updated>=?", arrayOf("$since"))
        db.delete("reading_positions", "updated>=?", arrayOf("$since"))
        db.execSQL("UPDATE documents SET place = NULL, progress = 0 WHERE profile_id = ? AND updated >= ?", arrayOf<Any>(profileId, since))
    }

    // ---------------------------------------------------------------- bookmarks

    fun bookmarks(profileId: Long): List<BookmarkRow> =
        query("SELECT id, url, title, created, folder FROM bookmarks WHERE profile_id = ? ORDER BY created DESC",
            arrayOf("$profileId")) { BookmarkRow(it.getLong(0), it.str(1), it.str(2), it.getLong(3), it.str(4)) }

    fun bookmarkMatches(profileId: Long, text: String, limit: Int): List<BookmarkRow> {
        val like = "%${text.trim()}%"
        return query(
            "SELECT id, url, title, created, folder FROM bookmarks WHERE profile_id = ? AND (url LIKE ? OR title LIKE ?) " +
                "ORDER BY created DESC LIMIT $limit",
            arrayOf("$profileId", like, like),
        ) { BookmarkRow(it.getLong(0), it.str(1), it.str(2), it.getLong(3), it.str(4)) }
    }

    fun bookmark(profileId: Long, url: String): BookmarkRow? =
        query("SELECT id, url, title, created, folder FROM bookmarks WHERE profile_id = ? AND url = ?",
            arrayOf("$profileId", url)) { BookmarkRow(it.getLong(0), it.str(1), it.str(2), it.getLong(3), it.str(4)) }.firstOrNull()

    /** The newest bookmarks, for the empty address bar. */
    fun recentBookmarks(profileId: Long, limit: Int): List<BookmarkRow> =
        query("SELECT id, url, title, created, folder FROM bookmarks WHERE profile_id = ? ORDER BY created DESC LIMIT $limit",
            arrayOf("$profileId")) { BookmarkRow(it.getLong(0), it.str(1), it.str(2), it.getLong(3), it.str(4)) }

    /** Moves a bookmark into [folder] ("" is the top of the list, 2.6). */
    fun setBookmarkFolder(id: Long, folder: String) {
        writableDatabase.update("bookmarks", ContentValues().apply { put("folder", folder) }, "id=?", arrayOf("$id"))
    }

    fun renameBookmarkFolder(profileId: Long, from: String, to: String) {
        writableDatabase.update("bookmarks", ContentValues().apply { put("folder", to) }, "profile_id=? AND folder=?", arrayOf("$profileId", from))
    }

    // ---------------------------------------------------------------- saved sign-ins (2.6)

    fun logins(profileId: Long): List<LoginRow> =
        query(
            "SELECT guid, profile_id, origin, form_action, realm, username, password, created, used, times FROM logins " +
                "WHERE profile_id = ? ORDER BY origin, username", arrayOf("$profileId"),
        ) {
            LoginRow(
                it.str(0), it.getLong(1), it.str(2), it.strOrNull(3), it.strOrNull(4), it.str(5), it.str(6),
                it.getLong(7), it.getLong(8), it.getInt(9),
            )
        }

    fun saveLogin(row: LoginRow) {
        writableDatabase.insertWithOnConflict("logins", null, ContentValues().apply {
            put("guid", row.guid)
            put("profile_id", row.profileId)
            put("origin", row.origin)
            put("form_action", row.formAction)
            put("realm", row.realm)
            put("username", row.username)
            put("password", row.password)
            put("created", row.created)
            put("used", row.used)
            put("times", row.times)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loginUsed(guid: String, time: Long) {
        writableDatabase.execSQL("UPDATE logins SET used = ?, times = times + 1 WHERE guid = ?", arrayOf<Any>(time, guid))
    }

    fun deleteLogin(guid: String) {
        writableDatabase.delete("logins", "guid=?", arrayOf(guid))
    }

    fun deleteLoginsSince(profileId: Long, since: Long) {
        writableDatabase.delete("logins", "profile_id=? AND created>=?", arrayOf("$profileId", "$since"))
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
