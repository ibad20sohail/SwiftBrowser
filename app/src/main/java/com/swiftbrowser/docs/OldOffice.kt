package com.swiftbrowser.docs

import org.apache.poi.EncryptedDocumentException
import org.apache.poi.ddf.DefaultEscherRecordFactory
import org.apache.poi.ddf.EscherContainerRecord
import org.apache.poi.ddf.EscherOptRecord
import org.apache.poi.ddf.EscherProperty
import org.apache.poi.ddf.EscherSimpleProperty
import org.apache.poi.hpsf.SummaryInformation
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.HWPFDocumentCore
import org.apache.poi.hwpf.HWPFOldDocument
import org.apache.poi.hwpf.OldWordFileFormatException
import org.apache.poi.hwpf.converter.AbstractWordConverter
import org.apache.poi.hwpf.converter.AbstractWordUtils
import org.apache.poi.hwpf.model.CHPX
import org.apache.poi.hwpf.model.FieldsDocumentPart
import org.apache.poi.hwpf.model.PAPX
import org.apache.poi.hwpf.model.PlexOfCps
import org.apache.poi.hwpf.model.SubdocumentType
import org.apache.poi.hwpf.sprm.SprmIterator
import org.apache.poi.hwpf.usermodel.BorderCode
import org.apache.poi.hwpf.usermodel.Bookmark
import org.apache.poi.hwpf.usermodel.Field
import org.apache.poi.hwpf.usermodel.CharacterRun
import org.apache.poi.hwpf.usermodel.OfficeDrawing
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.hwpf.usermodel.Picture
import org.apache.poi.hwpf.usermodel.PictureType
import org.apache.poi.hwpf.usermodel.Range
import org.apache.poi.hwpf.usermodel.Section
import org.apache.poi.hwpf.usermodel.ShadingDescriptor
import org.apache.poi.hwpf.usermodel.Table
import org.apache.poi.hwpf.usermodel.TableRow
import org.apache.poi.poifs.filesystem.DirectoryNode
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.util.LittleEndian
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.Text
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Old Office files (2.8): Word 97–2003 (.doc) and PowerPoint 97–2003 (.ppt) turned into HTML for the
 * document viewer, so they look the way their authors made them instead of showing only their text.
 *
 * Word files are read with Apache POI's HWPF (poi-scratchpad 5.0.0, the newest POI that runs on
 * Android 7: see app/build.gradle.kts) and laid out as pages of the document's own size, fonts,
 * colours, tables and pictures. PowerPoint files are read here from their binary records (POI's
 * slide model needs java.awt): each slide is a box of the slide's size with its text boxes,
 * pictures, simple shapes and background where PowerPoint put them.
 *
 * The HTML is a fragment (no html or body) with its own `<style>`; every class starts with "oo-" and
 * sizes are in pt, so the viewer can scale a page or slide to the screen. The document's colours are
 * kept as they are (white pages, its own text colours) whatever Swift's theme. Pictures are data:
 * URIs in the style sheet (each one once, however often it is used); only JPEG, PNG, GIF and BMP
 * are shown, Windows metafiles (WMF/EMF) and the like get an empty frame of their size.
 */
object OldOffice {
    class Result(val html: String, val pages: Int, val note: String?)

    /** The file can't be read without its password. */
    class PasswordProtected : IOException("This file is protected with a password")

    /**
     * Not an old Word or PowerPoint file after all, though named like one: [looksLike] is what it
     * seems to be ("docx", "pptx", "xlsx", "rtf", "html", "pdf", "doc", "ppt", "xls"), or null.
     */
    class WrongFormat(val looksLike: String?) : IOException("Not an old Office file" + (looksLike?.let { " (looks like $it)" } ?: ""))

    /** The largest file accepted, as for the viewer's other documents (Docs.MAX_WHOLE). */
    const val MAX_FILE = 60L * 1_048_576

    /** Pictures stop being added once their data: URIs reach this many characters. */
    private const val MAX_PICTURES = 40L * 1_048_576

    /** More text than any real document has (about 5000 pages): a damaged file repeating itself. */
    private const val MAX_TEXT = 15L * 1_048_576

    /** Word 97–2003 (and Word 6/95, text and formatting only). Throws on a password or an unreadable file. */
    fun word(input: File): Result = guarded("Word") {
        open(input).use { fs ->
            val root = fs.root
            if (!root.hasEntry("WordDocument")) throw wrongContainer(root)
            // fEncrypted in the FIB (also set for XOR obfuscation): the text itself is scrambled.
            val fib = head(root, "WordDocument", 12)
            if (fib.size >= 12 && LittleEndian.getUShort(fib, 0x0A) and 0x0100 != 0) throw PasswordProtected()
            val doc: HWPFDocumentCore = try {
                HWPFDocument(root)
            } catch (e: OldWordFileFormatException) {
                HWPFOldDocument(root)
            }
            WordHtml(doc).convert()
        }
    }

    /** PowerPoint 97–2003. Throws on a password or an unreadable file. */
    fun slides(input: File): Result = guarded("PowerPoint") {
        open(input).use { fs ->
            val root = fs.root
            if (!root.hasEntry("PowerPoint Document")) {
                // PowerPoint 95 keeps its slides in another stream Swift doesn't read.
                if (root.hasEntry("PP40") || root.hasEntry("PP97_DUALSTORAGE")) throw WrongFormat(null)
                throw wrongContainer(root)
            }
            SlidesHtml(root).convert()
        }
    }

    /** Checks the file, then runs [convert] turning its failures into IOExceptions the caller can show. */
    private fun guarded(what: String, convert: () -> Result): Result = try {
        convert()
    } catch (e: IOException) {
        throw e
    } catch (e: EncryptedDocumentException) {
        throw PasswordProtected()
    } catch (e: OutOfMemoryError) {
        // A huge or broken file: give up on it rather than take Swift down. The memory is freed as
        // the conversion's objects go.
        throw IOException("This $what file is too big to show", e)
    } catch (e: StackOverflowError) {
        throw IOException("Swift couldn't read this $what file", e)
    } catch (e: RuntimeException) {
        throw IOException("Swift couldn't read this $what file", e)
    }

    /** What's inside an Office container that isn't the expected file (an encrypted .docx is one). */
    private fun wrongContainer(root: DirectoryNode): IOException = when {
        root.hasEntry("EncryptedPackage") -> PasswordProtected()
        root.hasEntry("WordDocument") -> WrongFormat("doc")
        root.hasEntry("PowerPoint Document") -> WrongFormat("ppt")
        root.hasEntry("Workbook") || root.hasEntry("Book") -> WrongFormat("xls")
        else -> WrongFormat(null)
    }

    /** Checks the size and the first bytes, then opens the file's compound container. */
    private fun open(input: File): POIFSFileSystem {
        if (input.length() > MAX_FILE) throw IOException("This file is too big to show (over ${MAX_FILE / 1_048_576} MB)")
        val magic = ByteArray(8)
        val n = FileInputStream(input).use { readFully(it, magic) }
        if (!isCompound(magic, n)) throw WrongFormat(sniff(input, magic, n))
        // Read-only: POI reads the blocks it needs onto the heap and never maps the file, so it never
        // reaches its buffer "cleaner" (CleanerUtil), which needs Java APIs Android 7 lacks.
        return POIFSFileSystem(input, true)
    }

    /** The first [n] bytes of [magic] are those of an Office 97–2003 compound file (D0 CF 11 E0 A1 B1 1A E1). */
    internal fun isCompound(magic: ByteArray, n: Int): Boolean =
        n >= 8 && magic.copyOf(8).contentEquals(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))

    /** The first [n] bytes of [magic] start a ZIP file (an Office 2007+ file is one). */
    internal fun isZip(magic: ByteArray, n: Int): Boolean =
        n >= 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() && magic[2].toInt() == 3 && magic[3].toInt() == 4

    /**
     * What a file that isn't a compound file is by its first bytes (3.0: split out, so the viewer can
     * show it as that): "rtf", "pdf", "html", or null. ZIP files are told apart by [sniff].
     */
    internal fun headKind(magic: ByteArray, n: Int): String? {
        val start = String(magic, 0, n.coerceIn(0, magic.size), Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\t', '\r', '\n')
        return when {
            start.startsWith("{\\rtf") -> "rtf"
            start.startsWith("%PDF") -> "pdf"
            start.startsWith("<") -> "html"
            else -> null
        }
    }

    private fun sniff(input: File, magic: ByteArray, n: Int): String? {
        if (isZip(magic, n)) {
            // An Office Open XML file: which one by its main part.
            return try {
                java.util.zip.ZipFile(input).use { z ->
                    when {
                        z.getEntry("word/document.xml") != null -> "docx"
                        z.getEntry("ppt/presentation.xml") != null -> "pptx"
                        z.getEntry("xl/workbook.xml") != null -> "xlsx"
                        else -> null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        return headKind(magic, n)
    }

    private fun head(dir: DirectoryNode, name: String, size: Int): ByteArray = dir.createDocumentInputStream(name).use { s ->
        val b = ByteArray(size)
        b.copyOf(readFully(s, b))
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    // ------------------------------------------------------------------ shared HTML pieces

    /** The style sheet: each distinct style gets one class, shared by everything that uses it. */
    private class Css {
        private val classes = LinkedHashMap<String, String>()

        fun cls(kind: String, style: CharSequence): String {
            val key = kind + "|" + style
            return classes.getOrPut(key) { "oo-" + kind + (classes.size + 1) }
        }

        fun write(out: StringBuilder) {
            for ((key, name) in classes) out.append(".oo .").append(name).append('{').append(key, key.indexOf('|') + 1, key.length).append("}\n")
        }
    }

    /**
     * The pictures shown, kept as raw bytes until the HTML is written, then each written once as a
     * data: URI in a class (`oo-iN`). Identical pictures share one; past [budget] characters no more
     * are added.
     */
    private class Pictures(private val budget: Long) {
        private val data = ArrayList<ByteArray>()
        private val mimes = ArrayList<String>()
        private val byHash = HashMap<Int, MutableList<Int>>()
        private var used = 0L
        var tooMany = 0
        var unsupported = 0

        /** The picture's index, or -1 when the budget is spent. */
        fun add(bytes: ByteArray, mime: String): Int {
            val hash = bytes.contentHashCode()
            byHash[hash]?.firstOrNull { data[it].contentEquals(bytes) }?.let { return it }
            val chars = (bytes.size + 2L) / 3 * 4 + 64
            if (used + chars > budget) {
                tooMany++
                return -1
            }
            used += chars
            data.add(bytes)
            mimes.add(mime)
            byHash.getOrPut(hash) { ArrayList(1) }.add(data.size - 1)
            return data.size - 1
        }

        val size get() = data.size
        val chars get() = used

        fun write(out: StringBuilder) {
            for (i in data.indices) {
                out.append(".oo .oo-i").append(i).append("{background-image:url(\"data:").append(mimes[i]).append(";base64,")
                base64(data[i], out)
                out.append("\")}\n")
            }
        }
    }

    /** What the picture is, by its first bytes: only formats the viewer's engine draws. */
    private fun imageMime(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
        b.size > 26 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
        else -> null
    }

    /** A Windows bitmap stored without its file header (a DIB), made into a .bmp the engine can draw. */
    private fun dibToBmp(dib: ByteArray): ByteArray? {
        if (dib.size < 16) return null
        if (dib[0] == 'B'.code.toByte() && dib[1] == 'M'.code.toByte()) return dib
        val headerSize = LittleEndian.getInt(dib, 0)
        if (headerSize < 12 || headerSize > dib.size) return null
        val bits: Int
        var colors = 0
        var masks = 0
        if (headerSize == 12) {
            bits = LittleEndian.getUShort(dib, 10)
            if (bits <= 8) colors = (1 shl bits) * 3
        } else {
            bits = LittleEndian.getUShort(dib, 14)
            val compression = LittleEndian.getInt(dib, 16)
            val used = if (dib.size >= 36) LittleEndian.getInt(dib, 32) else 0
            colors = 4 * if (used > 0) used else if (bits <= 8) 1 shl bits else 0
            if (headerSize == 40 && (compression == 3 || compression == 6)) masks = if (compression == 3) 12 else 16
        }
        val out = ByteArray(14 + dib.size)
        out[0] = 'B'.code.toByte()
        out[1] = 'M'.code.toByte()
        LittleEndian.putInt(out, 2, out.size)
        LittleEndian.putInt(out, 10, 14 + headerSize + masks + colors)
        System.arraycopy(dib, 0, out, 14, dib.size)
        return out
    }

    private val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

    /** Base64 straight into the HTML (android.util.Base64 would make a second copy; java.util.Base64 is Android 8+). */
    private fun base64(b: ByteArray, out: StringBuilder) {
        out.ensureCapacity(out.length + (b.size + 2) / 3 * 4)
        var i = 0
        val n = b.size
        while (i + 2 < n) {
            val v = (b[i].toInt() and 0xFF shl 16) or (b[i + 1].toInt() and 0xFF shl 8) or (b[i + 2].toInt() and 0xFF)
            out.append(B64[v ushr 18]).append(B64[v ushr 12 and 63]).append(B64[v ushr 6 and 63]).append(B64[v and 63])
            i += 3
        }
        if (i < n) {
            val v = (b[i].toInt() and 0xFF shl 16) or (if (i + 1 < n) b[i + 1].toInt() and 0xFF shl 8 else 0)
            out.append(B64[v ushr 18]).append(B64[v ushr 12 and 63])
            out.append(if (i + 1 < n) B64[v ushr 6 and 63] else '=').append('=')
        }
    }

    private fun escText(s: CharSequence, out: StringBuilder) {
        for (c in s) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> if (c >= ' ' || c == '\t') out.append(c)
            }
        }
    }

    private fun escAttr(s: CharSequence, out: StringBuilder) {
        for (c in s) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> if (c >= ' ') out.append(c)
            }
        }
    }

    /** A number for CSS: at most two decimals, no exponent, whatever the phone's language. */
    private fun num(x: Double): String {
        if (x.isNaN() || x.isInfinite()) return "0"
        val r = (x * 100).roundToLong()
        val a = abs(r)
        val whole = a / 100
        val frac = (a % 100).toInt()
        val sb = StringBuilder()
        if (r < 0) sb.append('-')
        sb.append(whole)
        if (frac != 0) {
            sb.append('.')
            if (frac < 10) sb.append('0').append(frac) else if (frac % 10 == 0) sb.append(frac / 10) else sb.append(frac)
        }
        return sb.toString()
    }

    private fun pt(x: Double) = num(x) + "pt"

    /** "#rrggbb" for 0xRRGGBB. */
    private fun hex(rgb: Int): String {
        val s = Integer.toHexString(rgb and 0xFFFFFF)
        return "#" + "000000".substring(s.length) + s
    }

    /** A Word COLORREF (0x00BBGGRR) as 0xRRGGBB, or -1 for "automatic". */
    private fun fromColorref(v: Int): Int =
        if (v == -1 || v ushr 24 == 0xFF) -1 else (v and 0xFF shl 16) or (v and 0xFF00) or (v ushr 16 and 0xFF)

    /** Word's 16 colours (ico), as 0xRRGGBB; 0 is "automatic". */
    private val ICO = intArrayOf(
        -1, 0x000000, 0x0000FF, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0xFFFF00, 0xFFFFFF,
        0x000080, 0x008080, 0x008000, 0x800080, 0x800000, 0x808000, 0x808080, 0xC0C0C0,
    )

    private fun ico(i: Int) = if (i in ICO.indices) ICO[i] else -1

    private fun dark(rgb: Int): Boolean {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        return r * 299 + g * 587 + b * 114 < 128_000
    }

    private fun mix(fore: Int, back: Int, f: Double): Int {
        fun c(s: Int) = ((fore shr s and 0xFF) * f + (back shr s and 0xFF) * (1 - f)).roundToLong().toInt() shl s
        return c(16) or c(8) or c(0)
    }

    /** A font name safe inside CSS (no quotes, braces, angle brackets or backslashes). */
    private fun cssFontName(name: String): String =
        name.filter { it >= ' ' && it !in "'\"\\;{}<>" }.trim().take(64)

    /** The generic family to fall back on, from the font's name. */
    private fun genericByName(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.contains("mono") || n.contains("courier") || n.contains("consolas") || n.contains("lucida console") -> "monospace"
            n.contains("times") || n.contains("roman") || n.contains("georgia") || n.contains("garamond") ||
                n.contains("cambria") || n.contains("book") || n.contains("serif") && !n.contains("sans") ||
                n.contains("palatino") || n.contains("baskerville") || n.contains("bodoni") || n.contains("century") ||
                n.contains("constantia") || n.contains("mincho") || n.contains("song") || n.contains("batang") -> "serif"
            n.contains("script") || n.contains("comic") || n.contains("brush") || n.contains("hand") -> "cursive"
            else -> "sans-serif"
        }
    }

    /** The weight a font's name says it has ("Arial Black" 900, "Segoe UI Light" 300), if any. */
    private fun fontWeight(name: String): Int? {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.contains("black") || n.contains("heavy") -> 900
            n.contains("extrabold") || n.contains("ultrabold") -> 800
            n.contains("semibold") || n.contains("demibold") || n.endsWith(" demi") -> 600
            n.contains("medium") -> 500
            n.contains("extralight") || n.contains("ultralight") -> 200
            n.contains("light") -> 300
            n.contains("thin") || n.contains("hairline") -> 100
            else -> null
        }
    }

    private fun narrow(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.contains("narrow") || n.contains("condensed") || n.contains("compressed")
    }

    // ------------------------------------------------------------------ symbol fonts

    /** The Symbol font's characters 0x20–0xFF as Unicode (the Greek letters, maths and arrows it is used for). */
    private const val SYMBOL =
        " !∀#∃%&∋()∗+,−./0123456789:;<=>?" +
            "≅ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖ[∴]⊥_" +
            "‾αβχδεφγηιϕκλμνοπθρστυϖωξψζ{|}∼\u007f" +
            "\u0080\u0081\u0082\u0083\u0084\u0085\u0086\u0087\u0088\u0089\u008a\u008b\u008c\u008d\u008e\u008f" +
            "\u0090\u0091\u0092\u0093\u0094\u0095\u0096\u0097\u0098\u0099\u009a\u009b\u009c\u009d\u009e\u009f" +
            "€ϒ′≤⁄∞ƒ♣♦♥♠↔←↑→↓°±″≥×∝∂•÷≠≡≈…⏐⎯↵" +
            "ℵℑℜ℘⊗⊕∅∩∪⊃⊇⊄⊂⊆∈∉∠∇®©™∏√⋅¬∧∨⇔⇐⇑⇒⇓" +
            "◊〈®©™∑⎛⎜⎝⎡⎢⎣⎧⎨⎩⎪ 〉∫⌠⎮⌡⎞⎟⎠⎤⎥⎦⎫⎬⎭ "

    /** The Wingdings characters most used in documents (bullets, ticks, boxes, arrows). */
    private val WINGDINGS = mapOf(
        0x4A to "☺", 0x4B to "😐", 0x4C to "☹", 0x6C to "●", 0x6D to "❍", 0x6E to "■", 0x6F to "□", 0x70 to "◻",
        0x71 to "❑", 0x72 to "❒", 0x73 to "⬧", 0x74 to "⧫", 0x75 to "◆", 0x76 to "❖", 0x77 to "⬥", 0x78 to "⌧",
        0x9F to "•", 0xA1 to "○", 0xA7 to "▪", 0xA8 to "◻", 0xAB to "★", 0xD8 to "➢", 0xDF to "⇦", 0xE0 to "⇨",
        0xE8 to "➔", 0xF0 to "⇨", 0xFB to "✗", 0xFC to "✓", 0xFD to "☒", 0xFE to "☑", 0x6A to "✱", 0x2A to "✉",
        0x28 to "☎", 0xA4 to "◉", 0xA2 to "◎", 0x9E to "·", 0x3F to "✍", 0x46 to "☞", 0x43 to "👍", 0x44 to "👎",
    )

    private fun symbolic(font: String?): Int {
        val f = font?.lowercase(Locale.ROOT) ?: return 0
        return when {
            f == "symbol" -> 1
            f.startsWith("wingdings") || f == "webdings" -> 2
            else -> 0
        }
    }

    /** Text in the Symbol or Wingdings font (or a bullet from them) as the Unicode characters they show. */
    private fun mapSymbols(text: String, kind: Int, bullet: Boolean): String {
        if (kind == 0 && !bullet) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            var c = ch.code
            if (c in 0xF020..0xF0FF) c -= 0xF000 else if (kind == 0) {
                sb.append(ch)
                continue
            }
            val k = if (kind == 0) (if (c == 0xB7) 1 else 2) else kind
            when {
                c < 0x20 || c > 0xFF -> sb.append(ch)
                k == 1 -> sb.append(SYMBOL[c - 0x20])
                else -> sb.append(WINGDINGS[c] ?: if (bullet) "•" else c.toChar().toString())
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ Word

    private val WORD_CSS = """
        .oo{-moz-text-size-adjust:none;text-size-adjust:none;color:#000;text-align:left;line-height:1.15;font-family:'Times New Roman',serif;font-size:10pt;font-weight:normal;font-style:normal;letter-spacing:normal;word-spacing:normal;text-transform:none;text-indent:0}
        .oo *{box-sizing:content-box}
        .oo .oo-page{position:relative;display:flex;flex-direction:column;box-sizing:border-box;margin:0 auto 12pt;background:#fff;color:#000;overflow-wrap:break-word;box-shadow:0 1px 4px rgba(0,0,0,.35)}
        .oo .oo-body{flex:1 0 auto}
        .oo .oo-hdr{flex:none}
        .oo .oo-ftr{flex:none;display:flex;flex-direction:column;justify-content:flex-end}
        .oo .oo-tabs{display:flex;align-items:baseline}
        .oo .oo-tabs>span{text-indent:0;min-width:0}
        .oo .oo-lead{flex:1 1 0;overflow:hidden;white-space:nowrap;min-width:9pt;margin:0 2pt}
        .oo .oo-grow{flex:1 1 0}
        .oo .oo-mid{flex:none;text-align:center}
        .oo .oo-end{flex:1 1 0;text-align:right}
        .oo p{margin:0;padding:0;white-space:pre-wrap;-moz-tab-size:36pt;tab-size:36pt;min-height:0}
        .oo table{border-collapse:collapse;border-spacing:0;table-layout:fixed;margin:0;background:transparent}
        .oo td,.oo th{padding:0 5.4pt;vertical-align:top;text-align:inherit;font-weight:inherit;border:0 none;background:transparent}
        .oo a{color:inherit;text-decoration:inherit}
        .oo sup{line-height:0}
        .oo .oo-img{display:inline-block;background-repeat:no-repeat;background-size:100% 100%;vertical-align:baseline;-webkit-print-color-adjust:exact;print-color-adjust:exact}
        .oo .oo-none{outline:1px dashed #b4b4b4;outline-offset:-1px}
        .oo .oo-fl{float:left;margin:0 9pt 4pt 0}
        .oo .oo-fr{float:right;margin:0 0 4pt 9pt}
        .oo .oo-fc{display:block;margin:4pt auto}
        .oo .oo-label{text-indent:0}
        .oo .oo-notes{margin-top:12pt}
        .oo .oo-notes:before{content:'';display:block;width:144pt;border-top:.75pt solid #000;margin-bottom:6pt}
        .oo .oo-note{font-size:10pt}
        .oo .oo-tb{float:right;margin:0 0 4pt 9pt;border:.75pt solid #000;padding:3.6pt 7.2pt}
        """.trimIndent()

    private class Geometry(
        val width: Double, val height: Double, val top: Double, val right: Double, val bottom: Double, val left: Double,
        val header: Double, val footer: Double, val columns: Int, val gap: Double,
    ) {
        val text get() = width - left - right
    }

    private class Page(val box: Element, val body: Element, val section: Int, val geo: Geometry, val first: Boolean)

    /**
     * Writes a Word document as HTML pages. POI's AbstractWordConverter walks the document (sections,
     * paragraphs, tables, fields, notes, pictures) and calls back here to make the elements.
     */
    private class WordHtml(private val core: HWPFDocumentCore) : AbstractWordConverter() {
        private val doc = core as? HWPFDocument
        private val dom: Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        private val root: Element = dom.createElement("div").also {
            it.setAttribute("class", "oo oo-doc")
            dom.appendChild(it)
        }
        private val css = Css()
        private val pics = Pictures(MAX_PICTURES)
        private val numbering = AbstractWordUtils.NumberingState()

        /** Page boxes as made (before explicit page breaks split them). */
        private val pages = ArrayList<Page>()

        /** The elements text flows into at the top level: page breaks only split pages there. */
        private val flows = HashSet<Element>()
        private var page: Page? = null
        private var sectionIndex = 0
        private var sectionEnd = Int.MAX_VALUE
        private var paraStyle = 0

        /** The width text flows in where the converter is (page text area, header, table cell), pt. */
        private var textWidth = 451.0

        /** The colour under the text being written, for "automatic" text colour (white on dark shading). */
        private var background = 0xFFFFFF
        private var notes: Element? = null
        private var footnotes = 0
        private var endnotes = 0
        private var inHeader = false

        // Fonts: Word 2007+ saves text in the theme font ("Calibri (Body)") as text with no font of
        // its own; the document's default font is in the style sheet header (Stshif.ftcAsciiSym),
        // which POI doesn't apply, so it would show in the font table's first font (Times New Roman).
        private val fontNames: Array<String?>
        private val fontGeneric = HashMap<String, String>()
        private val defaultFont: String?
        private val runs: List<CHPX>? = doc?.characterTable?.textRuns
        private val paragraphsTable: List<PAPX>? = doc?.paragraphTable?.paragraphs
        private val styleSetsFont = HashMap<Int, Boolean>()

        // Headers and footers: the stories of each section in the plcfHdd.
        private var hdd: PlexOfCps? = null
        private var hddStart = 0

        /**
         * Where field codes are (between a field's start and separator marks), sorted: POI leaves a
         * field's code in the text when the field spans paragraphs (a table of contents does).
         */
        private var codeStarts = IntArray(0)
        private var codeEnds = IntArray(0)

        init {
            val names = ArrayList<String?>()
            if (core is HWPFOldDocument) {
                core.oldFontTable?.fontNames?.forEach { names.add(it.mainFontName) }
            } else {
                core.fontTable?.fontNames?.forEach { f ->
                    val name = f.mainFontName
                    names.add(name)
                    if (name != null) fontGeneric[name] = generic(name, try {
                        f.toByteArray()[1].toInt()
                    } catch (e: Exception) {
                        0
                    })
                }
            }
            fontNames = names.toTypedArray()
            defaultFont = doc?.let {
                try {
                    val fc = it.fileInformationBlock.fcStshf
                    val table = it.tableStream
                    val ftc = LittleEndian.getShort(table, fc + 2 + 12).toInt()
                    if (ftc > 0 && ftc < fontNames.size) fontNames[ftc] else null
                } catch (e: Exception) {
                    null
                }
            }
            if (doc != null) {
                try {
                    val fib = doc.fileInformationBlock
                    if (fib.getSubdocumentTextStreamLength(SubdocumentType.HEADER) > 0 && fib.plcfHddSize > 0) {
                        hdd = PlexOfCps(doc.tableStream, fib.plcfHddOffset, fib.plcfHddSize, 0)
                        hddStart = doc.headerStoryRange.startOffset
                    }
                } catch (e: Exception) {
                    hdd = null
                }
                try {
                    // The field marks straight from the PLCFFLD tables (POI's own field list loses
                    // the outer field of nested ones, such as a table of contents).
                    val codes = ArrayList<IntArray>()
                    val fib = doc.fileInformationBlock
                    for ((part, base) in listOf(FieldsDocumentPart.MAIN to 0, FieldsDocumentPart.HEADER to hddStart)) {
                        val length = fib.getFieldsPlcfLength(part)
                        if (length <= 0) continue
                        val plex = PlexOfCps(doc.tableStream, fib.getFieldsPlcfOffset(part), length, 2)
                        val open = ArrayList<IntArray>()
                        for (i in 0 until plex.length()) {
                            val node = plex.getProperty(i)
                            when (node.bytes[0].toInt() and 0x1F) {
                                0x13 -> open.add(intArrayOf(node.start, -1))
                                0x14 -> open.lastOrNull()?.let { if (it[1] < 0) it[1] = node.start }
                                0x15 -> open.removeLastOrNull()?.let {
                                    val end = if (it[1] >= 0) it[1] else node.start
                                    if (end > it[0] + 1) codes.add(intArrayOf(base + it[0] + 1, base + end))
                                }
                            }
                        }
                    }
                    codes.sortBy { it[0] }
                    codeStarts = IntArray(codes.size) { codes[it][0] }
                    codeEnds = IntArray(codes.size) { codes[it][1] }
                } catch (e: Exception) {
                }
            }
        }

        /** Whether the character at [cp] is part of a field's code (hidden in Word). */
        private fun inFieldCode(cp: Int): Boolean {
            var i = codeStarts.binarySearch(cp)
            if (i < 0) i = -i - 2
            // Nested fields: an earlier, longer field's code may hold this one.
            var k = i
            while (k >= 0 && k > i - 8) {
                if (cp >= codeStarts[k] && cp < codeEnds[k]) return true
                k--
            }
            return false
        }

        /** The generic family for a font, from its FFN (pitch and family bits), else its name. */
        private fun generic(name: String, info: Int): String {
            if (info and 3 == 1) return "monospace"
            return when (info shr 4 and 7) {
                1 -> "serif"
                2 -> "sans-serif"
                3 -> "monospace"
                4 -> "cursive"
                else -> genericByName(name)
            }
        }

        private fun fontCss(name: String?): String {
            if (name.isNullOrBlank()) return "serif"
            val g = fontGeneric[name] ?: genericByName(name)
            return "'" + cssFontName(name) + "'," + (if (narrow(name)) "'Roboto Condensed'," else "") + g
        }

        fun convert(): Result {
            processDocument(core)
            if (pages.isEmpty()) newPage(Geometry(612.0, 792.0, 72.0, 72.0, 72.0, 72.0, 36.0, 36.0, 1, 36.0), 0)
            notes?.let { pages.last().body.appendChild(it) }
            val boxes = split()
            decorate(boxes)
            val out = StringBuilder(pics.chars.toInt() + 65_536)
            out.append("<style>\n").append(WORD_CSS).append('\n')
            css.write(out)
            pics.write(out)
            out.append("</style>")
            write(root, out)
            val note = when {
                core !is HWPFDocument -> "This is a very old Word file (Word 6 or 95): Swift shows its text and formatting, without pictures."
                pics.tooMany > 0 -> "This document has too many pictures for Swift to show them all."
                pics.unsupported > 0 -> "Some drawings in this document are in a format Swift can't show."
                else -> null
            }
            return Result(out.toString(), boxes.size, note)
        }

        private fun el(tag: String, cls: String? = null): Element {
            val e = dom.createElement(tag)
            if (cls != null) e.setAttribute("class", cls)
            return e
        }

        override fun getDocument(): Document = dom

        override fun processDocumentInformation(summaryInformation: SummaryInformation) {}

        // ---------------------------------------------------------------- sections and pages

        override fun processSection(wordDocument: HWPFDocumentCore, section: Section, s: Int) {
            sectionIndex = s
            sectionEnd = section.endOffset
            val sep = try {
                core.sectionTable.sections.getOrNull(s)?.sectionProperties
            } catch (e: Exception) {
                null
            }
            val geo = geometry(section, sep?.dyaHdrTop ?: 720, sep?.dyaHdrBottom ?: 720)
            textWidth = (geo.text - (geo.columns - 1) * geo.gap) / geo.columns
            val cur = page
            val flow: Element
            if (cur != null && sep != null && sep.bkc.toInt() == 0) {
                // A continuous section break: the text goes on in the same page, maybe in other columns.
                flow = el("div", columns(geo))
                cur.body.appendChild(flow)
                flows.add(flow)
            } else {
                flow = newPage(geo, s).body
            }
            processParagraphes(wordDocument, flow, section, Int.MIN_VALUE)
        }

        private fun geometry(section: Section, hdrTop: Int, hdrBottom: Int): Geometry {
            fun tw(v: Int, def: Int) = (if (v > 0) v else def) / 20.0
            return Geometry(
                width = tw(section.pageWidth, 12240), height = tw(section.pageHeight, 15840),
                top = abs(section.marginTop) / 20.0, right = abs(section.marginRight) / 20.0,
                bottom = abs(section.marginBottom) / 20.0, left = abs(section.marginLeft) / 20.0,
                header = tw(hdrTop, 720), footer = tw(hdrBottom, 720),
                columns = section.numColumns.coerceIn(1, 12), gap = tw(section.distanceBetweenColumns, 720),
            )
        }

        private fun columns(geo: Geometry): String? =
            if (geo.columns > 1) css.cls("c", "column-count:${geo.columns};column-gap:${pt(geo.gap)}") else null

        private fun newPage(geo: Geometry, s: Int): Page {
            val box = el("div", "oo-page " + pageClass(geo))
            val body = el("div", "oo-body" + (columns(geo)?.let { " $it" } ?: ""))
            box.appendChild(body)
            root.appendChild(box)
            flows.add(body)
            val p = Page(box, body, s, geo, true)
            pages.add(p)
            page = p
            return p
        }

        private fun pageClass(geo: Geometry) = css.cls(
            "g", "width:${pt(geo.width)};min-height:${pt(geo.height)};padding:${pt(geo.top)} ${pt(geo.right)} ${pt(geo.bottom)} ${pt(geo.left)}",
        )

        override fun processPageBreak(wordDocument: HWPFDocumentCore, flow: Element) {
            if (flow in flows) flow.appendChild(dom.createElement("oo-pb"))
        }

        /** Splits the pages at their page-break marks; returns every page box in order. */
        private fun split(): List<Page> {
            val out = ArrayList<Page>()
            for (pg in pages) {
                var box = pg.box
                var body = pg.body
                out.add(pg)
                while (true) {
                    val mark = findMark(body) ?: break
                    if (!contentAfter(mark, body)) {
                        mark.parentNode.removeChild(mark)
                        continue
                    }
                    val box2 = box.cloneNode(false) as Element
                    val body2 = body.cloneNode(false) as Element
                    box2.appendChild(body2)
                    flows.add(body2)
                    moveAfter(mark, body, body2)
                    mark.parentNode.removeChild(mark)
                    box.parentNode.insertBefore(box2, box.nextSibling)
                    box = box2
                    body = body2
                    out.add(Page(box2, body2, pg.section, pg.geo, false))
                }
            }
            return out
        }

        private fun findMark(e: Element): Element? {
            var n = e.firstChild
            while (n != null) {
                if (n is Element) {
                    if (n.tagName == "oo-pb") return n
                    if (n in flows) findMark(n)?.let { return it }
                }
                n = n.nextSibling
            }
            return null
        }

        private fun contentAfter(mark: Node, body: Element): Boolean {
            var n: Node? = mark
            while (n != null && n != body) {
                if (n.nextSibling != null) return true
                n = n.parentNode
            }
            return false
        }

        /** Moves what follows [mark] (in its section's div too) into [body2], keeping the same nesting. */
        private fun moveAfter(mark: Element, body: Element, body2: Element) {
            val chain = ArrayList<Element>()
            var a = mark.parentNode
            while (a != null && a != body) {
                chain.add(0, a as Element)
                a = a.parentNode
            }
            val clones = ArrayList<Element>()
            var dst = body2
            for (c in chain) {
                val copy = c.cloneNode(false) as Element
                dst.appendChild(copy)
                flows.add(copy)
                clones.add(copy)
                dst = copy
            }
            moveSiblings(mark, clones.lastOrNull() ?: body2)
            for (i in chain.indices.reversed()) moveSiblings(chain[i], if (i == 0) body2 else clones[i - 1])
        }

        private fun moveSiblings(from: Node, to: Element) {
            var n = from.nextSibling
            while (n != null) {
                val next = n.nextSibling
                to.appendChild(n)
                n = next
            }
        }

        /** Headers and footers on each page, with their page numbers. */
        private fun decorate(boxes: List<Page>) {
            val facing = doc?.docProperties?.isFFacingPages == true
            val made = HashMap<String, Element?>()
            boxes.forEachIndexed { i, pg ->
                val n = i + 1
                val sep = try {
                    core.sectionTable.sections.getOrNull(pg.section)?.sectionProperties
                } catch (e: Exception) {
                    null
                }
                val titlePage = sep?.fTitlePage == true && pg.first
                // Stories per section: even header, odd header, even footer, odd footer, first header, first footer.
                val h = if (titlePage) 4 else if (facing && n % 2 == 0) 0 else 1
                val f = if (titlePage) 5 else if (facing && n % 2 == 0) 2 else 3
                for ((kind, footer) in listOf(h to false, f to true)) {
                    val key = "${pg.section}/$kind"
                    val tpl = if (made.containsKey(key)) made[key] else headerFooter(pg.section, kind, pg.geo, footer).also { made[key] = it }
                    if (tpl != null) {
                        val copy = tpl.cloneNode(true) as Element
                        fillFields(copy, n, boxes.size)
                        if (footer) pg.box.appendChild(copy) else pg.box.insertBefore(copy, pg.box.firstChild)
                    }
                }
            }
        }

        /**
         * A header or footer for the pages of [section]. It sits in the top (bottom) margin, from
         * Word's header (footer) distance; a taller one pushes the text down (up), as in Word.
         */
        private fun headerFooter(section: Int, kind: Int, geo: Geometry, footer: Boolean): Element? {
            val range = story(section, kind) ?: return null
            val div = el("div")
            val savedBg = background
            inHeader = true
            textWidth = geo.text
            try {
                processParagraphes(core, div, range, Int.MIN_VALUE)
            } catch (e: RuntimeException) {
                return null
            } finally {
                inHeader = false
                background = savedBg
            }
            if (div.textContent.isBlank() && !hasPicture(div)) return null
            val style = if (footer) {
                "margin-bottom:${pt(geo.footer - geo.bottom)};min-height:${pt(max(0.0, geo.bottom - geo.footer))}"
            } else {
                "margin-top:${pt(geo.header - geo.top)};min-height:${pt(max(0.0, geo.top - geo.header))}"
            }
            div.setAttribute("class", (if (footer) "oo-ftr " else "oo-hdr ") + css.cls("h", style))
            return div
        }

        private fun hasPicture(e: Element): Boolean {
            val all = e.getElementsByTagName("span")
            for (i in 0 until all.length) if ((all.item(i) as Element).getAttribute("class").contains("oo-img")) return true
            return false
        }

        /** Story [kind] of [section], or the one before it inherits ("same as previous"). */
        private fun story(section: Int, kind: Int): Range? {
            val plex = hdd ?: return null
            for (s in section downTo 0) {
                val i = 6 + s * 6 + kind
                if (i >= plex.length()) continue
                val node = plex.getProperty(i)
                if (node.end <= node.start) continue
                // The last character of every story is a paragraph mark Word never shows.
                val end = node.end - 1
                if (end <= node.start) return null
                return try {
                    Range(hddStart + node.start, hddStart + end, core)
                } catch (e: RuntimeException) {
                    null
                }
            }
            return null
        }

        private fun fillFields(e: Element, page: Int, total: Int) {
            val spans = e.getElementsByTagName("span")
            for (i in 0 until spans.length) {
                val s = spans.item(i) as Element
                when (s.getAttribute("data-oo-f")) {
                    "PAGE" -> s.textContent = page.toString()
                    "NUMPAGES", "SECTIONPAGES" -> s.textContent = total.toString()
                }
            }
        }

        // ---------------------------------------------------------------- paragraphs

        override fun processParagraphes(wordDocument: HWPFDocumentCore, flow: Element, range: Range, currentTableLevel: Int) {
            val count = range.numParagraphs()
            var i = 0
            while (i < count) {
                val p = range.getParagraph(i)
                if (p.isInTable && p.tableLevel > currentTableLevel.coerceAtLeast(0)) {
                    // A table, or the rest of one cut by a broken file: then its paragraphs as they are.
                    val table = try {
                        range.getTable(p)
                    } catch (e: RuntimeException) {
                        null
                    }
                    if (table != null && table.numParagraphs() > 0) {
                        processTable(wordDocument, flow, table)
                        i += table.numParagraphs()
                        continue
                    }
                }
                if (p.text() == "\u000c") processPageBreak(wordDocument, flow)
                val label = if (p.isInList) {
                    try {
                        AbstractWordUtils.getBulletText(numbering, p.list, p.ilvl.toChar())
                    } catch (e: Exception) {
                        ""
                    }
                } else ""
                try {
                    processParagraph(wordDocument, flow, currentTableLevel, p, label)
                } catch (e: RuntimeException) {
                    // One damaged paragraph doesn't cost the rest of the document.
                }
                i++
            }
        }

        override fun processField(wordDocument: HWPFDocument, parentRange: Range, currentTableLevel: Int, field: Field, currentBlock: Element) {
            try {
                super.processField(wordDocument, parentRange, currentTableLevel, field, currentBlock)
            } catch (e: RuntimeException) {
                // A field POI can't take apart (a broken drop-down, say): what it last showed, unless
                // that is broken too (damaged files can nest fields in themselves).
                if (fieldFallbacks > 0) return
                fieldFallbacks++
                try {
                    field.secondSubrange(parentRange)?.let { processCharacters(wordDocument, currentTableLevel, it, currentBlock) }
                } catch (e2: RuntimeException) {
                } finally {
                    fieldFallbacks--
                }
            }
        }

        private var fieldFallbacks = 0
        private var written = 0L

        override fun processParagraph(wordDocument: HWPFDocumentCore, parentElement: Element, currentTableLevel: Int, paragraph: Paragraph, bulletText: String) {
            val text = paragraph.text()
            if (text == "\u000c") return
            val runs = paragraph.numCharacterRuns()
            if (runs == 0) return
            // Hidden text, paragraph mark included: Word doesn't show the paragraph at all.
            if ((0 until runs).all { paragraph.getCharacterRun(it)?.isVanished == true }) return
            val top = parentElement in flows
            val breakAt = text.indexOf('\u000c')
            // A page break at the end of a section's last paragraph is the section break itself.
            val sectionBreak = breakAt >= 0 && paragraph.endOffset >= sectionEnd
            if (top && (paragraph.pageBreakBefore() || breakAt >= 0 && !sectionBreak && text.substring(0, breakAt).isBlank())) {
                parentElement.appendChild(dom.createElement("oo-pb"))
            }

            val style = StringBuilder()
            val mark = paragraph.getCharacterRun(runs - 1)
            if (mark != null) {
                style.append("font-family:").append(fontCss(fontName(mark, paragraph.styleIndex.toInt()))).append(';')
                style.append("font-size:").append(pt(mark.fontSize / 2.0)).append(';')
            }
            val left = paragraph.indentFromLeft / 20.0
            val right = paragraph.indentFromRight / 20.0
            val first = paragraph.firstLineIndent / 20.0
            if (left != 0.0) style.append("margin-left:").append(pt(left)).append(';')
            if (right != 0.0) style.append("margin-right:").append(pt(right)).append(';')
            if (first != 0.0) style.append("text-indent:").append(pt(first)).append(';')
            // Word adds the space after one paragraph and before the next (CSS margins would overlap).
            if (paragraph.spacingBefore > 0) style.append("margin-top:").append(pt(paragraph.spacingBefore / 20.0)).append(';')
            if (paragraph.spacingAfter > 0) style.append("padding-bottom:").append(pt(paragraph.spacingAfter / 20.0)).append(';')
            lineHeight(paragraph, mark)?.let { style.append("line-height:").append(it).append(';') }
            val bidi = try {
                paragraph.props.fBiDi
            } catch (e: Exception) {
                false
            }
            when (paragraph.justification) {
                1 -> style.append("text-align:center;")
                2 -> style.append("text-align:").append(if (bidi) "left" else "right").append(';')
                3, 4, 5, 7, 8, 9 -> style.append("text-align:justify;")
                0 -> if (bidi) style.append("text-align:right;")
            }
            val shade = paragraphShading(paragraph)
            if (shade >= 0) style.append("background-color:").append(hex(shade)).append(';')
            border(paragraph.topBorder, "top", style)
            border(paragraph.bottomBorder, "bottom", style)
            border(paragraph.leftBorder, "left", style)
            border(paragraph.rightBorder, "right", style)
            val tabs = if (bidi) null else tabPlan(paragraph, text)
            if (tabs != null && tabs.stop > 0 && tabs.stop < textWidth - left - 9) {
                // A right tab short of the margin: the text ends there.
                style.append("padding-right:").append(pt(textWidth - left - tabs.stop - max(0.0, right))).append(';')
            }

            val p = el("p", if (style.isNotEmpty()) css.cls("p", style) else null)
            if (bidi) p.setAttribute("dir", "rtl")
            parentElement.appendChild(p)
            val savedBg = background
            val savedStyle = paraStyle
            if (shade >= 0) background = shade
            paraStyle = paragraph.styleIndex.toInt()
            try {
                if (bulletText.isNotEmpty()) label(p, paragraph, bulletText)
                processCharacters(wordDocument, currentTableLevel, paragraph, p)
            } finally {
                background = savedBg
                paraStyle = savedStyle
            }
            if (!hasContent(p)) p.appendChild(dom.createElement("br")) else if (tabs != null) lineUp(p, tabs)
            if (top && breakAt > 0 && !sectionBreak && text.substring(0, breakAt).isNotBlank()) {
                parentElement.appendChild(dom.createElement("oo-pb"))
            }
        }

        /**
         * How a paragraph's tabs line up when its last tab stops are centred or right-aligned (tables
         * of contents, headers and footers, forms): [three] for left / centre / right, otherwise the
         * text after the last tab goes to the right, after a [leader]. Null for ordinary tabs, which
         * the paragraph's CSS tab size handles.
         */
        private class TabPlan(val three: Boolean, val leader: String?, val stop: Double)

        private fun tabPlan(paragraph: Paragraph, text: String): TabPlan? {
            val count = text.count { it == '\t' }
            if (count == 0 || doc == null) return null
            return try {
                // Bar tabs (jc 4) only draw a line.
                val all = tabStops(paragraph)
                val stops = all.entries.filter { it.value and 7 != 4 }
                if (stops.isEmpty()) return null
                val last = stops.last()
                val jc = last.value and 7
                when {
                    count == 2 && stops.size >= 2 && stops[stops.size - 2].value and 7 == 1 && jc == 2 -> TabPlan(true, null, last.key / 20.0)
                    jc == 2 || jc == 3 -> {
                        val leader = when (last.value shr 3 and 7) {
                            1 -> ". ".repeat(150)
                            2 -> "- ".repeat(150)
                            3, 4 -> "_".repeat(300)
                            5 -> "· ".repeat(150)
                            else -> null
                        }
                        TabPlan(false, leader, last.key / 20.0)
                    }
                    count == 1 && stops.size == 1 && jc == 1 -> TabPlan(true, null, 0.0)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        private val styleTabs = HashMap<Int, java.util.TreeMap<Int, Int>>()

        /**
         * A paragraph's tab stops (position in twips → TBD byte: alignment in bits 0–2, leader in
         * 3–5), from its styles and its own properties. Read here: POI reads the TBDs from the wrong
         * offset.
         */
        private fun tabStops(p: Paragraph): java.util.TreeMap<Int, Int> {
            val tabs = java.util.TreeMap(stylesTabs(p.styleIndex.toInt(), 0))
            papxAt(p.startOffset)?.let { applyTabs(it.sprmBuf.iterator(), tabs) }
            return tabs
        }

        private fun stylesTabs(istd: Int, depth: Int): java.util.TreeMap<Int, Int> {
            styleTabs[istd]?.let { return it }
            val tabs = java.util.TreeMap<Int, Int>()
            val sd = if (istd in 0 until 4095 && depth < 10) try {
                core.styleSheet.getStyleDescription(istd)
            } catch (e: Exception) {
                null
            } else null
            if (sd != null) {
                if (sd.baseStyle != istd) tabs.putAll(stylesTabs(sd.baseStyle, depth + 1))
                val upx = sd.papx
                if (upx != null && upx.size > 2) applyTabs(SprmIterator(upx, 2), tabs)
            }
            styleTabs[istd] = tabs
            return tabs
        }

        /** Applies sprmPChgTabsPapx and sprmPChgTabs (tabs cleared, then added) to [tabs]. */
        private fun applyTabs(it: SprmIterator, tabs: java.util.TreeMap<Int, Int>) {
            while (it.hasNext()) {
                val op = it.next()
                if (op.type != 1 || op.operation != 0x0D && op.operation != 0x15) continue
                val b = op.grpprl
                var o = op.grpprlOffset
                val end = o + op.size() - 3
                if (end > b.size) continue
                val del = b[o++].toInt() and 0xFF
                for (i in 0 until del) if (o + 2 * i + 1 < end) tabs.remove(LittleEndian.getShort(b, o + 2 * i).toInt())
                o += 2 * del
                // sprmPChgTabs also lists how far each deletion reaches.
                if (op.operation == 0x15) o += 2 * del
                if (o >= end) continue
                val add = b[o++].toInt() and 0xFF
                for (i in 0 until add) {
                    if (o + 2 * add + i >= end) break
                    tabs[LittleEndian.getShort(b, o + 2 * i).toInt()] = b[o + 2 * add + i].toInt() and 0xFF
                }
            }
        }

        /** Rearranges a written paragraph by its [TabPlan]. */
        private fun lineUp(p: Element, plan: TabPlan) {
            val (t, k) = lastTab(p) ?: return
            val after = splitAfter(p, t, k)
            if (plan.three) {
                val second = lastTab(p)
                val middle: Element
                if (second != null) {
                    middle = splitAfter(p, second.first, second.second)
                } else {
                    // One tab to a centred stop: the text before it stays left, the rest is centred.
                    middle = after
                    val left = wrap(p)
                    left.setAttribute("class", "oo-grow")
                    middle.setAttribute("class", "oo-mid")
                    p.appendChild(left)
                    p.appendChild(middle)
                    p.appendChild(el("span", "oo-grow"))
                    p.setAttribute("class", (p.getAttribute("class") + " oo-tabs").trim())
                    return
                }
                val left = wrap(p)
                left.setAttribute("class", "oo-grow")
                middle.setAttribute("class", "oo-mid")
                after.setAttribute("class", "oo-end")
                p.appendChild(left)
                p.appendChild(middle)
                p.appendChild(after)
            } else {
                val left = wrap(p)
                val lead = el("span", "oo-lead")
                if (plan.leader != null) lead.appendChild(dom.createTextNode(plan.leader))
                p.appendChild(left)
                p.appendChild(lead)
                p.appendChild(after)
            }
            p.setAttribute("class", (p.getAttribute("class") + " oo-tabs").trim())
        }

        /** The last tab character in [e]'s text: its text node and index. */
        private fun lastTab(e: Node): Pair<Text, Int>? {
            var n = e.lastChild
            while (n != null) {
                if (n is Text) {
                    val k = n.data.lastIndexOf('\t')
                    if (k >= 0) return n to k
                } else if (n is Element && n.getAttribute("class").contains("oo-label").not()) {
                    lastTab(n)?.let { return it }
                }
                n = n.previousSibling
            }
            return null
        }

        /**
         * Cuts [container] at character [k] of [t] (the character itself is dropped): what follows
         * moves to a new span, inside copies of the elements it was in (a link, a run's span).
         */
        private fun splitAfter(container: Element, t: Text, k: Int): Element {
            val rest = t.data.substring(k + 1)
            t.data = t.data.substring(0, k)
            val chain = ArrayList<Element>()
            var a = t.parentNode
            while (a != null && a != container) {
                chain.add(0, a as Element)
                a = a.parentNode
            }
            val out = el("span")
            var dst = out
            val clones = ArrayList<Element>()
            for (c in chain) {
                val copy = c.cloneNode(false) as Element
                copy.removeAttribute("id")
                dst.appendChild(copy)
                clones.add(copy)
                dst = copy
            }
            val deepest = clones.lastOrNull() ?: out
            if (rest.isNotEmpty()) deepest.appendChild(dom.createTextNode(rest))
            moveSiblings(t, deepest)
            for (i in chain.indices.reversed()) moveSiblings(chain[i], if (i == 0) out else clones[i - 1])
            return out
        }

        /** A span holding all of [p]'s children, not yet put back in [p]. */
        private fun wrap(p: Element): Element {
            val span = el("span")
            while (p.firstChild != null) span.appendChild(p.firstChild)
            return span
        }

        private fun hasContent(e: Element): Boolean {
            var n = e.firstChild
            while (n != null) {
                when {
                    n is Text -> if (n.data.isNotEmpty()) return true
                    n is Element -> if (n.tagName != "a" || n.hasChildNodes()) {
                        if (n.tagName == "br" || n.tagName == "span" && n.getAttribute("class").contains("oo-img") || hasContent(n)) return true
                    }
                }
                n = n.nextSibling
            }
            return false
        }

        /** CSS line-height: Word's "single" is about 1.15 lines of the font, multiples of it, or points. */
        private fun lineHeight(p: Paragraph, mark: CharacterRun?): String? {
            val v = try {
                p.lineSpacing.toInt()
            } catch (e: Exception) {
                return null
            }
            val dya = (v and 0xFFFF).toShort().toInt()
            val multiple = v ushr 16 and 0xFFFF
            if (dya == 0) return null
            if (multiple != 0) {
                if (dya == 240) return null
                return num(1.15 * dya / 240.0)
            }
            val points = abs(dya) / 20.0
            if (dya > 0) {
                // "At least": the font's own height when that is more.
                val font = (mark?.fontSize ?: 20) / 2.0 * 1.15
                if (font >= points) return null
            }
            return pt(points)
        }

        /** The list number or bullet before a paragraph, in the list level's font where it has one. */
        private fun label(p: Element, paragraph: Paragraph, text: String) {
            val tab = text.endsWith("\t")
            val run = paragraph.getCharacterRun(0)
            var font: String? = run?.let { fontName(it, paragraph.styleIndex.toInt()) }
            var size = (run?.fontSize ?: 20) / 2.0
            var color = -2
            var bold: Boolean? = null
            var italic: Boolean? = null
            try {
                val lvl = paragraph.list.listData.levels[paragraph.ilvl]
                val chpx = lvl.grpprlChpx
                if (chpx != null) {
                    val it = SprmIterator(chpx, 0)
                    while (it.hasNext()) {
                        val op = it.next()
                        when (op.operation and 0xFF) {
                            0x4F -> fontNames.getOrNull(op.operand and 0xFFFF)?.let { f -> font = f }
                            0x43 -> size = (op.operand and 0xFFFF) / 2.0
                            0x42 -> color = ico(op.operand and 0xFF)
                            0x70 -> color = fromColorref(op.operand)
                            0x35 -> bold = op.operand and 1 != 0
                            0x36 -> italic = op.operand and 1 != 0
                        }
                    }
                }
            } catch (e: Exception) {
            }
            val kind = symbolic(font)
            val shown = mapSymbols(text.trimEnd('\t'), kind, true)
            if (kind != 0) font = null
            val st = StringBuilder()
            if (font != null) st.append("font-family:").append(fontCss(font)).append(';')
            st.append("font-size:").append(pt(size)).append(';')
            if (color >= 0) st.append("color:").append(hex(color)).append(';') else if (run != null) runColor(run, st)
            if (bold == true || bold == null && run?.isBold == true) st.append("font-weight:bold;")
            if (italic == true || italic == null && run?.isItalic == true) st.append("font-style:italic;")
            if (tab) {
                // To the hanging indent (where the text starts), or the next default tab stop.
                val first = paragraph.firstLineIndent / 20.0
                val width = if (first < 0) -first else 18.0
                st.append("display:inline-block;min-width:").append(pt(width)).append(';')
            }
            val span = el("span", "oo-label " + css.cls("s", st))
            span.appendChild(dom.createTextNode(shown))
            p.appendChild(span)
        }

        // ---------------------------------------------------------------- characters

        override fun outputCharacters(block: Element, characterRun: CharacterRun, text: String) {
            write(block, characterRun, text, null)
        }

        private fun write(block: Element, cr: CharacterRun, text: String, symbolFont: String?) {
            if (cr.isVanished || cr.isMarkedDeleted) return
            if (codeStarts.isNotEmpty() && inFieldCode(cr.startOffset)) return
            var font = symbolFont ?: fontName(cr, paraStyle)
            val kind = symbolic(font)
            var t = mapSymbols(text, kind, false)
            if (kind != 0) font = null
            if (t.indexOf('\r') >= 0 || t.indexOf('\n') >= 0) {
                // Several paragraphs in one run (a footnote's text): a line for each.
                val parts = t.split('\r', '\n')
                parts.forEachIndexed { i, part ->
                    if (part.isNotEmpty()) write(block, cr, part, symbolFont)
                    if (i < parts.size - 1) block.appendChild(dom.createElement("br"))
                }
                return
            }
            if (t.isEmpty()) return
            // A damaged file can repeat its text endlessly: stop before the phone runs out of memory.
            written += t.length
            if (written > MAX_TEXT) throw IOException("This Word file is too big to show")
            val cls = css.cls("s", charStyle(cr, font))
            val last = block.lastChild
            if (last is Element && last.tagName == "span" && last.getAttribute("class") == cls && !last.hasAttribute("data-oo-f")) {
                val child = last.lastChild
                if (child is Text) {
                    child.appendData(t)
                    return
                }
            }
            val span = el("span", cls)
            span.appendChild(dom.createTextNode(t))
            block.appendChild(span)
        }

        /** The run's font, or the document's default font where the run (and its styles) set none. */
        private fun fontName(cr: CharacterRun, paraIstd: Int): String? {
            val name = cr.fontName
            val def = defaultFont ?: return name
            if (name != fontNames.getOrNull(0)) return name
            return if (explicitFont(cr, paraIstd)) name else def
        }

        private fun explicitFont(cr: CharacterRun, paraIstd: Int): Boolean {
            val list = runs ?: return true
            val found = chpxAt(list, cr.startOffset)
            if (found != null && found.sprmBuf.findSprm(SPRM_FONT) != null) return true
            return styleFont(cr.styleIndex.toInt(), 0) || styleFont(paraIstd, 0)
        }

        /** The character properties (CHPX) in force at [cp]. */
        private fun chpxAt(list: List<CHPX>, cp: Int): CHPX? {
            var lo = 0
            var hi = list.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = list[mid]
                when {
                    c.start > cp -> hi = mid - 1
                    c.end <= cp -> lo = mid + 1
                    else -> return c
                }
            }
            return null
        }

        private fun styleFont(istd: Int, depth: Int): Boolean {
            if (istd < 0 || istd >= 4095 || depth > 10) return false
            styleSetsFont[istd]?.let { return it }
            val sd = try {
                core.styleSheet.getStyleDescription(istd)
            } catch (e: Exception) {
                null
            }
            var r = false
            if (sd != null) {
                val chpx = sd.chpx
                if (chpx != null) {
                    val it = SprmIterator(chpx, 0)
                    while (it.hasNext()) if (it.next().operation and 0xFF == 0x4F) {
                        r = true
                        break
                    }
                }
                val base = sd.baseStyle
                if (!r && base != istd) r = styleFont(base, depth + 1)
            }
            styleSetsFont[istd] = r
            return r
        }

        private fun runColor(cr: CharacterRun, st: StringBuilder, under: Int = background) {
            var c = try {
                cr.ico24.let { if (it == -1) -1 else fromColorref(it) }
            } catch (e: Exception) {
                -1
            }
            // "Automatic" is black, or white on a dark background.
            if (c == -1 && dark(under)) c = 0xFFFFFF
            if (c != -1) st.append("color:").append(hex(c)).append(';')
        }

        private fun charStyle(cr: CharacterRun, font: String?): String {
            val st = StringBuilder()
            if (font != null) {
                st.append("font-family:").append(fontCss(font)).append(';')
                // A font's own weight or width in its name ("Arial Black", "Arial Narrow"): the phone
                // doesn't have those fonts, but its own font comes in those weights and widths.
                if (!cr.isBold) fontWeight(font)?.let { st.append("font-weight:").append(it).append(';') }
                if (narrow(font)) st.append("font-stretch:condensed;")
            }
            var size = cr.fontSize / 2.0
            val script = cr.subSuperScriptIndex.toInt()
            if (script == 1 || script == 2) {
                size *= 0.65
                st.append("vertical-align:").append(if (script == 1) "super" else "sub").append(';')
            }
            st.append("font-size:").append(pt(size)).append(';')
            if (cr.isBold) st.append("font-weight:bold;")
            if (cr.isItalic) st.append("font-style:italic;")
            val deco = StringBuilder()
            val ul = cr.underlineCode
            if (ul != 0) deco.append("underline ")
            if (cr.isStrikeThrough || cr.isDoubleStrikeThrough) deco.append("line-through ")
            if (deco.isNotEmpty()) {
                st.append("text-decoration:").append(deco.trim()).append(';')
                when {
                    cr.isDoubleStrikeThrough || ul == 3 || ul == 43 -> st.append("text-decoration-style:double;")
                    ul == 4 || ul == 20 -> st.append("text-decoration-style:dotted;")
                    ul in intArrayOf(7, 9, 10, 23, 25, 26, 39, 55) -> st.append("text-decoration-style:dashed;")
                    ul == 11 || ul == 27 -> st.append("text-decoration-style:wavy;")
                }
            }
            // Highlighting is drawn over the run's shading.
            var under = runShading(cr)
            if (cr.isHighlighted) {
                val h = ico(cr.highlightedColor.toInt())
                if (h >= 0) under = h
            }
            if (under >= 0) st.append("background-color:").append(hex(under)).append(';')
            runColor(cr, st, if (under >= 0) under else background)
            if (cr.isCapitalized) st.append("text-transform:uppercase;")
            if (cr.isSmallCaps) st.append("font-variant:small-caps;")
            val offset = cr.verticalOffset
            if (offset != 0 && script == 0) st.append("position:relative;top:").append(pt(-offset / 2.0)).append(';')
            val spacing = cr.characterSpacing
            if (spacing != 0) st.append("letter-spacing:").append(pt(spacing / 20.0)).append(';')
            when {
                cr.isOutlined -> st.append("-webkit-text-stroke:.04em currentColor;-webkit-text-fill-color:#fff;")
                cr.isEmbossed -> st.append("text-shadow:-.04em -.04em 0 #ccc;")
                cr.isImprinted -> st.append("text-shadow:.04em .04em 0 #ccc;")
                cr.isShadowed -> st.append("text-shadow:.06em .06em .04em rgba(0,0,0,.45);")
            }
            cr.border?.let { if (!it.isEmpty && it.borderType != 0) border(it, null, st) }
            return st.toString()
        }

        override fun processSymbol(doc: HWPFDocument, characterRun: CharacterRun, block: Element) {
            val font = try {
                characterRun.symbolFont?.mainFontName
            } catch (e: Exception) {
                null
            }
            write(block, characterRun, characterRun.symbolCharacter.toString(), font ?: "Symbol")
        }

        override fun processLineBreak(block: Element, characterRun: CharacterRun) {
            block.appendChild(dom.createElement("br"))
        }

        override fun processHyperlink(wordDocument: HWPFDocumentCore, currentBlock: Element, textRange: Range?, currentTableLevel: Int, hyperlink: String) {
            if (textRange == null) return
            val a = el("a")
            val url = hyperlink.trim()
            val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
            if (scheme in setOf("http", "https", "mailto", "ftp", "tel")) {
                a.setAttribute("href", url)
                a.setAttribute("rel", "noopener noreferrer")
            }
            currentBlock.appendChild(a)
            processCharacters(wordDocument, currentTableLevel, textRange, a)
        }

        override fun processPageref(wordDocument: HWPFDocumentCore, currentBlock: Element, textRange: Range?, currentTableLevel: Int, pageref: String) {
            if (textRange == null) return
            val a = el("a")
            a.setAttribute("href", "#" + anchor(pageref))
            currentBlock.appendChild(a)
            processCharacters(wordDocument, currentTableLevel, textRange, a)
        }

        override fun processBookmarks(wordDocument: HWPFDocumentCore, currentBlock: Element, range: Range?, currentTableLevel: Int, rangeBookmarks: List<Bookmark>) {
            for (b in rangeBookmarks) {
                val a = el("a")
                a.setAttribute("id", anchor(b.name ?: continue))
                currentBlock.appendChild(a)
            }
            if (range != null) processCharacters(wordDocument, currentTableLevel, range, currentBlock)
        }

        private fun anchor(name: String) = "oo-bm-" + name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }

        override fun processDeadField(wordDocument: HWPFDocumentCore, currentBlock: Element, range: Range, currentTableLevel: Int, beginMark: Int, separatorMark: Int, endMark: Int) {
            // Page numbers in headers and footers: filled in for each page.
            val code = StringBuilder()
            for (i in beginMark + 1 until separatorMark) code.append(range.getCharacterRun(i).text())
            val name = code.toString().trim().substringBefore(' ').uppercase(Locale.ROOT)
            if (name == "PAGE" || name == "NUMPAGES" || name == "SECTIONPAGES") {
                val cr = range.getCharacterRun(if (separatorMark + 1 < endMark) separatorMark + 1 else beginMark)
                val span = el("span", css.cls("s", charStyle(cr, fontName(cr, paraStyle))))
                span.setAttribute("data-oo-f", name)
                span.appendChild(dom.createTextNode("1"))
                currentBlock.appendChild(span)
                return
            }
            super.processDeadField(wordDocument, currentBlock, range, currentTableLevel, beginMark, separatorMark, endMark)
        }

        // ---------------------------------------------------------------- notes

        override fun processFootnoteAutonumbered(wordDocument: HWPFDocument, noteIndex: Int, block: Element, footnoteTextRange: Range) {
            note(wordDocument, (++footnotes).toString(), "f$footnotes", block, footnoteTextRange)
        }

        override fun processEndnoteAutonumbered(wordDocument: HWPFDocument, noteIndex: Int, block: Element, endnoteTextRange: Range) {
            note(wordDocument, roman(++endnotes), "e$endnotes", block, endnoteTextRange)
        }

        /** The note's number in the text, and its text with the others after the document's end. */
        private fun note(wordDocument: HWPFDocument, mark: String, id: String, block: Element, range: Range) {
            val sup = el("sup")
            val a = el("a")
            a.setAttribute("href", "#oo-n$id")
            a.setAttribute("id", "oo-r$id")
            a.appendChild(dom.createTextNode(mark))
            sup.appendChild(a)
            block.appendChild(sup)
            val list = notes ?: el("div", "oo-notes").also { notes = it }
            val p = el("p", "oo-note")
            p.setAttribute("id", "oo-n$id")
            val back = el("a")
            back.setAttribute("href", "#oo-r$id")
            val s = el("sup")
            s.appendChild(dom.createTextNode(mark))
            back.appendChild(s)
            p.appendChild(back)
            p.appendChild(dom.createTextNode(" "))
            list.appendChild(p)
            try {
                processCharacters(wordDocument, Int.MIN_VALUE, range, p)
            } catch (e: RuntimeException) {
            }
        }

        private fun roman(n: Int): String {
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val s = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
            var x = n
            val sb = StringBuilder()
            for (i in v.indices) while (x >= v[i]) {
                sb.append(s[i])
                x -= v[i]
            }
            return sb.toString()
        }

        // ---------------------------------------------------------------- pictures

        // The crop getters are "deprecated" because Word 2000+ also keeps cropping in the shape; both are read.
        @Suppress("DEPRECATION")
        override fun processImage(currentBlock: Element, inlined: Boolean, picture: Picture) {
            val sx = picture.horizontalScalingFactor.let { if (it > 0) it / 1000.0 else 1.0 }
            val sy = picture.verticalScalingFactor.let { if (it > 0) it / 1000.0 else 1.0 }
            var w = picture.dxaGoal * sx / 20
            var h = picture.dyaGoal * sy / 20
            if (w <= 0 || h <= 0) {
                w = max(1, picture.width) * 0.75
                h = max(1, picture.height) * 0.75
            }
            // Cropping: Word 97 kept it in the PICF, later versions in the picture's shape.
            var cl = picture.dxaCropLeft * sx / 20
            var cr = picture.dxaCropRight * sx / 20
            var ct = picture.dyaCropTop * sy / 20
            var cb = picture.dyaCropBottom * sy / 20
            if (cl == 0.0 && cr == 0.0 && ct == 0.0 && cb == 0.0) shapeCrop(picture)?.let { f ->
                // Then the goal size is the part shown: the whole picture is larger.
                w /= max(0.05, 1 - f[0] - f[2])
                h /= max(0.05, 1 - f[1] - f[3])
                cl = f[0] * w
                ct = f[1] * h
                cr = f[2] * w
                cb = f[3] * h
            }
            val id = when (picture.suggestPictureType()) {
                // Windows and Mac metafiles, TIFF: nothing on the phone draws them.
                PictureType.EMF, PictureType.WMF, PictureType.PICT, PictureType.TIFF -> null
                PictureType.BMP -> dibToBmp(picture.content)?.let { pics.add(it, "image/bmp") }
                else -> picture.content.let { b -> imageMime(b)?.let { pics.add(b, it) } }
            }
            if (id == null) pics.unsupported++
            val alt = try {
                picture.description
            } catch (e: Exception) {
                null
            }
            picture(currentBlock, id ?: -1, w, h, cl, ct, cr, cb, null, alt)
        }

        /**
         * A picture box [w]×[h] pt showing the picture with [cl]/[ct]/[cr]/[cb] pt cut off its edges,
         * or an empty frame when there's no picture to show (id -1).
         */
        private fun picture(block: Element, id: Int, w: Double, h: Double, cl: Double, ct: Double, cr: Double, cb: Double, extra: String?, alt: String?) {
            val vw = max(1.0, w - cl - cr)
            val vh = max(1.0, h - ct - cb)
            val st = StringBuilder("width:").append(pt(vw)).append(";height:").append(pt(vh)).append(';')
            if (id >= 0 && (cl != 0.0 || cr != 0.0 || ct != 0.0 || cb != 0.0) && w > 0 && h > 0) {
                st.append("background-size:").append(num(100 * w / vw)).append("% ").append(num(100 * h / vh)).append("%;")
                val px = if (cl + cr != 0.0) 100 * cl / (cl + cr) else 0.0
                val py = if (ct + cb != 0.0) 100 * ct / (ct + cb) else 0.0
                st.append("background-position:").append(num(px)).append("% ").append(num(py)).append("%;")
            }
            val cls = StringBuilder("oo-img ")
            cls.append(if (id >= 0) "oo-i$id " else "oo-none ").append(css.cls("z", st))
            if (extra != null) cls.append(' ').append(extra)
            val span = el("span", cls.toString())
            span.setAttribute("role", "img")
            if (!alt.isNullOrBlank()) span.setAttribute("aria-label", alt.take(500))
            block.appendChild(span)
        }

        /** Crop fractions (left, top, right, bottom) from the picture's shape properties, if any. */
        private fun shapeCrop(picture: Picture): DoubleArray? {
            val data = doc?.dataStream ?: return null
            return try {
                val o = picture.startOffset
                // PICF (68 bytes), the picture's name for mm 0x66, then its shape (an OfficeArt container).
                var p = o + 68
                if (LittleEndian.getUShort(data, o + 6) == 0x66) p += 1 + (data[p].toInt() and 0xFF)
                val shape = EscherContainerRecord()
                shape.fillFields(data, p, DefaultEscherRecordFactory())
                val opt = shape.getChildById<EscherOptRecord>(EscherOptRecord.RECORD_ID) ?: return null
                fun frac(id: Int) = (opt.lookup<EscherProperty>(id) as? EscherSimpleProperty)?.propertyValue?.let { it / 65536.0 } ?: 0.0
                val f = doubleArrayOf(frac(0x102), frac(0x100), frac(0x103), frac(0x101))
                if (f.all { it == 0.0 } || f.any { it < 0 || it >= 1 }) null else f
            } catch (e: Exception) {
                null
            }
        }

        override fun processImage(currentBlock: Element, inlined: Boolean, picture: Picture, url: String) {}

        override fun processImageWithoutPicturesManager(currentBlock: Element, inlined: Boolean, picture: Picture) {}

        /** Floating pictures: beside the text on the side Word put them (exact positions need Word's layout). */
        override fun processDrawnObject(doc: HWPFDocument, characterRun: CharacterRun, block: Element) {
            val drawings = if (inHeader) doc.officeDrawingsHeaders else doc.officeDrawingsMain
            val od = drawings.getOfficeDrawingAt(characterRun.startOffset) ?: return
            val w = (od.rectangleRight - od.rectangleLeft) / 20.0
            val h = (od.rectangleBottom - od.rectangleTop) / 20.0
            if (w <= 0 || h <= 0) return
            val data = try {
                od.pictureData
            } catch (e: Exception) {
                null
            } ?: return
            val mime = imageMime(data)
            val id = if (mime != null) pics.add(data, mime) else dibToBmp(data)?.takeIf { looksLikeDib(data) }?.let { pics.add(it, "image/bmp") }
            if (id == null) pics.unsupported++
            val text = page?.geo?.text ?: 451.0
            val left = od.rectangleLeft / 20.0
            val side = when (od.horizontalPositioning) {
                OfficeDrawing.HorizontalPositioning.RIGHT, OfficeDrawing.HorizontalPositioning.OUTSIDE -> "oo-fr"
                OfficeDrawing.HorizontalPositioning.CENTER -> "oo-fc"
                OfficeDrawing.HorizontalPositioning.LEFT, OfficeDrawing.HorizontalPositioning.INSIDE -> "oo-fl"
                else -> if (left + w / 2 > text / 2 + 36) "oo-fr" else if (abs(left + w / 2 - text / 2) <= 36 && w < text * 0.8) "oo-fc" else "oo-fl"
            }
            picture(block, id ?: -1, w, h, 0.0, 0.0, 0.0, 0.0, side, null)
        }

        private fun looksLikeDib(b: ByteArray) = b.size > 40 && LittleEndian.getInt(b, 0).let { it == 40 || it == 12 || it == 108 || it == 124 }

        override fun processDrawnObject(doc: HWPFDocument, characterRun: CharacterRun, officeDrawing: OfficeDrawing, path: String, block: Element) {}

        // ---------------------------------------------------------------- tables

        override fun processTable(wordDocument: HWPFDocumentCore, flow: Element, table: Table) {
            val el = try {
                tableElement(wordDocument, table)
            } catch (e: RuntimeException) {
                null
            }
            if (el != null) {
                flow.appendChild(el)
                return
            }
            // A table POI can't make sense of: its paragraphs one after the other.
            for (i in 0 until table.numParagraphs()) {
                val p = table.getParagraph(i)
                processParagraph(wordDocument, flow, table.tableLevel, p, "")
            }
        }

        private fun tableElement(wordDocument: HWPFDocumentCore, table: Table): Element? {
            val rows = table.numRows()
            if (rows == 0) return null
            // Every cell edge in the table: the columns.
            val edgeSet = java.util.TreeSet<Int>()
            for (r in 0 until rows) {
                val row = table.getRow(r)
                for (c in 0 until row.numCells()) {
                    val cell = row.getCell(c)
                    edgeSet.add(cell.leftEdge)
                    edgeSet.add(cell.leftEdge + cell.width)
                }
            }
            if (edgeSet.size < 2) return null
            val edges = edgeSet.toIntArray()
            val t = el("table")
            val first = table.getRow(0)
            val ts = StringBuilder("width:").append(pt((edges.last() - edges.first()) / 20.0)).append(';')
            when (first.rowJustification) {
                1 -> ts.append("margin-left:auto;margin-right:auto;")
                2 -> ts.append("margin-left:auto;")
                else -> if (edges.first() != 0) ts.append("margin-left:").append(pt(edges.first() / 20.0)).append(';')
            }
            t.setAttribute("class", css.cls("t", ts))
            val colgroup = el("colgroup")
            for (i in 0 until edges.size - 1) {
                val col = el("col", css.cls("w", "width:" + pt((edges[i + 1] - edges[i]) / 20.0)))
                colgroup.appendChild(col)
            }
            t.appendChild(colgroup)
            val tbody = el("tbody")
            t.appendChild(tbody)
            for (r in 0 until rows) {
                val row = table.getRow(r)
                val tr = el("tr")
                val height = row.rowHeight
                if (height != 0) tr.setAttribute("class", css.cls("r", "height:" + pt(abs(height) / 20.0)))
                val shades = rowShading(row)
                val cells = row.numCells()
                var c = 0
                while (c < cells) {
                    val cell = row.getCell(c)
                    if (cell.isVerticallyMerged && !cell.isFirstVerticallyMerged || cell.isMerged && !cell.isFirstMerged) {
                        c++
                        continue
                    }
                    var right = cell.leftEdge + cell.width
                    if (cell.isFirstMerged) {
                        // Old-style horizontal merge: the cells after it that join it.
                        var k = c + 1
                        while (k < cells && row.getCell(k).isMerged && !row.getCell(k).isFirstMerged) {
                            right = row.getCell(k).leftEdge + row.getCell(k).width
                            k++
                        }
                    }
                    val from = edges.binarySearch(cell.leftEdge)
                    val to = edges.binarySearch(right)
                    val span = if (from >= 0 && to > from) to - from else 1
                    val td = el(if (row.isTableHeader) "th" else "td")
                    if (span > 1) td.setAttribute("colspan", span.toString())
                    if (cell.isFirstVerticallyMerged) {
                        val rs = try {
                            getNumberRowsSpanned(table, edges, r, c, cell)
                        } catch (e: RuntimeException) {
                            1
                        }
                        if (rs > 1) td.setAttribute("rowspan", rs.toString())
                    }
                    val cs = StringBuilder()
                    val gap = row.gapHalf / 20.0
                    if (gap >= 0 && gap != 5.4) cs.append("padding-left:").append(pt(gap)).append(";padding-right:").append(pt(gap)).append(';')
                    when (cell.vertAlign.toInt()) {
                        1 -> cs.append("vertical-align:middle;")
                        2 -> cs.append("vertical-align:bottom;")
                    }
                    var shade = shades?.getOrNull(c) ?: -1
                    if (shade < 0) shade = try {
                        shading(cell.descriptor?.shd)
                    } catch (e: Exception) {
                        -1
                    }
                    if (shade >= 0) cs.append("background-color:").append(hex(shade)).append(';')
                    border(pick(cell.brcTop, if (r == 0) row.topBorder else row.horizontalBorder), "top", cs)
                    border(pick(cell.brcBottom, if (r == rows - 1) row.bottomBorder else row.horizontalBorder), "bottom", cs)
                    border(pick(cell.brcLeft, if (c == 0) row.leftBorder else row.verticalBorder), "left", cs)
                    border(pick(cell.brcRight, if (c == cells - 1) row.rightBorder else row.verticalBorder), "right", cs)
                    if (cs.isNotEmpty()) td.setAttribute("class", css.cls("d", cs))
                    val savedBg = background
                    val savedWidth = textWidth
                    if (shade >= 0) background = shade
                    textWidth = (right - cell.leftEdge) / 20.0 - 2 * max(0.0, gap)
                    try {
                        processParagraphes(wordDocument, td, cell, table.tableLevel)
                    } finally {
                        background = savedBg
                        textWidth = savedWidth
                    }
                    if (!td.hasChildNodes()) td.appendChild(el("p")).appendChild(dom.createElement("br"))
                    tr.appendChild(td)
                    c++
                }
                tbody.appendChild(tr)
            }
            return t
        }

        private fun pick(cell: BorderCode?, row: BorderCode?): BorderCode? =
            if (cell != null && !cell.isEmpty && cell.borderType != 0) cell else row

        /** The paragraph properties (PAPX) in force at [cp]. */
        private fun papxAt(cp: Int): PAPX? {
            val list = paragraphsTable ?: return null
            var lo = 0
            var hi = list.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val p = list[mid]
                when {
                    p.start > cp -> hi = mid - 1
                    p.end <= cp -> lo = mid + 1
                    else -> return p
                }
            }
            return null
        }

        /**
         * A paragraph's shading, from its own properties or its style's. (POI reads sprmPShd from the
         * wrong place in the property list, so its Paragraph.getShading() is mostly noise.)
         */
        private fun paragraphShading(p: Paragraph): Int {
            if (doc == null) return -1
            try {
                papxAt(p.startOffset)?.let { px -> shadingIn(px.sprmBuf.iterator(), 1)?.let { return it } }
                var istd = p.styleIndex.toInt()
                var depth = 0
                while (istd in 0 until 4095 && depth++ < 10) {
                    val sd = core.styleSheet.getStyleDescription(istd) ?: break
                    val upx = sd.papx
                    if (upx != null && upx.size > 2) shadingIn(SprmIterator(upx, 2), 1)?.let { return it }
                    if (sd.baseStyle == istd) break
                    istd = sd.baseStyle
                }
            } catch (e: Exception) {
            }
            return -1
        }

        /**
         * The last shading set in [it]: sprmPShd/sprmPShd80 for paragraphs ([type] 1), sprmCShd/
         * sprmCShd80 for characters (2). Null if none is set, -1 if it is set to none.
         */
        private fun shadingIn(it: SprmIterator, type: Int): Int? {
            var found: Int? = null
            val (wide, narrow) = if (type == 1) 0x4D to 0x2D else 0x71 to 0x66
            while (it.hasNext()) {
                val op = it.next()
                if (op.type != type) continue
                when (op.operation) {
                    wide -> if (op.size() >= 13) {
                        val b = op.grpprl
                        val o = op.grpprlOffset
                        found = shade(LittleEndian.getInt(b, o), LittleEndian.getInt(b, o + 4), LittleEndian.getUShort(b, o + 8))
                    }
                    narrow -> {
                        val v = op.operand and 0xFFFF
                        found = shade80(v)
                    }
                }
            }
            return found
        }

        /** A Word 97 shading (Shd80: foreground and background of the 16 colours, and a pattern). */
        private fun shade80(v: Int): Int {
            val fore = ico(v and 0x1F)
            val back = ico(v shr 5 and 0x1F)
            val pattern = v shr 10 and 0x3F
            if (pattern == 0x3F) return -1
            return shadeRgb(fore, back, pattern)
        }

        /** A run's own shading (sprmCShd/sprmCShd80), -1 for none. */
        private fun runShading(cr: CharacterRun): Int {
            val list = runs ?: return -1
            val c = chpxAt(list, cr.startOffset) ?: return -1
            return try {
                shadingIn(c.sprmBuf.iterator(), 2) ?: -1
            } catch (e: Exception) {
                -1
            }
        }

        /** Cell shading in exact colours (sprmTDefTableShd, Word 2000+), by cell, from the row's properties. */
        private fun rowShading(row: TableRow): IntArray? {
            val n = row.numParagraphs()
            if (n == 0) return null
            val buf = papxAt(row.getParagraph(n - 1).startOffset)?.sprmBuf ?: return null
            var out: IntArray? = null
            for ((sprm, base) in listOf(SPRM_SHD to 0, SPRM_SHD2 to 22, SPRM_SHD3 to 44)) {
                val op = buf.findSprm(sprm) ?: continue
                val bytes = op.grpprl
                val start = op.grpprlOffset
                val len = op.size() - 3
                val count = len / 10
                if (out == null) out = IntArray(63) { -1 }
                for (i in 0 until count) {
                    val o = start + i * 10
                    if (o + 10 > bytes.size || base + i >= out.size) break
                    val fore = LittleEndian.getInt(bytes, o)
                    val back = LittleEndian.getInt(bytes, o + 4)
                    val pat = LittleEndian.getUShort(bytes, o + 8)
                    out[base + i] = shade(fore, back, pat)
                }
            }
            return out
        }

        private fun shading(shd: ShadingDescriptor?): Int {
            if (shd == null || shd.isEmpty) return -1
            return shade(shd.cvFore.value, shd.cvBack.value, shd.ipat)
        }

        /** The colour of a shading given in COLORREFs. */
        private fun shade(foreRef: Int, backRef: Int, pattern: Int): Int =
            if (pattern == 0xFFFF) -1 else shadeRgb(fromColorref(foreRef), fromColorref(backRef), pattern)

        /**
         * The colour of a shading: background, foreground, or a mix for the "percent" patterns; -1
         * for none. Colours are 0xRRGGBB, -1 for automatic.
         */
        private fun shadeRgb(foreRgb: Int, backRaw: Int, pattern: Int): Int {
            val fore = if (foreRgb == -1) 0x000000 else foreRgb
            val back = if (backRaw == -1) 0xFFFFFF else backRaw
            val percent = when (pattern) {
                0 -> return if (backRaw == -1) -1 else back
                1 -> return fore
                in 2..13 -> PERCENT[pattern - 2]
                in 0x23..0x3E -> PERCENT2[pattern - 0x23]
                // Stripes and grids: about half of each colour.
                else -> 50.0
            }
            return mix(fore, back, percent / 100.0)
        }

        /** A Word border as CSS (an empty [side] means all four). */
        private fun border(b: BorderCode?, side: String?, st: StringBuilder) {
            if (b == null || b.isEmpty) return
            val type = b.borderType
            if (type == 0 || type == 0xFF) return
            var width = b.lineWidth / 8.0
            if (width <= 0) width = 0.5
            val style = when (type) {
                3, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21 -> {
                    width = max(width * 3, 2.25)
                    "double"
                }
                6, 26 -> "dotted"
                7, 8, 9, 22, 23 -> "dashed"
                24 -> "ridge"
                25 -> "groove"
                5 -> {
                    width = 0.25
                    "solid"
                }
                else -> "solid"
            }
            val color = ico(b.color.toInt()).let { if (it == -1) 0 else it }
            st.append(if (side == null) "border" else "border-$side").append(':').append(pt(width)).append(' ').append(style).append(' ').append(hex(color)).append(';')
            if (side != null && b.space > 0 && side != "left" && side != "right") st.append("padding-").append(side).append(':').append(pt(b.space.toDouble())).append(';')
        }

        // ---------------------------------------------------------------- writing the HTML

        private fun write(node: Node, out: StringBuilder) {
            when (node) {
                is Text -> escText(node.data, out)
                is Element -> {
                    val tag = node.tagName
                    if (tag == "oo-pb") return
                    out.append('<').append(tag)
                    val attrs = node.attributes
                    for (i in 0 until attrs.length) {
                        val a = attrs.item(i)
                        if (a.nodeName.startsWith("data-oo")) continue
                        out.append(' ').append(a.nodeName).append("=\"")
                        escAttr(a.nodeValue, out)
                        out.append('"')
                    }
                    out.append('>')
                    if (tag == "br" || tag == "col") return
                    var c = node.firstChild
                    while (c != null) {
                        write(c, out)
                        c = c.nextSibling
                    }
                    out.append("</").append(tag).append('>')
                }
            }
        }
    }

    /** How much of the foreground the shading patterns "5 percent" to "90 percent" (Ipat 2–13) show. */
    private val PERCENT = doubleArrayOf(5.0, 10.0, 20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 70.0, 75.0, 80.0, 90.0)

    /** The same for Ipat 0x23–0x3E (2.5 to 97 percent). */
    private val PERCENT2 = doubleArrayOf(
        2.5, 7.5, 12.5, 15.0, 17.5, 22.5, 27.5, 32.5, 35.0, 37.5, 42.5, 45.0, 47.5, 52.5,
        55.0, 57.5, 62.5, 65.0, 67.5, 72.5, 77.5, 82.5, 85.0, 87.5, 92.5, 95.0, 97.5, 97.0,
    )

    private val SPRM_FONT = 0x4A4F.toShort()
    private val SPRM_SHD = 0xD612.toShort()
    private val SPRM_SHD2 = 0xD616.toShort()
    private val SPRM_SHD3 = 0xD60C.toShort()

    // ------------------------------------------------------------------ PowerPoint

    private val SLIDES_CSS = """
        .oo{-moz-text-size-adjust:none;text-size-adjust:none;color:#000;text-align:left;line-height:normal;font-weight:normal;font-style:normal;letter-spacing:normal;text-transform:none;text-indent:0}
        .oo *{box-sizing:border-box}
        .oo .oo-slide{position:relative;overflow:hidden;margin:0 auto 12pt;background:#fff;color:#000;box-shadow:0 1px 4px rgba(0,0,0,.35);overflow-wrap:break-word}
        .oo .oo-sp{position:absolute;display:flex;flex-direction:column;justify-content:flex-start}
        .oo .oo-sp p{margin:0;padding:0;white-space:pre-wrap;-moz-tab-size:72pt;tab-size:72pt}
        .oo .oo-mid{justify-content:center}
        .oo .oo-bot{justify-content:flex-end}
        .oo .oo-nowrap p{white-space:pre}
        .oo .oo-ell{border-radius:50%}
        .oo .oo-round{border-radius:10%}
        .oo .oo-img{position:absolute;background-repeat:no-repeat;background-size:100% 100%;-webkit-print-color-adjust:exact;print-color-adjust:exact}
        .oo .oo-none{outline:1px dashed #b4b4b4;outline-offset:-1px}
        .oo .oo-line{position:absolute;height:0;transform-origin:0 0}
        .oo .oo-bullet{display:inline-block;text-indent:0}
        .oo .oo-notes{margin:-6pt auto 16pt;padding:6pt 10pt;background:#fff;color:#000;font:11pt sans-serif;box-shadow:0 1px 4px rgba(0,0,0,.35)}
        .oo .oo-notes summary{cursor:pointer;color:#555}
        .oo .oo-notes p{margin:4pt 0 0;white-space:pre-wrap}
        """.trimIndent()

    /** Text from a slide: its characters, paragraph and character runs, ruler and type (title, body…). */
    private class TextBlock(val type: Int) {
        var text = ""
        val paras = ArrayList<IntArray>()
        val chars = ArrayList<IntArray>()
        var ruler: IntArray? = null
        val meta = HashMap<Int, Int>()
    }

    /** A shape: kind (spt), FSP flags, properties, where it is on the slide (pt), and what it holds. */
    private class Shape(val spt: Int, val flags: Int, val spid: Int, val props: Map<Int, Int>) {
        var box: DoubleArray? = null
        var placeholder = -1
        var text: TextBlock? = null
        var textRef = -1
    }

    /**
     * A PowerPoint 97–2003 file read from its records (MS-PPT): the persist directory gives the
     * slides in order, each slide's drawing gives its shapes, and the master gives the text styles,
     * colour scheme, background and the shapes behind every slide.
     */
    private class SlidesHtml(private val root: DirectoryNode) {
        private val s: ByteArray = root.createDocumentInputStream("PowerPoint Document").use { it.readBytes() }
        private val css = Css()
        private val pics = Pictures(MAX_PICTURES)
        private val out = StringBuilder()
        private val persist = HashMap<Int, Int>()
        private val fontNames = HashMap<Int, String>()
        private val fontFamily = HashMap<Int, String>()
        private val blips = ArrayList<Int>()
        private val blipIds = HashMap<Int, Int>()
        private val defaults = HashMap<Int, Int>()
        private var width = 720.0
        private var height = 540.0
        private var footer: String? = null
        private var skippedShapes = 0

        /** Master text styles: [text type][level] → PF and CF. The document's own defaults are under -1. */
        private val masterStyles = HashMap<Int, HashMap<Int, Array<Array<IntArray?>>>>()
        private val masterShapes = HashMap<Int, Map<Int, Shape>>()

        // ---------------------------------------------------------------- records

        private fun u8(o: Int) = s[o].toInt() and 0xFF
        private fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        private fun s16(o: Int) = u16(o).toShort().toInt()
        private fun i32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        private fun type(o: Int) = u16(o + 2)
        private fun inst(o: Int) = u16(o) ushr 4
        private fun len(o: Int) = i32(o + 4)
        private fun ok(o: Int) = o >= 0 && o + 8 <= s.size && len(o) >= 0 && o + 8L + len(o) <= s.size

        private fun children(o: Int): List<Int> {
            val list = ArrayList<Int>()
            if (!ok(o)) return list
            var p = o + 8
            val end = o + 8 + len(o)
            while (p + 8 <= end) {
                val l = len(p)
                if (l < 0 || p + 8L + l > end) break
                list.add(p)
                p += 8 + l
            }
            return list
        }

        private fun child(o: Int, t: Int, i: Int = -1): Int? = children(o).firstOrNull { type(it) == t && (i < 0 || inst(it) == i) }

        private fun utf16(o: Int, n: Int): String {
            val sb = StringBuilder(n / 2)
            var i = 0
            while (i + 1 < n && o + i + 1 < s.size) {
                sb.append((u8(o + i) or (u8(o + i + 1) shl 8)).toChar())
                i += 2
            }
            return sb.toString()
        }

        // ---------------------------------------------------------------- the document

        fun convert(): Result {
            val doc = documentOffset()
            val slides = ArrayList<Int>()
            val notesById = HashMap<Int, Int>()
            val slideTexts = HashMap<Int, List<TextBlock>>()
            if (doc != null) {
                readDocument(doc)
                for (list in children(doc).filter { type(it) == 0x0FF0 }) {
                    // Each SlidePersistAtom, then the texts kept for that slide (its placeholders' text).
                    var current = -1
                    var texts = ArrayList<TextBlock>()
                    for (c in children(list)) {
                        if (type(c) == 0x03F3) {
                            if (current >= 0) slideTexts[current] = texts
                            val ref = i32(c + 8)
                            val id = i32(c + 8 + 12)
                            val at = persist[ref] ?: -1
                            current = at
                            texts = ArrayList()
                            when (inst(list)) {
                                0 -> if (at >= 0) slides.add(at)
                                1 -> masterById[id] = at
                                2 -> notesById[id] = at
                            }
                        } else {
                            readText(c, texts)
                        }
                    }
                    if (current >= 0) slideTexts[current] = texts
                }
            }
            listedTexts = slideTexts
            if (slides.isEmpty()) {
                // No usable directory (a damaged file): the slides in the order they are stored.
                for (c in topRecords()) when (type(c)) {
                    0x03EE -> slides.add(c)
                    0x03F8 -> if (masterById.isEmpty()) masterById[0] = c
                }
                // PowerPoint 95 and older keep their slides in records Swift doesn't read.
                if (slides.isEmpty() && doc == null) throw IOException("This PowerPoint file is too old for Swift to show (PowerPoint 95 or earlier)")
            }
            if (slides.isEmpty()) return Result("<div class=\"oo oo-deck\"></div>", 0, "This presentation has no slides.")
            out.append("<div class=\"oo oo-deck\">")
            slides.forEachIndexed { i, at ->
                try {
                    slide(at, i + 1, slideTexts[at].orEmpty(), notesById)
                } catch (e: RuntimeException) {
                    // A damaged slide: an empty one in its place, the others still shown.
                    out.append("<div class=\"oo-slide ").append(css.cls("g", "width:${pt(width)};height:${pt(height)}")).append("\"></div>")
                }
            }
            out.append("</div>")
            val html = StringBuilder(out.length + pics.chars.toInt() + 8192)
            html.append("<style>\n").append(SLIDES_CSS).append('\n')
            css.write(html)
            pics.write(html)
            html.append("</style>").append(out)
            val note = when {
                pics.tooMany > 0 -> "This presentation has too many pictures for Swift to show them all."
                pics.unsupported > 0 || skippedShapes > 0 -> "Swift shows the slides' text, pictures and simple shapes; charts, diagrams and some drawings are left out."
                else -> null
            }
            return Result(html.toString(), slides.size, note)
        }

        private val masterById = HashMap<Int, Int>()
        private var listedTexts: Map<Int, List<TextBlock>> = emptyMap()

        /** The slide being written (for slide-number fields). */
        private var number = 0

        private fun topRecords(): List<Int> {
            val list = ArrayList<Int>()
            var p = 0
            while (p + 8 <= s.size) {
                val l = len(p)
                if (l < 0 || p + 8L + l > s.size) break
                list.add(p)
                p += 8 + l
            }
            return list
        }

        /** Reads the persist directory (newest edit first) and returns the document container's offset. */
        private fun documentOffset(): Int? {
            val cu = try {
                root.createDocumentInputStream("Current User").use { it.readBytes() }
            } catch (e: Exception) {
                return null
            }
            if (cu.size < 20) return null
            // The header token says whether the presentation is encrypted.
            if (LittleEndian.getInt(cu, 12) == 0xF3D1C4DF.toInt() || root.hasEntry("EncryptedSummary")) throw PasswordProtected()
            var edit = LittleEndian.getInt(cu, 16)
            var docRef = -1
            val seen = HashSet<Int>()
            while (edit > 0 && ok(edit) && type(edit) == 0x0FF5 && seen.add(edit)) {
                val b = edit + 8
                if (docRef < 0) docRef = i32(b + 16)
                val dir = i32(b + 12)
                if (ok(dir) && type(dir) == 0x1772) {
                    var p = dir + 8
                    val end = dir + 8 + len(dir)
                    while (p + 4 <= end) {
                        val v = i32(p)
                        val id = v and 0xFFFFF
                        val n = v ushr 20
                        p += 4
                        for (k in 0 until n) {
                            if (p + 4 > end) break
                            persist.putIfAbsent(id + k, i32(p))
                            p += 4
                        }
                    }
                }
                edit = i32(b + 8)
            }
            val at = persist[docRef] ?: return null
            return if (ok(at) && type(at) == 0x03E8) at else null
        }

        private fun readDocument(doc: Int) {
            child(doc, 0x03E9)?.let {
                val w = i32(it + 8)
                val h = i32(it + 12)
                if (w > 0 && h > 0) {
                    width = w / 8.0
                    height = h / 8.0
                }
            }
            child(doc, 0x03F2)?.let { env ->
                child(env, 0x07D5)?.let { fc ->
                    for (f in children(fc)) if (type(f) == 0x0FB7 && len(f) >= 68) {
                        val name = utf16(f + 8, 64).substringBefore('\u0000')
                        fontNames[inst(f)] = name
                        fontFamily[inst(f)] = when (u8(f + 8 + 67) and 0xF0) {
                            0x10 -> "serif"
                            0x30 -> "monospace"
                            0x40 -> "cursive"
                            0x20 -> "sans-serif"
                            else -> genericByName(name)
                        }
                    }
                }
                // The document's default text styles.
                val styles = HashMap<Int, Array<Array<IntArray?>>>()
                for (c in children(env)) when (type(c)) {
                    0x0FA3 -> styles[inst(c)] = masterStyle(c)
                    0x0FA4 -> styles[-2] = arrayOf(arrayOf(null, cf(c + 8, c + 8 + len(c)).first))
                    0x0FA5 -> styles[-3] = arrayOf(arrayOf(pf(c + 10, c + 8 + len(c)).first, null))
                }
                masterStyles[-1] = styles
            }
            // Pictures: the blip store's entries point into the Pictures stream.
            child(doc, 0x040B)?.let { group ->
                child(group, 0xF000)?.let { dgg ->
                    child(dgg, 0xF00B)?.let { defaults.putAll(opt(it)) }
                    child(dgg, 0xF001)?.let { store ->
                        for (e in children(store)) blips.add(if (type(e) == 0xF007 && len(e) >= 36) e else -1)
                    }
                }
            }
            // The slides' footer text, for footer placeholders.
            children(doc).firstOrNull { type(it) == 0x0FD9 && inst(it) == 3 }?.let { hf ->
                child(hf, 0x0FBA, 2)?.let { footer = utf16(it + 8, len(it)) }
            }
        }

        // ---------------------------------------------------------------- text

        /** Collects the text records of one text (header, characters, styles, ruler, fields) into [list]. */
        private fun readText(c: Int, list: MutableList<TextBlock>) {
            when (type(c)) {
                0x0F9F -> list.add(TextBlock(i32(c + 8)))
                0x0FA0 -> list.lastOrNull()?.text = utf16(c + 8, len(c))
                0x0FA8 -> list.lastOrNull()?.text = String(s, c + 8, len(c), Charsets.ISO_8859_1)
                0x0FA1 -> list.lastOrNull()?.let { styleRuns(c, it) }
                0x0FA6 -> list.lastOrNull()?.ruler = ruler(c)
                0x0FD8 -> list.lastOrNull()?.meta?.put(i32(c + 8), 1)
                0x0FF7, 0x0FF8, 0x1015 -> list.lastOrNull()?.meta?.put(i32(c + 8), 2)
                0x0FFA -> list.lastOrNull()?.meta?.put(i32(c + 8), 3)
                0x0FF9 -> list.lastOrNull()?.meta?.put(i32(c + 8), 2)
            }
        }

        /** StyleTextPropAtom: paragraph runs (count, level, PF) then character runs (count, CF). */
        private fun styleRuns(c: Int, t: TextBlock) {
            val end = c + 8 + len(c)
            val total = t.text.length + 1
            var p = c + 8
            var covered = 0
            while (covered < total && p + 6 <= end) {
                val count = i32(p)
                val level = u16(p + 4)
                val (props, next) = pf(p + 6, end)
                props[PF_LEVEL] = level
                props[PF_COUNT] = count
                t.paras.add(props)
                covered += count
                p = next
                if (count <= 0) break
            }
            covered = 0
            while (covered < total && p + 4 <= end) {
                val count = i32(p)
                val (props, next) = cf(p + 4, end)
                props[CF_COUNT] = count
                t.chars.add(props)
                covered += count
                p = next
                if (count <= 0) break
            }
        }

        /** A TextPFException at [o]: the properties set (unset ones are [UNSET]) and where it ends. */
        private fun pf(o: Int, end: Int): Pair<IntArray, Int> {
            val r = IntArray(PF_SIZE) { UNSET }
            if (o + 4 > end) return r to end
            val m = i32(o)
            var p = o + 4
            fun take(n: Int): Int {
                val v = if (n == 2) s16(p) else i32(p)
                p += n
                return v
            }
            if (m and 0xF != 0) {
                r[PF_BULLET_MASK] = m and 0xF
                r[PF_BULLET] = take(2)
            }
            if (m and 0x80 != 0) r[PF_BULLET_CHAR] = take(2) and 0xFFFF
            if (m and 0x10 != 0) r[PF_BULLET_FONT] = take(2) and 0xFFFF
            if (m and 0x40 != 0) r[PF_BULLET_SIZE] = take(2)
            if (m and 0x20 != 0) r[PF_BULLET_COLOR] = take(4)
            if (m and 0x800 != 0) r[PF_ALIGN] = take(2)
            if (m and 0x1000 != 0) r[PF_LINE] = take(2)
            if (m and 0x2000 != 0) r[PF_BEFORE] = take(2)
            if (m and 0x4000 != 0) r[PF_AFTER] = take(2)
            if (m and 0x100 != 0) r[PF_LEFT] = take(2)
            if (m and 0x400 != 0) r[PF_INDENT] = take(2)
            if (m and 0x8000 != 0) take(2)
            if (m and 0x100000 != 0) {
                val n = take(2) and 0xFFFF
                p += 4 * n
            }
            if (m and 0x10000 != 0) take(2)
            if (m and 0xE0000 != 0) take(2)
            if (m and 0x200000 != 0) take(2)
            return r to min(p, end)
        }

        /** A TextCFException at [o]: the properties set and where it ends. */
        private fun cf(o: Int, end: Int): Pair<IntArray, Int> {
            val r = IntArray(CF_SIZE) { UNSET }
            if (o + 4 > end) return r to end
            val m = i32(o)
            var p = o + 4
            fun take(n: Int): Int {
                val v = if (n == 2) s16(p) else i32(p)
                p += n
                return v
            }
            if (m and 0xFFFF != 0) {
                r[CF_STYLE_MASK] = m and 0xFFFF
                r[CF_STYLE] = take(2)
            }
            if (m and 0x10000 != 0) r[CF_FONT] = take(2) and 0xFFFF
            if (m and 0x200000 != 0) take(2)
            if (m and 0x400000 != 0) take(2)
            if (m and 0x800000 != 0) r[CF_SYMBOL] = take(2) and 0xFFFF
            if (m and 0x20000 != 0) r[CF_SIZE_PT] = take(2)
            if (m and 0x40000 != 0) r[CF_COLOR] = take(4)
            if (m and 0x80000 != 0) r[CF_POSITION] = take(2)
            return r to min(p, end)
        }

        /** TextRulerAtom: left margin and indent for each level (master units), or [UNSET]. */
        private fun ruler(c: Int): IntArray {
            val r = IntArray(10) { UNSET }
            val end = c + 8 + len(c)
            if (len(c) < 4) return r
            val m = i32(c + 8)
            var p = c + 12
            if (m and 2 != 0) p += 2
            if (m and 1 != 0) p += 2
            if (m and 4 != 0 && p + 2 <= end) p += 2 + 4 * u16(p)
            for (i in 0 until 5) {
                if (m and (1 shl (3 + i)) != 0 && p + 2 <= end) {
                    r[i] = s16(p)
                    p += 2
                }
                if (m and (1 shl (8 + i)) != 0 && p + 2 <= end) {
                    r[5 + i] = s16(p)
                    p += 2
                }
            }
            return r
        }

        /** TextMasterStyleAtom: for each level, its PF and CF. */
        private fun masterStyle(c: Int): Array<Array<IntArray?>> {
            val end = c + 8 + len(c)
            if (len(c) < 2) return emptyArray()
            val levels = u16(c + 8).coerceIn(0, 9)
            var p = c + 10
            return Array(levels) {
                if (inst(c) >= 5) p += 2
                val (pfp, a) = pf(p, end)
                val (cfp, b) = cf(a, end)
                p = b
                arrayOf<IntArray?>(pfp, cfp)
            }
        }

        // ---------------------------------------------------------------- slides

        private class Context(val master: Int, val scheme: IntArray, val styles: Map<Int, Array<Array<IntArray?>>>)

        private fun slide(at: Int, number: Int, listed: List<TextBlock>, notesById: Map<Int, Int>) {
            this.number = number
            val atom = child(at, 0x03EF)
            val masterId = atom?.let { i32(it + 8 + 12) } ?: 0
            val notesId = atom?.let { i32(it + 8 + 16) } ?: 0
            val flags = atom?.let { u16(it + 8 + 20) } ?: 7
            // A slide's master may be a title master, itself on a main master (which has the text styles).
            var master = masterById[masterId] ?: masterById.values.firstOrNull() ?: -1
            val chain = ArrayList<Int>()
            var guard = 0
            while (master >= 0 && ok(master) && guard++ < 4) {
                chain.add(0, master)
                if (type(master) == 0x03F8) break
                master = child(master, 0x03EF)?.let { masterById[i32(it + 8 + 12)] } ?: -1
            }
            val main = chain.firstOrNull { type(it) == 0x03F8 } ?: -1
            val styles = if (main >= 0) masterStyles.getOrPut(main) {
                val m = HashMap<Int, Array<Array<IntArray?>>>()
                for (c in children(main)) if (type(c) == 0x0FA3) m[inst(c)] = masterStyle(c)
                m
            } else emptyMap()
            val ownScheme = child(at, 0x07F0, 1)
            val schemeAt = if (flags and 2 != 0 || ownScheme == null) chain.lastOrNull()?.let { child(it, 0x07F0, 1) } ?: ownScheme else ownScheme
            val scheme = IntArray(8) { i -> schemeAt?.let { rgb(i32(it + 8 + 4 * i)) } ?: DEFAULT_SCHEME[i] }
            val ctx = Context(chain.lastOrNull() ?: -1, scheme, styles)

            out.append("<div class=\"oo-slide ").append(css.cls("g", "width:${pt(width)};height:${pt(height)}"))
            // The background: the slide's own, or its master's.
            val bgFrom = if (flags and 4 != 0) chain.lastOrNull() ?: at else at
            background(bgFrom, ctx)?.let { out.append(' ').append(it) }
            out.append("\" role=\"img\" aria-label=\"Slide ").append(number).append("\">")
            // The masters' own drawings (logos, lines, pictures), behind the slide's, but not their placeholders.
            if (flags and 1 != 0) for (m in chain) {
                for (shape in shapesOf(m, ctx, emptyList())) if (shape.placeholder < 0) draw(shape, ctx)
            }
            for (shape in shapesOf(at, ctx, listed)) draw(shape, ctx)
            out.append("</div>")
            notes(notesById[notesId], ctx)
        }

        private fun notes(at: Int?, ctx: Context) {
            if (at == null || !ok(at) || type(at) != 0x03F0) return
            val texts = shapesOf(at, ctx, listedTexts[at].orEmpty()).filter { it.placeholder == 0x0C || it.placeholder == 0x06 }
                .mapNotNull { it.text?.text }.map { it.trim() }.filter { it.isNotEmpty() }
            if (texts.isEmpty()) return
            out.append("<details class=\"oo-notes ").append(css.cls("n", "width:${pt(width)}")).append("\"><summary>Notes</summary>")
            for (t in texts) for (line in t.split('\r')) {
                out.append("<p>")
                escText(line.replace('\u000B', '\n'), out)
                out.append("</p>")
            }
            out.append("</details>")
        }

        /** A slide's (or master's) shapes, with positions on the slide in pt and their text. */
        private fun shapesOf(at: Int, ctx: Context, listed: List<TextBlock>): List<Shape> {
            val drawing = child(at, 0x040C) ?: return emptyList()
            val dg = child(drawing, 0xF002) ?: return emptyList()
            val list = ArrayList<Shape>()
            child(dg, 0xF003)?.let { group(it, doubleArrayOf(0.0, 0.0, 0.125, 0.125), list, true) }
            val masterMap = if (ctx.master >= 0 && ctx.master != at) masterShapes.getOrPut(ctx.master) {
                shapesOf(ctx.master, Context(-1, ctx.scheme, ctx.styles), emptyList()).associateBy { it.spid }
            } else emptyMap()
            for (sh in list) {
                if (sh.textRef >= 0) sh.text = listed.getOrNull(sh.textRef)
                // Placeholders take what they don't set (their place, say) from the master's.
                if (sh.box == null) {
                    val m = sh.props[0x0301]?.let { masterMap[it] } ?: masterMap.values.firstOrNull { samePlace(it.placeholder, sh.placeholder) }
                    sh.box = m?.box
                }
            }
            return list
        }

        private fun samePlace(master: Int, slide: Int): Boolean = when (slide) {
            0x0D, 0x0F, 0x11 -> master == 0x01 || master == 0x03
            0x0E, 0x10, 0x12, 0x13 -> master == 0x02 || master == 0x04
            0x0C -> master == 0x06
            else -> false
        }

        /**
         * The shapes in group [g], placed on the slide by [map] (x0, y0 in pt, and the scales from
         * the group's coordinates); groups inside are flattened into [list].
         */
        private fun group(g: Int, map: DoubleArray, list: MutableList<Shape>, top: Boolean) {
            val items = children(g)
            for ((i, c) in items.withIndex()) {
                when (type(c)) {
                    0xF003 -> {
                        val head = children(c).firstOrNull() ?: continue
                        val anchor = anchor(head, map, top = false) ?: continue
                        val fspgr = child(head, 0xF009) ?: continue
                        val gl = i32(fspgr + 8).toDouble()
                        val gt = i32(fspgr + 12).toDouble()
                        val gw = (i32(fspgr + 16) - gl).takeIf { it != 0.0 } ?: 1.0
                        val gh = (i32(fspgr + 20) - gt).takeIf { it != 0.0 } ?: 1.0
                        val sx = (anchor[2] - anchor[0]) / gw
                        val sy = (anchor[3] - anchor[1]) / gh
                        group(c, doubleArrayOf(anchor[0] - gl * sx, anchor[1] - gt * sy, sx, sy), list, false)
                    }
                    0xF004 -> {
                        // The first shape of a group is the group itself.
                        if (i == 0) continue
                        try {
                            shape(c, map, top)?.let { list.add(it) }
                        } catch (e: RuntimeException) {
                        }
                    }
                }
            }
        }

        /** A shape's box in pt (left, top, right, bottom): its client anchor, or child anchor in its group. */
        private fun anchor(sp: Int, map: DoubleArray, top: Boolean): DoubleArray? {
            val client = child(sp, 0xF010)
            val ch = child(sp, 0xF00F)
            val r: DoubleArray = when {
                ch != null && len(ch) >= 16 -> doubleArrayOf(i32(ch + 8).toDouble(), i32(ch + 12).toDouble(), i32(ch + 16).toDouble(), i32(ch + 20).toDouble())
                client != null && len(client) >= 16 -> doubleArrayOf(i32(client + 12).toDouble(), i32(client + 8).toDouble(), i32(client + 16).toDouble(), i32(client + 20).toDouble())
                client != null && len(client) >= 8 -> doubleArrayOf(s16(client + 10).toDouble(), s16(client + 8).toDouble(), s16(client + 12).toDouble(), s16(client + 14).toDouble())
                else -> return null
            }
            // Top-level anchors are in master units (576 per inch), children in their group's units.
            val useMap = if (ch != null && !top) map else doubleArrayOf(0.0, 0.0, 0.125, 0.125)
            return doubleArrayOf(useMap[0] + r[0] * useMap[2], useMap[1] + r[1] * useMap[3], useMap[0] + r[2] * useMap[2], useMap[1] + r[3] * useMap[3])
        }

        private fun shape(sp: Int, map: DoubleArray, top: Boolean): Shape? {
            val fsp = child(sp, 0xF00A) ?: return null
            val flags = i32(fsp + 12)
            if (flags and 0x8 != 0) return null
            val props = HashMap<Int, Int>()
            for (c in children(sp)) if (type(c) == 0xF00B || type(c) == 0xF121 || type(c) == 0xF122) props.putAll(opt(c))
            val shape = Shape(inst(fsp), flags, i32(fsp + 8), props)
            var box = anchor(sp, map, top)
            // PowerPoint stores the box of a shape turned by about 90° with width and height swapped.
            val rot = props[0x0004]?.let { it / 65536.0 } ?: 0.0
            val norm = ((rot % 360) + 360) % 360
            if (box != null && (norm in 45.0..135.0 || norm in 225.0..315.0)) {
                val cx = (box[0] + box[2]) / 2
                val cy = (box[1] + box[3]) / 2
                val hw = (box[3] - box[1]) / 2
                val hh = (box[2] - box[0]) / 2
                box = doubleArrayOf(cx - hw, cy - hh, cx + hw, cy + hh)
            }
            shape.box = box
            child(sp, 0xF011)?.let { cd -> child(cd, 0x0BC3)?.let { shape.placeholder = u8(it + 8 + 4) } }
            child(sp, 0xF00D)?.let { tb ->
                val texts = ArrayList<TextBlock>()
                for (c in children(tb)) {
                    if (type(c) == 0x0F9E) shape.textRef = i32(c + 8) else readText(c, texts)
                }
                shape.text = texts.firstOrNull()
            }
            return shape
        }

        /** An OfficeArtFOPT's simple properties (id → value). */
        private fun opt(o: Int): Map<Int, Int> {
            val m = HashMap<Int, Int>()
            val n = inst(o)
            var p = o + 8
            val end = o + 8 + len(o)
            for (i in 0 until n) {
                if (p + 6 > end) break
                m[u16(p) and 0x3FFF] = i32(p + 2)
                p += 6
            }
            return m
        }

        // ---------------------------------------------------------------- drawing

        private fun prop(sh: Shape, id: Int): Int? = sh.props[id]

        /** Bit [bit] of boolean property group [id] if [props] set it (its "use" flag is bit + 16). */
        private fun own(props: Map<Int, Int>, id: Int, bit: Int): Boolean? {
            val v = props[id] ?: return null
            return if (v and (1 shl (bit + 16)) != 0) v and (1 shl bit) != 0 else null
        }

        /** The same from the shape, else the drawing's defaults, else [default]. */
        private fun flag(props: Map<Int, Int>, id: Int, bit: Int, default: Boolean): Boolean =
            own(props, id, bit) ?: own(defaults, id, bit) ?: default

        /** An OfficeArt colour (RGB, or an index into the slide's colour scheme) as 0xRRGGBB, -1 if unknown. */
        private fun artColor(v: Int?, ctx: Context): Int {
            if (v == null) return -1
            val f = v ushr 24
            return when {
                f and 0x08 != 0 -> ctx.scheme.getOrElse(v and 0xFF) { -1 }
                f and 0x10 != 0 -> -1
                else -> rgb(v)
            }
        }

        /** A ColorIndexStruct (text colours): RGB, or a scheme colour. */
        private fun textColor(v: Int, ctx: Context): Int {
            val index = v ushr 24
            return when {
                index == 0xFE -> rgb(v)
                index in 0..7 -> ctx.scheme[index]
                else -> -1
            }
        }

        private fun rgb(v: Int) = (v and 0xFF shl 16) or (v and 0xFF00) or (v ushr 16 and 0xFF)

        /** The fill of a background shape as CSS class, or null for white. */
        private fun background(at: Int, ctx: Context): String? {
            val drawing = child(at, 0x040C) ?: return null
            val dg = child(drawing, 0xF002) ?: return null
            for (sp in children(dg)) {
                if (type(sp) != 0xF004) continue
                val fsp = child(sp, 0xF00A) ?: continue
                if (i32(fsp + 12) and 0x400 == 0) continue
                val props = HashMap<Int, Int>()
                for (c in children(sp)) if (type(c) == 0xF00B || type(c) == 0xF121 || type(c) == 0xF122) props.putAll(opt(c))
                val fill = fill(props, ctx, true) ?: return null
                return (if (fill.second >= 0) "oo-i${fill.second} " else "") + css.cls("b", fill.first)
            }
            return null
        }

        /**
         * A fill as CSS (a colour, a two-colour gradient, or a picture stretched or tiled) and the
         * picture's index (-1 for none); null for no fill.
         */
        private fun fill(props: Map<Int, Int>, ctx: Context, background: Boolean): Pair<String, Int>? {
            if (!flag(props, 0x01BF, 4, true)) return null
            val kind = props[0x0180] ?: 0
            val color = artColor(props[0x0181] ?: defaults[0x0181], ctx).let { if (it == -1) (if (background) 0xFFFFFF else ctx.scheme[4]) else it }
            when (kind) {
                2, 3 -> {
                    val id = props[0x0186]?.let { picture(it) } ?: -1
                    if (id >= 0) {
                        val size = if (kind == 2) "background-repeat:repeat;background-size:auto;" else "background-repeat:no-repeat;background-size:100% 100%;"
                        return "background-color:${hex(color)};$size" to id
                    }
                }
                in 4..8 -> {
                    val back = artColor(props[0x0183] ?: defaults[0x0183], ctx).let { if (it == -1) 0xFFFFFF else it }
                    val angle = (props[0x018B] ?: 0) / 65536.0
                    return "background:linear-gradient(${num(180 - angle)}deg,${hex(color)},${hex(back)});" to -1
                }
            }
            val opacity = props[0x0182]?.let { it / 65536.0 }
            val css = if (opacity != null && opacity < 1) {
                "background-color:rgba(${color shr 16 and 0xFF},${color shr 8 and 0xFF},${color and 0xFF},${num(opacity)});"
            } else "background-color:${hex(color)};"
            return css to -1
        }

        private fun draw(sh: Shape, ctx: Context) {
            val box = sh.box ?: return
            val l = box[0]
            val t = box[1]
            val w = box[2] - box[0]
            val h = box[3] - box[1]
            val rot = prop(sh, 0x0004)?.let { it / 65536.0 } ?: 0.0
            val flipH = sh.flags and 0x40 != 0
            val flipV = sh.flags and 0x80 != 0
            val spt = sh.spt
            // Lines and connectors.
            if (spt == 20 || spt in 32..40) {
                if (!flag(sh.props, 0x01FF, 3, true)) return
                val x1 = if (flipH) l + w else l
                val y1 = if (flipV) t + h else t
                val x2 = if (flipH) l else l + w
                val y2 = if (flipV) t else t + h
                val length = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
                val angle = Math.toDegrees(atan2(y2 - y1, x2 - x1)) + rot
                val color = artColor(prop(sh, 0x01C0) ?: defaults[0x01C0], ctx).let { if (it == -1) ctx.scheme[1] else it }
                val lw = max(0.5, (prop(sh, 0x01CB) ?: 9525) / 12700.0)
                val dash = if ((prop(sh, 0x01CE) ?: 0) != 0) "dashed" else "solid"
                out.append("<div class=\"oo-line ").append(css.cls("l", "left:${pt(x1)};top:${pt(y1 - lw / 2)};width:${pt(length)};border-top:${pt(lw)} $dash ${hex(color)};transform:rotate(${num(angle)}deg)")).append("\"></div>")
                return
            }
            if (w <= 0 || h <= 0) return
            val geom = StringBuilder("left:${pt(l)};top:${pt(t)};width:${pt(w)};height:${pt(h)};")
            val transform = StringBuilder()
            if (rot != 0.0) transform.append("rotate(").append(num(rot)).append("deg) ")
            val pib = prop(sh, 0x0104)
            if (pib != null && pib > 0) {
                // A picture (or an embedded object's preview picture).
                if (flipH) transform.append("scaleX(-1) ")
                if (flipV) transform.append("scaleY(-1) ")
                if (transform.isNotEmpty()) geom.append("transform:").append(transform.trim()).append(';')
                val id = picture(pib)
                val crop = intArrayOf(prop(sh, 0x0102) ?: 0, prop(sh, 0x0100) ?: 0, prop(sh, 0x0103) ?: 0, prop(sh, 0x0101) ?: 0).map { it.toDouble() / 65536 }
                if (id >= 0 && crop.all { it >= 0 && it < 1 } && crop.any { it > 0 }) {
                    val vw = max(0.01, 1 - crop[0] - crop[2])
                    val vh = max(0.01, 1 - crop[1] - crop[3])
                    geom.append("background-size:${num(100 / vw)}% ${num(100 / vh)}%;")
                    val px = if (crop[0] + crop[2] > 0) 100 * crop[0] / (crop[0] + crop[2]) else 0.0
                    val py = if (crop[1] + crop[3] > 0) 100 * crop[1] / (crop[1] + crop[3]) else 0.0
                    geom.append("background-position:${num(px)}% ${num(py)}%;")
                }
                out.append("<div class=\"oo-img ").append(if (id >= 0) "oo-i$id " else "oo-none ").append(css.cls("p", geom)).append("\" role=\"img\"></div>")
                if (sh.text == null) return
                geom.setLength(0)
                geom.append("left:${pt(l)};top:${pt(t)};width:${pt(w)};height:${pt(h)};")
                transform.setLength(0)
                if (rot != 0.0) transform.append("rotate(").append(num(rot)).append("deg)")
            }
            if (transform.isNotEmpty()) geom.append("transform:").append(transform.trim()).append(';')
            // Fill and outline, for the shapes drawn as boxes; other shapes keep only their text.
            val boxLike = spt in BOX_SHAPES
            val text = sh.text?.takeIf { it.text.isNotEmpty() }
            var fillPicture = -1
            var drawn = false
            if (boxLike && pib == null) {
                // Placeholders and text boxes are unfilled and unlined unless they say otherwise.
                val plain = sh.placeholder >= 0 || spt == 202
                if (!plain || own(sh.props, 0x01BF, 4) == true) fill(sh.props, ctx, false)?.let {
                    geom.append(it.first)
                    fillPicture = it.second
                    drawn = true
                }
                if (if (plain) own(sh.props, 0x01FF, 3) == true else flag(sh.props, 0x01FF, 3, true)) {
                    val color = artColor(prop(sh, 0x01C0) ?: defaults[0x01C0], ctx).let { if (it == -1) ctx.scheme[1] else it }
                    val lw = max(0.5, (prop(sh, 0x01CB) ?: 9525) / 12700.0)
                    geom.append("border:${pt(lw)} ${if ((prop(sh, 0x01CE) ?: 0) != 0) "dashed" else "solid"} ${hex(color)};")
                    drawn = true
                }
            } else if (pib == null && OUTLINES.containsKey(spt)) {
                // Common figures (triangles, arrows, stars…): their fill cut to their outline, without
                // the outline itself (a clipped border would be cut away). Text goes on top, unclipped.
                fill(sh.props, ctx, false)?.let {
                    if (it.second >= 0) return@let
                    val shapeCss = StringBuilder("left:${pt(l)};top:${pt(t)};width:${pt(w)};height:${pt(h)};")
                    shapeCss.append(it.first).append("clip-path:polygon(").append(OUTLINES[spt]).append(");")
                    val turn = StringBuilder()
                    if (rot != 0.0) turn.append("rotate(").append(num(rot)).append("deg) ")
                    if (flipH) turn.append("scaleX(-1) ")
                    if (flipV) turn.append("scaleY(-1) ")
                    if (turn.isNotEmpty()) shapeCss.append("transform:").append(turn.trim()).append(';')
                    out.append("<div class=\"oo-img ").append(css.cls("f", shapeCss)).append("\"></div>")
                }
            } else if (pib == null && spt !in BOX_SHAPES) {
                // Callouts, freeforms, other figures: not drawn (a box would misrepresent them).
                if (spt != 0 || sh.props.containsKey(0x0145)) skippedShapes++
            }
            if (text == null && !drawn) return
            // Text insets (EMU) and where the text sits in the box.
            val insetLeft = (prop(sh, 0x0081) ?: 91440) / 12700.0
            val insetTop = (prop(sh, 0x0082) ?: 45720) / 12700.0
            val insetRight = (prop(sh, 0x0083) ?: 91440) / 12700.0
            val insetBottom = (prop(sh, 0x0084) ?: 45720) / 12700.0
            geom.append("padding:${pt(insetTop)} ${pt(insetRight)} ${pt(insetBottom)} ${pt(insetLeft)};")
            val cls = StringBuilder("oo-sp ")
            if (fillPicture >= 0) cls.append("oo-i").append(fillPicture).append(' ')
            when (prop(sh, 0x0087) ?: if (text?.type == 0 || text?.type == 6) 1 else 0) {
                1, 4, 7 -> cls.append("oo-mid ")
                2, 5, 8, 9 -> cls.append("oo-bot ")
            }
            if (prop(sh, 0x0085) == 2) cls.append("oo-nowrap ")
            if (boxLike && (spt == 3 || spt == 120)) cls.append("oo-ell ")
            if (boxLike && (spt == 2 || spt == 176)) cls.append("oo-round ")
            cls.append(css.cls("s", geom))
            out.append("<div class=\"").append(cls).append("\"><div>")
            if (text != null) paragraphs(text, ctx)
            out.append("</div></div>")
        }

        // ---------------------------------------------------------------- text out

        /** The value of a PF or CF property for [level] of text [type], from the masters and defaults. */
        private fun inherited(ctx: Context, type: Int, level: Int, kind: Int, index: Int): Int {
            val chain = when (type) {
                5, 7, 8 -> intArrayOf(type, 1)
                6 -> intArrayOf(6, 0)
                else -> intArrayOf(type)
            }
            for (t in chain) {
                val levels = ctx.styles[t] ?: continue
                val lv = levels.getOrNull(level) ?: levels.getOrNull(0) ?: continue
                lv[kind]?.get(index)?.takeIf { it != UNSET }?.let { return it }
            }
            val env = masterStyles[-1] ?: return UNSET
            env[type]?.let { lv -> (lv.getOrNull(level) ?: lv.getOrNull(0))?.get(kind)?.get(index)?.takeIf { it != UNSET }?.let { return it } }
            env[4]?.let { lv -> (lv.getOrNull(level) ?: lv.getOrNull(0))?.get(kind)?.get(index)?.takeIf { it != UNSET }?.let { return it } }
            val base = if (kind == 0) env[-3] else env[-2]
            return base?.getOrNull(0)?.get(kind)?.get(index) ?: UNSET
        }

        private fun paragraphs(t: TextBlock, ctx: Context) {
            // Fields ("*" in the text): the slide number and the footer, filled in as the runs are
            // written (the runs count characters); dates are left out (PowerPoint shows today's).
            val text = StringBuilder(t.text)
            for ((pos, kind) in t.meta) if (pos in text.indices && text[pos] == '*') text.setCharAt(pos, FIELD_MARKS[kind])
            var all = text.toString()
            if (all.endsWith("\r")) all = all.dropLast(1)
            var start = 0
            var paraRun = 0
            var paraRunEnd = t.paras.getOrNull(0)?.get(PF_COUNT) ?: Int.MAX_VALUE
            var charRun = 0
            var charRunEnd = t.chars.getOrNull(0)?.get(CF_COUNT) ?: Int.MAX_VALUE
            for (line in all.split('\r')) {
                while (start >= paraRunEnd && paraRun + 1 < t.paras.size) {
                    paraRun++
                    paraRunEnd += t.paras[paraRun][PF_COUNT]
                }
                val pf = t.paras.getOrNull(paraRun)
                val level = pf?.get(PF_LEVEL)?.coerceIn(0, 4) ?: 0
                fun p(i: Int): Int = pf?.get(i)?.takeIf { it != UNSET } ?: inherited(ctx, t.type, level, 0, i)
                // The first run's character properties set the paragraph's size (for spacing and empty lines).
                while (start >= charRunEnd && charRun + 1 < t.chars.size) {
                    charRun++
                    charRunEnd += t.chars[charRun][CF_COUNT]
                }
                val firstCf = t.chars.getOrNull(charRun)
                val size = firstCf?.get(CF_SIZE_PT)?.takeIf { it != UNSET } ?: inherited(ctx, t.type, level, 1, CF_SIZE_PT).let { if (it == UNSET) 18 else it }
                val st = StringBuilder("font-size:").append(size).append("pt;")
                var left = t.ruler?.get(level)?.takeIf { it != UNSET } ?: p(PF_LEFT)
                var indent = t.ruler?.get(5 + level)?.takeIf { it != UNSET } ?: p(PF_INDENT)
                pf?.get(PF_LEFT)?.takeIf { it != UNSET }?.let { left = it }
                pf?.get(PF_INDENT)?.takeIf { it != UNSET }?.let { indent = it }
                val leftPt = if (left == UNSET) 0.0 else left / 8.0
                val indentPt = if (indent == UNSET) 0.0 else indent / 8.0
                if (leftPt != 0.0) st.append("padding-left:").append(pt(leftPt)).append(';')
                if (indentPt - leftPt != 0.0) st.append("text-indent:").append(pt(indentPt - leftPt)).append(';')
                when (p(PF_ALIGN)) {
                    1 -> st.append("text-align:center;")
                    2 -> st.append("text-align:right;")
                    3, 4, 6 -> st.append("text-align:justify;")
                }
                p(PF_LINE).takeIf { it != UNSET && it != 100 }?.let { st.append("line-height:").append(if (it > 0) num(it / 100.0 * 1.2) else pt(-it / 8.0)).append(';') }
                p(PF_BEFORE).takeIf { it != UNSET && it != 0 }?.let { st.append("margin-top:").append(if (it > 0) num(it / 100.0 * 1.2) + "em" else pt(-it / 8.0)).append(';') }
                p(PF_AFTER).takeIf { it != UNSET && it != 0 }?.let { st.append("margin-bottom:").append(if (it > 0) num(it / 100.0 * 1.2) + "em" else pt(-it / 8.0)).append(';') }
                out.append("<p class=\"").append(css.cls("q", st)).append("\">")
                // The bullet.
                val bulletBits = p(PF_BULLET)
                if (bulletBits != UNSET && bulletBits and 1 != 0 && line.isNotEmpty()) {
                    val bs = StringBuilder()
                    val ch = p(PF_BULLET_CHAR).let { if (it == UNSET || it == 0) '•'.code else it }
                    var font = if (bulletBits and 2 != 0) p(PF_BULLET_FONT) else UNSET
                    if (font == UNSET) font = firstCf?.get(CF_FONT)?.takeIf { it != UNSET } ?: inherited(ctx, t.type, level, 1, CF_FONT)
                    val fontName = fontNames[font]
                    val kind = symbolic(fontName)
                    val shown = mapSymbols(ch.toChar().toString(), kind, true)
                    if (kind == 0 && fontName != null) bs.append("font-family:").append(family(font)).append(';')
                    val bsize = if (bulletBits and 8 != 0) p(PF_BULLET_SIZE) else UNSET
                    if (bsize != UNSET && bsize in 25..400) bs.append("font-size:").append(num(size * bsize / 100.0)).append("pt;")
                    if (bsize != UNSET && bsize < 0) bs.append("font-size:").append(pt(-bsize.toDouble())).append(';')
                    val bc = if (bulletBits and 4 != 0) p(PF_BULLET_COLOR) else UNSET
                    val color = if (bc != UNSET) textColor(bc, ctx) else charColor(firstCf, ctx, t.type, level)
                    if (color >= 0) bs.append("color:").append(hex(color)).append(';')
                    val gap = leftPt - indentPt
                    if (gap > 0) bs.append("min-width:").append(pt(gap)).append(';')
                    out.append("<span class=\"oo-bullet ").append(css.cls("u", bs)).append("\">")
                    escText(shown, out)
                    out.append("</span>")
                }
                if (line.isEmpty()) out.append("<br>")
                // Character runs.
                var i = 0
                while (i < line.length) {
                    val at = start + i
                    while (at >= charRunEnd && charRun + 1 < t.chars.size) {
                        charRun++
                        charRunEnd += t.chars[charRun][CF_COUNT]
                    }
                    val cfp = t.chars.getOrNull(charRun)
                    val n = min(line.length - i, max(1, charRunEnd - at))
                    run(line.substring(i, i + n), cfp, ctx, t.type, level)
                    i += n
                }
                out.append("</p>")
                start += line.length + 1
            }
        }

        private fun charColor(cfp: IntArray?, ctx: Context, type: Int, level: Int): Int {
            val c = cfp?.get(CF_COLOR)?.takeIf { it != UNSET } ?: inherited(ctx, type, level, 1, CF_COLOR)
            return if (c == UNSET) ctx.scheme[1] else textColor(c, ctx).let { if (it == -1) ctx.scheme[1] else it }
        }

        private fun family(font: Int): String {
            val name = fontNames[font] ?: return "sans-serif"
            return "'" + cssFontName(name) + "'," + (if (narrow(name)) "'Roboto Condensed'," else "") + (fontFamily[font] ?: genericByName(name))
        }

        private fun run(text: String, cfp: IntArray?, ctx: Context, type: Int, level: Int) {
            fun c(i: Int): Int = cfp?.get(i)?.takeIf { it != UNSET } ?: inherited(ctx, type, level, 1, i)
            // Bold, italic, underline and shadow are bits of one field, each set or inherited on its own.
            var style = 0
            for (bit in intArrayOf(0, 1, 2, 4)) {
                val mask = 1 shl bit
                val own = cfp?.get(CF_STYLE_MASK)?.takeIf { it != UNSET && it and mask != 0 }?.let { cfp[CF_STYLE] and mask }
                style = style or (own ?: inheritedBit(ctx, type, level, mask))
            }
            val st = StringBuilder()
            val font = c(CF_FONT)
            val name = fontNames[font]
            val kind = symbolic(name)
            val shown = mapSymbols(text, kind, false)
            if (name != null && kind == 0) {
                st.append("font-family:").append(family(font)).append(';')
                if (style and 1 == 0) fontWeight(name)?.let { st.append("font-weight:").append(it).append(';') }
            }
            var size = c(CF_SIZE_PT).let { if (it == UNSET || it <= 0) 18.0 else it.toDouble() }
            val position = c(CF_POSITION)
            if (position != UNSET && position != 0) {
                size *= 0.67
                st.append("vertical-align:").append(if (position > 0) "super" else "sub").append(';')
            }
            st.append("font-size:").append(num(size)).append("pt;")
            if (style and 1 != 0) st.append("font-weight:bold;")
            if (style and 2 != 0) st.append("font-style:italic;")
            if (style and 4 != 0) st.append("text-decoration:underline;")
            if (style and 16 != 0) st.append("text-shadow:.06em .06em .04em rgba(0,0,0,.4);")
            val color = charColor(cfp, ctx, type, level)
            if (color >= 0) st.append("color:").append(hex(color)).append(';')
            out.append("<span class=\"").append(css.cls("r", st)).append("\">")
            var shownText = shown.replace('\u000B', '\n')
            if (shownText.any { it in FIELD_MARKS }) {
                shownText = shownText.replace(FIELD_MARKS[1].toString(), number.toString())
                    .replace(FIELD_MARKS[3].toString(), footer.orEmpty())
                    .replace(FIELD_MARKS[2].toString(), "")
            }
            escText(shownText, out)
            out.append("</span>")
        }

        private fun inheritedBit(ctx: Context, type: Int, level: Int, mask: Int): Int {
            val chain = when (type) {
                5, 7, 8 -> intArrayOf(type, 1)
                6 -> intArrayOf(6, 0)
                else -> intArrayOf(type)
            }
            val sources = ArrayList<IntArray?>()
            for (t in chain) ctx.styles[t]?.let { sources.add((it.getOrNull(level) ?: it.getOrNull(0))?.get(1)) }
            masterStyles[-1]?.let { env ->
                env[type]?.let { sources.add((it.getOrNull(level) ?: it.getOrNull(0))?.get(1)) }
                env[4]?.let { sources.add((it.getOrNull(level) ?: it.getOrNull(0))?.get(1)) }
                env[-2]?.let { sources.add(it.getOrNull(0)?.get(1)) }
            }
            for (cfp in sources) {
                if (cfp == null) continue
                val m = cfp[CF_STYLE_MASK]
                if (m != UNSET && m and mask != 0) return cfp[CF_STYLE] and mask
            }
            return 0
        }

        // ---------------------------------------------------------------- pictures

        /** The picture for blip store entry [pib] (1-based): its index in [pics], or -1. */
        private fun picture(pib: Int): Int {
            blipIds[pib]?.let { return it }
            val id = try {
                loadPicture(pib)
            } catch (e: Exception) {
                -1
            }
            blipIds[pib] = id
            return id
        }

        private fun loadPicture(pib: Int): Int {
            val fbse = blips.getOrNull(pib - 1) ?: return -1
            if (fbse < 0) return -1
            val blipType = u8(fbse + 8)
            if (blipType !in intArrayOf(5, 6, 7, 0x12)) {
                // EMF, WMF, PICT, TIFF: nothing on the phone draws them.
                pics.unsupported++
                return -1
            }
            val size = i32(fbse + 8 + 20)
            val delay = i32(fbse + 8 + 28)
            val nameLen = u8(fbse + 8 + 33)
            val record: ByteArray = if (len(fbse) > 36 + nameLen) {
                // The picture is inside the store entry.
                val o = fbse + 8 + 36 + nameLen
                s.copyOfRange(o, min(s.size, o + 8 + len(o)))
            } else {
                if (size <= 8 || delay < 0 || !root.hasEntry("Pictures")) return -1
                root.createDocumentInputStream("Pictures").use { input ->
                    var skip = delay.toLong()
                    while (skip > 0) {
                        val n = input.skip(skip)
                        if (n <= 0) return -1
                        skip -= n
                    }
                    // The record's own header says how long it is.
                    val head = ByteArray(8)
                    if (readFully(input, head) < 8) return -1
                    val length = LittleEndian.getInt(head, 4)
                    if (length <= 0 || length > MAX_FILE) return -1
                    val body = ByteArray(length)
                    if (readFully(input, body) < length) return -1
                    head + body
                }
            }
            if (record.size < 8) return -1
            val inst = LittleEndian.getUShort(record, 0) ushr 4
            // The picture's ids (one or two), a tag byte, then the image.
            val start = 8 + 16 * (if (inst and 1 == 1) 2 else 1) + 1
            if (start >= record.size) return -1
            val data = record.copyOfRange(start, record.size)
            val mime = imageMime(data)
            return when {
                mime != null -> pics.add(data, mime)
                blipType == 7 -> dibToBmp(data)?.let { pics.add(it, "image/bmp") } ?: -1
                else -> {
                    pics.unsupported++
                    -1
                }
            }
        }
    }

    /** Stand-ins for slide fields while the text is laid out: [1] slide number, [2] a date, [3] the footer. */
    private val FIELD_MARKS = String(CharArray(4) { (0xE000 + it).toChar() })

    /** Outlines (CSS polygons) of common figures by shape type, at their default proportions. */
    private val OUTLINES = mapOf(
        4 to "50% 0,100% 50%,50% 100%,0 50%",
        5 to "50% 0,100% 100%,0 100%",
        6 to "0 0,100% 100%,0 100%",
        7 to "25% 0,100% 0,75% 100%,0 100%",
        9 to "25% 0,75% 0,100% 50%,75% 100%,25% 100%,0 50%",
        10 to "29.3% 0,70.7% 0,100% 29.3%,100% 70.7%,70.7% 100%,29.3% 100%,0 70.7%,0 29.3%",
        11 to "25% 0,75% 0,75% 25%,100% 25%,100% 75%,75% 75%,75% 100%,25% 100%,25% 75%,0 75%,0 25%,25% 25%",
        12 to "50% 0,61% 35%,98% 35%,68% 57%,79% 91%,50% 70%,21% 91%,32% 57%,2% 35%,39% 35%",
        13 to "0 25%,75% 25%,75% 0,100% 50%,75% 100%,75% 75%,0 75%",
        15 to "0 0,75% 0,100% 50%,75% 100%,0 100%",
        55 to "0 0,75% 0,100% 50%,75% 100%,0 100%,25% 50%",
        56 to "50% 0,100% 38%,82% 100%,18% 100%,0 38%",
        66 to "100% 25%,25% 25%,25% 0,0 50%,25% 100%,25% 75%,100% 75%",
        67 to "25% 0,75% 0,75% 75%,100% 75%,50% 100%,0 75%,25% 75%",
        68 to "25% 100%,75% 100%,75% 25%,100% 25%,50% 0,0 25%,25% 25%",
        69 to "0 50%,20% 0,20% 25%,80% 25%,80% 0,100% 50%,80% 100%,80% 75%,20% 75%,20% 100%",
    )

    /** Shapes drawn as a box (with fill and outline): rectangles, rounded ones, ellipses, text boxes, pictures. */
    private val BOX_SHAPES = setOf(1, 2, 3, 75, 109, 120, 176, 202)

    /** PowerPoint's default colour scheme (background, text, shadow, title, fill, accents). */
    private val DEFAULT_SCHEME = intArrayOf(0xFFFFFF, 0x000000, 0x808080, 0x000000, 0xBBE0E3, 0x333399, 0x009999, 0x99CC00)

    private const val UNSET = Int.MIN_VALUE

    // Paragraph properties (PF), by index.
    private const val PF_BULLET_MASK = 0
    private const val PF_BULLET = 1
    private const val PF_BULLET_CHAR = 2
    private const val PF_BULLET_FONT = 3
    private const val PF_BULLET_SIZE = 4
    private const val PF_BULLET_COLOR = 5
    private const val PF_ALIGN = 6
    private const val PF_LINE = 7
    private const val PF_BEFORE = 8
    private const val PF_AFTER = 9
    private const val PF_LEFT = 10
    private const val PF_INDENT = 11
    private const val PF_LEVEL = 12
    private const val PF_COUNT = 13
    private const val PF_SIZE = 14

    // Character properties (CF), by index.
    private const val CF_STYLE_MASK = 0
    private const val CF_STYLE = 1
    private const val CF_FONT = 2
    private const val CF_SYMBOL = 3
    private const val CF_SIZE_PT = 4
    private const val CF_COLOR = 5
    private const val CF_POSITION = 6
    private const val CF_COUNT = 7
    private const val CF_SIZE = 8
}
