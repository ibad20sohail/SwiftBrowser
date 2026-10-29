package com.swiftbrowser.core

/**
 * Browser profiles. Each profile gets its own Gecko "context": cookies, logins and site storage
 * never mix between profiles. History, bookmarks and tabs are stored per profile in [Db].
 */
object Profiles {
    val COLORS = intArrayOf(
        0xFF1A73E8.toInt(), // blue
        0xFFD93025.toInt(), // red
        0xFF188038.toInt(), // green
        0xFFF29900.toInt(), // amber
        0xFF9334E6.toInt(), // purple
        0xFF00897B.toInt(), // teal
        0xFFE52592.toInt(), // pink
        0xFF5F6368.toInt(), // grey
    )

    private lateinit var db: Db
    private lateinit var prefs: Prefs
    /** Replaced whole when it changes: other threads (backups, passwords) read it safely. */
    @Volatile private var list: List<ProfileRow> = emptyList()

    fun init(db: Db, prefs: Prefs) {
        this.db = db
        this.prefs = prefs
        reload()
        if (list.none { it.id == prefs.currentProfileId }) prefs.currentProfileId = list.first().id
    }

    private fun reload() {
        var rows = db.profiles()
        if (rows.isEmpty()) {
            db.addProfile("Personal", COLORS[0])
            rows = db.profiles()
        }
        list = rows
    }

    fun all(): List<ProfileRow> = list

    fun get(id: Long): ProfileRow = list.firstOrNull { it.id == id } ?: list.first()

    val currentId: Long get() = prefs.currentProfileId

    fun current(): ProfileRow = get(prefs.currentProfileId)

    /** Gecko session context id. Every profile uses a named context, so the default jar stays empty. */
    fun contextId(id: Long): String = "profile-$id"

    fun add(name: String): ProfileRow {
        val color = COLORS[list.size % COLORS.size]
        val id = db.addProfile(name.ifBlank { "Profile ${list.size + 1}" }, color)
        reload()
        return get(id)
    }

    fun rename(id: Long, name: String) {
        if (name.isBlank()) return
        db.renameProfile(id, name.trim())
        reload()
    }

    /** Removes the profile's rows. The caller clears its Gecko data and closes its tabs. */
    fun delete(id: Long) {
        if (list.size <= 1) return
        db.deleteProfile(id)
        prefs.sp.edit().remove("pinned_tiles_$id").apply()
        reload()
        if (prefs.currentProfileId == id) prefs.currentProfileId = list.first().id
    }

    fun setCurrent(id: Long) {
        prefs.currentProfileId = id
    }

    fun initial(p: ProfileRow): String = p.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
