package com.swiftbrowser.core

import android.app.ActivityManager
import android.content.Context
import android.os.Process

/**
 * Settings → Speed & memory → Right now (3.0.1): the phone's memory, Swift's own, its tabs and the
 * page engine's recent crashes, measured only when asked (nothing runs in the background). No
 * addresses or site names; what Android doesn't tell shows as not available.
 */
object RightNow {
    private const val MB = 1024L * 1024L
    private const val WEEK = 7L * 24 * 3600 * 1000

    class Snapshot(
        val freeMb: Long,
        val totalMb: Long,
        val low: Boolean,
        /** Swift's parts and their memory in MB ("app", "pages"...); empty when Android didn't say. */
        val parts: List<Pair<String, Long>>,
        val tabs: Int,
        val loaded: Int,
        val kept: Int,
        /** Why only the open tab stays loaded now, if it does. */
        val limitedBy: String?,
        val crashesThisWeek: Int,
        /** How the previous run ended, if badly. */
        val lastRun: String?,
        /** When tabs were last unloaded and why (3.0.2), see [unloadedText]. */
        val lastUnloaded: String? = null,
    )

    /** When tabs last let go of their pages and why (3.0.2), [agoMs] ago. */
    fun unloadedText(count: Int, agoMs: Long, why: String): String {
        val min = agoMs / 60_000
        val ago = when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            else -> "${min / 60} h ${min % 60} min ago"
        }
        return "Last unloaded: ${if (count == 1) "1 tab" else "$count tabs"}, $ago, because $why."
    }

    /** Blocking (off the main thread): Swift's processes by part, with their memory (PSS) in MB. */
    fun parts(ctx: Context): List<Pair<String, Long>> = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mine = am.runningAppProcesses.orEmpty().filter { it.uid == Process.myUid() }
        val used = if (mine.isEmpty()) emptyArray() else am.getProcessMemoryInfo(mine.map { it.pid }.toIntArray())
        byPart(mine.indices.map { i -> mine[i].processName to (used.getOrNull(i)?.totalPss?.toLong() ?: 0L) / 1024 })
    } catch (_: Exception) {
        emptyList()
    }

    /** Process names (com.swiftbrowser.app:tab0...) and MB to the parts people know, largest first. */
    fun byPart(processes: List<Pair<String, Long>>): List<Pair<String, Long>> {
        val parts = LinkedHashMap<String, Long>()
        for ((name, mb) in processes) {
            if (mb <= 0) continue
            val part = when (val p = name.substringAfter(':', "")) {
                "" -> "the app"
                "gpu" -> "graphics"
                else -> if (p.startsWith("tab")) "pages" else "other"
            }
            parts[part] = (parts[part] ?: 0L) + mb
        }
        return parts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** The phone's free and total memory (MB) and whether Android says it's low: cheap, any thread. */
    fun memory(ctx: Context): Triple<Long, Long, Boolean> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Triple(mi.availMem / MB, mi.totalMem / MB, mi.lowMemory)
    }

    /** Page engine crashes in the week before [now]. */
    fun crashesSince(times: List<Long>, now: Long): Int = times.count { it in (now - WEEK)..now }

    /** The row's line in Settings. */
    fun summary(freeMb: Long, totalMb: Long, loaded: Int, tabs: Int): String =
        "$freeMb MB free of $totalMb MB · $loaded of $tabs ${if (tabs == 1) "tab" else "tabs"} loaded"

    /** A line of the details: what is measured ([label], null for a sentence of its own) and how it stands. */
    class Row(val label: String?, val value: String)

    /** [details] as rows, each line split at its first colon. */
    fun rows(s: Snapshot): List<Row> = details(s).split("\n").map { line ->
        val colon = line.indexOf(": ")
        if (colon < 0) Row(null, line) else Row(line.substring(0, colon), line.substring(colon + 2))
    }

    /** The details, line by line. */
    fun details(s: Snapshot): String {
        val lines = ArrayList<String>()
        lines += "Phone memory: ${s.freeMb} MB free of ${s.totalMb} MB${if (s.low) ", low" else ""}."
        lines += if (s.parts.isEmpty()) {
            "Swift's own memory: not available."
        } else {
            "Swift uses ${s.parts.sumOf { it.second }} MB: " + s.parts.joinToString(", ") { "${it.first} ${it.second} MB" } + "."
        }
        val kept = if (s.kept > 0) ", ${s.kept} kept loaded" else ""
        lines += "Tabs: ${s.loaded} of ${s.tabs} loaded$kept."
        s.limitedBy?.let { lines += "$it: only the open tab and tabs kept loaded stay loaded." }
        s.lastUnloaded?.let { lines += it }
        lines += when (s.crashesThisWeek) {
            0 -> "No page crashes in the last 7 days."
            1 -> "1 page crash in the last 7 days."
            else -> "${s.crashesThisWeek} page crashes in the last 7 days."
        }
        s.lastRun?.let { lines += it }
        return lines.joinToString("\n")
    }
}
