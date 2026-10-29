package com.swiftbrowser.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where a crash happened (2.7), read from the engine's crash file before it is deleted: the signal
 * (what went wrong) and the library and offset the crash was in, e.g. "memory access error in
 * libxul.so+0x1a2b3c". Enough to tell graphics, scripts, video or memory apart, and to look the
 * offset up later in Firefox's symbols. Only the minidump's exception, system and module lists are
 * read; nothing of the page's memory.
 */
object Minidump {
    private const val SIGNATURE = 0x504d444d // "MDMP"
    private const val MODULE_LIST = 4
    private const val EXCEPTION = 6
    private const val SYSTEM_INFO = 7

    private val SIGNALS = mapOf(
        4 to "illegal instruction (SIGILL)",
        5 to "trap (SIGTRAP)",
        6 to "abort (SIGABRT)",
        7 to "bus error (SIGBUS)",
        8 to "arithmetic error (SIGFPE)",
        11 to "memory access error (SIGSEGV)",
    )

    /** One line, or null when the file can't be read. */
    fun summary(file: File): String? = try {
        RandomAccessFile(file, "r").use { read(it) }
    } catch (_: Exception) {
        null
    }

    private fun read(f: RandomAccessFile): String? {
        fun u32(at: Long): Long {
            f.seek(at)
            val b = ByteArray(4)
            f.readFully(b)
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        }
        fun u64(at: Long): Long {
            f.seek(at)
            val b = ByteArray(8)
            f.readFully(b)
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
        }
        fun u16(at: Long): Int {
            f.seek(at)
            return (f.read() or (f.read() shl 8))
        }
        if (u32(0).toInt() != SIGNATURE) return null
        val streams = u32(8)
        val dirAt = u32(12)
        var exceptionAt = -1L
        var modulesAt = -1L
        var systemAt = -1L
        for (i in 0 until streams.coerceAtMost(64)) {
            val e = dirAt + i * 12
            when (u32(e).toInt()) {
                EXCEPTION -> exceptionAt = u32(e + 8)
                MODULE_LIST -> modulesAt = u32(e + 8)
                SYSTEM_INFO -> systemAt = u32(e + 8)
            }
        }
        if (exceptionAt < 0) return null
        // MINIDUMP_EXCEPTION_STREAM: thread id, padding, then the exception record.
        val signal = u32(exceptionAt + 8).toInt()
        val faultAddress = u64(exceptionAt + 24)
        // The thread context follows the record (15 parameters): its size and place.
        val contextAt = u32(exceptionAt + 8 + 152 + 4)
        val arch = if (systemAt >= 0) u16(systemAt) else -1
        val pc = when (arch) {
            5 -> u32(contextAt + 4 + 15 * 4) // ARM: context_flags, r0..r15 (pc)
            12, 0x8003 -> u64(contextAt + 8 + 32 * 8) // ARM64: flags + cpsr, x0..x30 and sp, then pc
            9 -> u64(contextAt + 0xF8) // AMD64: rip
            0 -> u32(contextAt + 0xB8) // x86: eip
            else -> 0L
        }
        val where = if (pc != 0L && modulesAt >= 0) module(f, modulesAt, pc, ::u32, ::u64) else null
        val what = SIGNALS[signal] ?: "signal $signal"
        return buildString {
            append(what)
            if (where != null) append(" in ").append(where) else if (pc != 0L) append(" at 0x").append(java.lang.Long.toHexString(pc))
            if (signal == 11 || signal == 7) append(", address 0x").append(java.lang.Long.toHexString(faultAddress))
        }
    }

    /** "libxul.so+0x1234" for the library holding [pc]. */
    private fun module(f: RandomAccessFile, at: Long, pc: Long, u32: (Long) -> Long, u64: (Long) -> Long): String? {
        val count = u32(at).coerceAtMost(2000)
        for (i in 0 until count) {
            val m = at + 4 + i * 108
            val base = u64(m)
            val size = u32(m + 8)
            if (pc >= base && pc < base + size) {
                val nameAt = u32(m + 20)
                val len = u32(nameAt).toInt().coerceIn(0, 1024)
                f.seek(nameAt + 4)
                val bytes = ByteArray(len)
                f.readFully(bytes)
                val name = String(bytes, Charsets.UTF_16LE).substringAfterLast('/')
                return "$name+0x${java.lang.Long.toHexString(pc - base)}"
            }
        }
        return null
    }
}
