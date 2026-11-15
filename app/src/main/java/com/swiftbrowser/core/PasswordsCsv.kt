package com.swiftbrowser.core

/**
 * 3.0.11: a passwords file as Google Password Manager, Chrome or Firefox export it (CSV), for "Bring
 * in from Chrome", and Swift's own "Export passwords" in Google's columns ([write]). Its first line
 * names the columns: it needs `url`, `username` and `password` (any order, any case; Google's also
 * has `name` and `note`, which are left out). Plain Kotlin, tested on the PC (PasswordsCsvTest).
 */
object PasswordsCsv {
    /** One password: the site it's for (its origin, as Swift keeps them), the name and the password. */
    class Row(val origin: String, val username: String, val password: String)

    /** The file's passwords, and how many lines were left out (an app's password, an empty one…). */
    class Result(val rows: List<Row>, val skipped: Int)

    /** More would be a file picked by mistake. */
    const val MAX_ROWS = 10_000

    /** The longest name or password kept (as Backup's restore allows). */
    private const val MAX_USER = 1024
    private const val MAX_PASSWORD = 4096

    /** Null when it isn't a passwords file (no `url`, `username` and `password` columns). */
    fun parse(text: String): Result? {
        val records = records(text)
        val header = records.firstOrNull()?.map { it.trim().lowercase() } ?: return null
        val u = header.indexOf("url")
        val n = header.indexOf("username")
        val p = header.indexOf("password")
        if (u < 0 || n < 0 || p < 0) return null
        val rows = ArrayList<Row>()
        var skipped = 0
        for (r in records.drop(1)) {
            if (r.all { it.isBlank() }) continue
            val origin = originOf(r.getOrNull(u).orEmpty())
            val user = r.getOrNull(n).orEmpty()
            val pass = r.getOrNull(p).orEmpty()
            if (origin == null || pass.isEmpty() || user.length > MAX_USER || pass.length > MAX_PASSWORD || rows.size >= MAX_ROWS) {
                skipped++
                continue
            }
            rows += Row(origin, user, pass)
        }
        return Result(rows, skipped)
    }

    /**
     * The file's records, field by field (RFC 4180): a quoted field may hold commas, line breaks and
     * quotes written twice (""); a BOM at the start is dropped; lines end with CR LF or LF.
     */
    fun records(text: String): List<List<String>> {
        val s = text.removePrefix("﻿")
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> if (field.isEmpty()) quoted = true else field.append(c)
                    ',' -> {
                        row.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> Unit
                    '\n' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        out.add(row)
                        row = ArrayList()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            out.add(row)
        }
        return out
    }

    /**
     * 3.0.11: [rows] as Google Password Manager writes its passwords file ("Export passwords"):
     * name,url,username,password,note, a field in quotes when it holds a comma, a quote or a line
     * break. Chrome, Firefox and [parse] bring it in.
     */
    fun write(rows: List<Row>): String = buildString {
        append("name,url,username,password,note\n")
        for (r in rows) {
            val name = try {
                java.net.URI(r.origin).host?.removePrefix("www.")
            } catch (_: Exception) {
                null
            } ?: r.origin
            append(field(name)).append(',').append(field("${r.origin}/")).append(',')
                .append(field(r.username)).append(',').append(field(r.password)).append(",\n")
        }
    }

    /** One field of [write]: quoted (its quotes written twice) when it must be, or starts or ends with a space. */
    private fun field(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || v != v.trim()) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /**
     * "https://m.example.org:8443/a/b?x" → "https://m.example.org:8443", the way a site is kept for
     * its saved passwords; the usual port isn't written. Null for anything but a web address (an
     * app's password is android://…).
     */
    fun originOf(url: String): String? = try {
        val uri = java.net.URI(url.trim())
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty()) {
            null
        } else {
            val usual = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
            "$scheme://$host" + if (uri.port != -1 && !usual) ":${uri.port}" else ""
        }
    } catch (_: Exception) {
        null
    }
}
