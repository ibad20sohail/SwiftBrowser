package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import com.swiftbrowser.files.FileOps
import com.swiftbrowser.files.PlainAccess
import com.swiftbrowser.files.Transfers
import com.swiftbrowser.files.Volumes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Editing a picture (2.9): turning it a quarter at a time and cutting it down to a part, freely or
 * to a common shape (square, 4:3, 16:9), on a black screen over the viewer. What the screen shows
 * is the small picture the viewer already has; [PictureEdit] then makes the result from the full
 * picture, so nothing is lost to the screen's size.
 */
class PictureEditor(
    private val a: Activity,
    preview: Bitmap,
    private val onSave: (turns: Int, crop: RectF) -> Unit,
    private val onClose: () -> Unit,
) {
    private val crop = CropView(a)
    private val save: TextView
    private val chips = ArrayList<TextView>()

    /** The shape chosen: an index of [SHAPES]. */
    private var shape = 0
    val view: View

    init {
        crop.setPicture(preview)
        val white = Color.WHITE
        save = TextView(a).apply {
            text = "Save"
            Ui.style(this, Ui.Type.LABEL, Ui.onAccent(a))
            gravity = Gravity.CENTER
            minWidth = Ui.dp(a, 76)
            setPadding(Ui.dp(a, 18), 0, Ui.dp(a, 18), 0)
            background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), Ui.shape(a, Ui.accent(a), 20), null)
            setOnClickListener {
                if (crop.changed) onSave(crop.turns, RectF(crop.crop)) else Messages.show(a, "Turn or cut the picture first")
            }
        }
        val top = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 12), 0)
            addView(ImageButton(a, null, 0, R.style.Swift_ToolbarButton).apply {
                setImageDrawable(Ui.icon(a, R.drawable.ic_close, white))
                contentDescription = "Close without saving"
                background = whiteRipple()
                setOnClickListener { requestClose() }
            }, LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)))
            addView(Ui.text(a, Ui.Type.TITLE, "Edit", white).apply { setPadding(Ui.dp(a, 8), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 40)))
        }
        val shapes = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 4))
            SHAPES.indices.forEach { i ->
                val chip = TextView(a).apply {
                    Ui.style(this, Ui.Type.LABEL, white)
                    gravity = Gravity.CENTER
                    minWidth = Ui.dp(a, 56)
                    setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 14), 0)
                    setOnClickListener { pickShape(i) }
                }
                chips += chip
                addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 36)).apply {
                    marginStart = Ui.dp(a, 4)
                    marginEnd = Ui.dp(a, 4)
                })
            }
        }
        val tools = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 12))
            addView(tool(R.drawable.ic_pic_rotate_left, "Turn left") { crop.rotate(clockwise = false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tool(R.drawable.ic_pic_rotate_right, "Turn right") { crop.rotate(clockwise = true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tool(R.drawable.ic_refresh, "Start again") {
                crop.reset()
                shape = 0
                paintChips()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        view = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            // Touches stay here (the pictures underneath don't move).
            isClickable = true
            addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 56)))
            addView(crop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(shapes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        crop.onChange = { paintChips() }
        paintChips()
    }

    private fun whiteRipple() = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, null)

    private fun tool(icon: Int, label: String, click: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 6))
        background = whiteRipple()
        contentDescription = label
        isClickable = true
        addView(ImageView(a).apply {
            setImageDrawable(Ui.icon(a, icon, Color.WHITE))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)))
        addView(Ui.text(a, Ui.Type.CAPTION, label, 0xDDFFFFFF.toInt(), lines = 1).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(0, Ui.dp(a, 6), 0, 0)
        })
        setOnClickListener { click() }
    }

    /**
     * A shape for the part kept, wide or tall as the picture is. Tapping the chosen shape again
     * turns it (4:3 to 3:4).
     */
    private fun pickShape(i: Int) {
        if (i == 0) {
            crop.setRatio(0f)
        } else {
            val r = SHAPES[i]
            val wide = if (i == shape && i != 1) crop.ratio < 1f else crop.turnedWidth >= crop.turnedHeight
            crop.setRatio(if (wide) r else 1f / r)
        }
        shape = i
        paintChips()
    }

    private fun paintChips() {
        chips.forEachIndexed { i, chip ->
            val chosen = i == shape
            val label = SHAPE_LABELS[i]
            // The chosen shape says which way it stands (3:4 when tall).
            chip.text = if (chosen && i > 1 && crop.ratio in 0.01f..0.999f) label.split(':').reversed().joinToString(":") else label
            chip.setTextColor(if (chosen) Ui.onAccent(a) else Color.WHITE)
            chip.background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), Ui.shape(a, if (chosen) Ui.accent(a) else 0x26FFFFFF, 18), null)
        }
        save.alpha = if (crop.changed) 1f else 0.5f
    }

    /** While the picture is written: the Save button waits. */
    fun setBusy(busy: Boolean) {
        save.isEnabled = !busy
        save.text = if (busy) "Saving…" else "Save"
    }

    /** Back or ×: asks first when something was changed. */
    fun requestClose() {
        if (!crop.changed) {
            onClose()
            return
        }
        Dialogs.confirm(a, "Leave without saving?", "The changes to this picture are lost.", "Leave", danger = true) { onClose() }
    }

    companion object {
        private val SHAPES = floatArrayOf(0f, 1f, 4f / 3f, 16f / 9f)
        private val SHAPE_LABELS = listOf("Free", "1:1", "4:3", "16:9")
    }
}

/**
 * The picture being edited, turned as chosen, with a frame around the part to keep: its corners
 * (and, when free, its sides) drag to change it, and inside it drags to move it.
 */
class CropView(context: Context) : View(context) {
    private var bmp: Bitmap? = null

    /** Quarter turns clockwise. */
    var turns = 0
        private set

    /** The part kept, as fractions of the turned picture. */
    val crop = RectF(0f, 0f, 1f, 1f)

    /** Width ÷ height of the part kept, as shown; 0 when free. */
    var ratio = 0f
        private set
    var onChange: (() -> Unit)? = null

    val changed: Boolean
        get() = turns != 0 || crop.left > 0.001f || crop.top > 0.001f || crop.right < 0.999f || crop.bottom < 0.999f

    val turnedWidth: Int get() = bmp?.let { if (turns % 2 == 0) it.width else it.height } ?: 0
    val turnedHeight: Int get() = bmp?.let { if (turns % 2 == 0) it.height else it.width } ?: 0

    private val d = resources.displayMetrics.density
    private val pad = 28 * d
    private val grab = 28 * d
    private val minSide = 48 * d

    private val toView = Matrix()

    /** The turned picture on screen, and the frame on it. */
    private val pic = RectF()
    private val box = RectF()
    private val start = RectF()
    private var mode = NONE
    private var downX = 0f
    private var downY = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0xA6000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
    }
    private val thirds = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        strokeWidth = d
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        strokeCap = Paint.Cap.SQUARE
    }

    fun setPicture(b: Bitmap) {
        bmp = b
        place()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place()
    }

    /** The turned picture as big as fits, with room around it for the frame's corners. */
    private fun place() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val tw = turnedWidth.toFloat()
        val th = turnedHeight.toFloat()
        val s = min((width - 2 * pad) / tw, (height - 2 * pad) / th)
        val w = tw * s
        val h = th * s
        pic.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        toView.set(Pictures.turnMatrix(ExifInterface.ORIENTATION_NORMAL, turns, b.width.toFloat(), b.height.toFloat()))
        toView.postScale(s, s)
        toView.postTranslate(pic.left, pic.top)
        box.set(pic.left + crop.left * w, pic.top + crop.top * h, pic.left + crop.right * w, pic.top + crop.bottom * h)
        invalidate()
    }

    private fun boxToCrop() {
        if (pic.isEmpty) return
        crop.set(
            ((box.left - pic.left) / pic.width()).coerceIn(0f, 1f),
            ((box.top - pic.top) / pic.height()).coerceIn(0f, 1f),
            ((box.right - pic.left) / pic.width()).coerceIn(0f, 1f),
            ((box.bottom - pic.top) / pic.height()).coerceIn(0f, 1f),
        )
    }

    /** A quarter turn; the frame turns with the picture, around the same part. */
    fun rotate(clockwise: Boolean) {
        val c = RectF(crop)
        if (clockwise) crop.set(1f - c.bottom, c.left, 1f - c.top, c.right) else crop.set(c.top, 1f - c.right, c.bottom, 1f - c.left)
        turns = (turns + if (clockwise) 1 else 3) % 4
        if (ratio > 0f) ratio = 1f / ratio
        place()
        // A small turn into place, so it's clear which way it went.
        animate().cancel()
        rotation = if (clockwise) -20f else 20f
        alpha = 0.6f
        animate().rotation(0f).alpha(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.standard).start()
        onChange?.invoke()
    }

    /** Keeps the part kept at width ÷ height [r] (0: free), as big as fits, in the middle. */
    fun setRatio(r: Float) {
        ratio = r
        if (r > 0f && !pic.isEmpty) {
            var w = pic.width()
            var h = w / r
            if (h > pic.height()) {
                h = pic.height()
                w = h * r
            }
            box.set(pic.centerX() - w / 2f, pic.centerY() - h / 2f, pic.centerX() + w / 2f, pic.centerY() + h / 2f)
            boxToCrop()
        }
        invalidate()
        onChange?.invoke()
    }

    /** The picture as it was: not turned, nothing cut. */
    fun reset() {
        turns = 0
        ratio = 0f
        crop.set(0f, 0f, 1f, 1f)
        place()
        onChange?.invoke()
    }

    // ------------------------------------------------------------------ dragging the frame

    private fun hit(x: Float, y: Float): Int {
        val alongX = x > box.left - grab && x < box.right + grab
        val alongY = y > box.top - grab && y < box.bottom + grab
        var m = NONE
        val dl = abs(x - box.left)
        val dr = abs(x - box.right)
        val dt = abs(y - box.top)
        val db = abs(y - box.bottom)
        if (alongY && min(dl, dr) < grab) m = m or (if (dl <= dr) LEFT else RIGHT)
        if (alongX && min(dt, db) < grab) m = m or (if (dt <= db) TOP else BOTTOM)
        // With a fixed shape only the corners change the size (a side alone would bend the shape).
        if (ratio > 0f && Integer.bitCount(m) == 1) m = NONE
        if (m == NONE && box.contains(x, y)) m = MOVE
        return m
    }

    private fun drag(dx: Float, dy: Float) {
        val least = min(minSide, min(pic.width(), pic.height()) / 2f)
        when {
            mode == MOVE -> {
                box.set(start)
                box.offset(dx.coerceIn(pic.left - start.left, pic.right - start.right), dy.coerceIn(pic.top - start.top, pic.bottom - start.bottom))
            }
            ratio > 0f -> {
                // A corner, keeping the shape: the opposite corner stays where it is.
                val left = mode and LEFT != 0
                val up = mode and TOP != 0
                val ax = if (left) start.right else start.left
                val ay = if (up) start.bottom else start.top
                var w = abs((if (left) start.left else start.right) + dx - ax)
                var h = abs((if (up) start.top else start.bottom) + dy - ay)
                if (w > h * ratio) h = w / ratio else w = h * ratio
                val maxW = if (left) ax - pic.left else pic.right - ax
                val maxH = if (up) ay - pic.top else pic.bottom - ay
                if (w > maxW) {
                    w = maxW
                    h = w / ratio
                }
                if (h > maxH) {
                    h = maxH
                    w = h * ratio
                }
                if (w < least) {
                    w = least
                    h = w / ratio
                }
                if (h < least) {
                    h = least
                    w = h * ratio
                }
                box.set(if (left) ax - w else ax, if (up) ay - h else ay, if (left) ax else ax + w, if (up) ay else ay + h)
            }
            else -> {
                box.set(start)
                if (mode and LEFT != 0) box.left = (start.left + dx).coerceIn(pic.left, start.right - least)
                if (mode and RIGHT != 0) box.right = (start.right + dx).coerceIn(start.left + least, pic.right)
                if (mode and TOP != 0) box.top = (start.top + dy).coerceIn(pic.top, start.bottom - least)
                if (mode and BOTTOM != 0) box.bottom = (start.bottom + dy).coerceIn(start.top + least, pic.bottom)
            }
        }
        boxToCrop()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (bmp == null) return false
                mode = hit(e.x, e.y)
                if (mode == NONE) return false
                start.set(box)
                downX = e.x
                downY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == NONE) return false
                drag(e.x - downX, e.y - downY)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (mode != NONE) {
                mode = NONE
                invalidate()
                onChange?.invoke()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        canvas.drawBitmap(b, toView, bitmapPaint)
        // What is cut away, dimmed.
        canvas.drawRect(pic.left, pic.top, pic.right, box.top, shade)
        canvas.drawRect(pic.left, box.bottom, pic.right, pic.bottom, shade)
        canvas.drawRect(pic.left, box.top, box.left, box.bottom, shade)
        canvas.drawRect(box.right, box.top, pic.right, box.bottom, shade)
        // Thirds while dragging, to place things as photographers do.
        if (mode != NONE) {
            for (i in 1..2) {
                val x = box.left + box.width() * i / 3f
                val y = box.top + box.height() * i / 3f
                canvas.drawLine(x, box.top, x, box.bottom, thirds)
                canvas.drawLine(box.left, y, box.right, y, thirds)
            }
        }
        canvas.drawRect(box, frame)
        val len = 18 * d
        val o = handle.strokeWidth / 2f
        fun corner(x: Float, y: Float, sx: Float, sy: Float) {
            canvas.drawLine(x - sx * o, y - sy * o, x + sx * len, y - sy * o, handle)
            canvas.drawLine(x - sx * o, y - sy * o, x - sx * o, y + sy * len, handle)
        }
        corner(box.left, box.top, 1f, 1f)
        corner(box.right, box.top, -1f, 1f)
        corner(box.left, box.bottom, 1f, -1f)
        corner(box.right, box.bottom, -1f, -1f)
    }

    companion object {
        private const val NONE = 0
        private const val MOVE = 1
        private const val LEFT = 2
        private const val TOP = 4
        private const val RIGHT = 8
        private const val BOTTOM = 16
    }
}

/**
 * Writing edited pictures (2.9), off the main thread. The result is made from the full picture,
 * a band of rows at a time (each turned straight into place), so a camera photo needs memory for
 * the result only, not for two copies of it. A picture too big even for that is made at half (or
 * a quarter) its size, and the caller says so. JPEGs keep the camera's details (EXIF: the date
 * taken, the camera, the place) and are written at quality 92.
 */
object PictureEdit {
    private const val QUALITY = 92

    /** Rows of the result decoded at a time. */
    private const val BAND = 256

    const val TOO_BIG = "This picture is too big for this phone to change at full size, so Swift won't replace the original with a smaller one. Save a copy instead."

    /** Something the user should read (the rest are Swift's own failures). */
    class Problem(message: String) : Exception(message)

    /** What was written: the file, its size in pixels, and how many times smaller than the original it had to be (1: full size). */
    class Saved(val file: File, val width: Int, val height: Int, val sample: Int)

    /**
     * Room for the picture being written: a sixth of what Swift may use, between 16 and 64 MB. On
     * the Moto E4 (512 MB for Swift) that is 64 MB: a 16-megapixel photo at full size.
     */
    private fun budget(): Long = (Runtime.getRuntime().maxMemory() / 6).coerceIn(16L shl 20, 64L shl 20)

    /**
     * JPEG, PNG and WebP pictures are written back as they were (so they can replace the original);
     * others become PNG copies. 3.0: a lossless WebP picture too, but only where Android writes
     * lossless WebP (10 and later): on Android 7 it would come out lossy, so it becomes a PNG copy.
     */
    fun keepsKind(info: Pictures.Info) = info.mime == "image/jpeg" || info.mime == "image/png" ||
        (info.mime == "image/webp" && (!info.lossless || Build.VERSION.SDK_INT >= 29))

    /** 3.0: a lossless WebP picture that this phone can only save without loss as a PNG. */
    fun pngForLossless(info: Pictures.Info) = info.mime == "image/webp" && info.lossless && Build.VERSION.SDK_INT < 29

    /** Why [info]'s picture can't be turned in place (it is saved as a turned copy instead). */
    fun whyNotInPlace(info: Pictures.Info): String =
        if (pngForLossless(info)) "This WebP picture has no loss of quality, which this phone can only keep in a PNG. Edit can save a turned PNG copy."
        else "Swift can turn JPEG, PNG and WebP pictures. Edit can save a turned copy of this one."

    private fun kindOut(info: Pictures.Info) = if (keepsKind(info)) info.mime!! else "image/png"

    /** The ending of the edited picture's file. */
    fun extension(info: Pictures.Info, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (kindOut(info)) {
            "image/jpeg" -> if (ext == "jpg" || ext == "jpeg") ext else "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
    }

    /** "name (edited).jpg" in [dir], or "(edited 2)" and so on when that is taken. Editing an edited picture doesn't pile the words up. */
    fun copyIn(dir: File, name: String, ext: String): File {
        val base = name.substringBeforeLast('.').replace(Regex(""" \(edited( \d+)?\)$"""), "").ifBlank { "Picture" }
        var f = File(dir, "$base (edited).$ext")
        var n = 2
        while (f.exists()) f = File(dir, "$base (edited ${n++}).$ext")
        return f
    }

    /**
     * Writes [src] turned [turns] quarter turns clockwise and cut to [crop] (fractions of the turned
     * picture) to [dest] (the original itself, or a new file). [fullSizeOnly]: refuses rather than
     * writing a smaller picture (replacing the original).
     */
    fun save(ctx: android.content.Context, src: Pictures.Source, info: Pictures.Info, turns: Int, crop: RectF, dest: File, fullSizeOnly: Boolean): Saved {
        val jpeg = info.mime == "image/jpeg"
        // Read before anything is written: [dest] may be the original.
        val exif = if (jpeg) Pictures.exif(ctx, src) else null
        val out = render(ctx, src, info, turns, crop, fullSizeOnly)
        val w = out.bitmap.width
        val h = out.bitmap.height
        var temp = try {
            encode(ctx, out.bitmap, kindOut(info), info.lossless)
        } finally {
            // Never drawn on screen: freed at once for the next step.
            out.bitmap.recycle()
        }
        try {
            if (jpeg) temp = withExif(exif, temp)
            writeOver(ctx, temp, dest)
        } finally {
            temp.delete()
        }
        FileOps.scan(ctx, listOf(dest.path))
        return Saved(dest, w, h, out.sample)
    }

    /**
     * Turns the picture file itself a quarter turn ([turns] 1: clockwise, -1: back). A JPEG only
     * changes the tag that says which way up it is: nothing of the photo is lost. PNG and WebP
     * pictures are written again.
     */
    fun turnFile(ctx: android.content.Context, src: Pictures.Source, info: Pictures.Info, turns: Int) {
        val file = src.file ?: throw Problem("Swift can only turn pictures in your files")
        when (info.mime) {
            "image/jpeg" -> {
                val temp = File(workDir(ctx), "turned.jpg")
                try {
                    file.copyTo(temp, overwrite = true)
                    ExifInterface(temp.path).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, turned(info.orientation, turns).toString())
                        saveAttributes()
                    }
                    if (!readable(temp)) throw IOException("The turned picture can't be read")
                    writeOver(ctx, temp, file)
                } finally {
                    temp.delete()
                }
                FileOps.scan(ctx, listOf(file.path))
            }
            "image/png", "image/webp" -> try {
                if (!keepsKind(info)) throw Problem(whyNotInPlace(info))
                save(ctx, src, info, turns, RectF(0f, 0f, 1f, 1f), file, fullSizeOnly = true)
            } catch (e: Problem) {
                throw if (e.message == TOO_BIG) Problem("This picture is too big for this phone to turn at full size. Edit can save a turned copy.") else e
            }
            else -> throw Problem(whyNotInPlace(info))
        }
    }

    /** The EXIF orientation after [turns] more quarter turns clockwise (a mirrored picture stays mirrored). */
    fun turned(orientation: Int, turns: Int): Int {
        val q = ((turns % 4) + 4) % 4
        val plain = intArrayOf(
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_ROTATE_270,
        )
        val mirrored = intArrayOf(
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_FLIP_VERTICAL, ExifInterface.ORIENTATION_TRANSPOSE,
        )
        plain.indexOf(orientation).takeIf { it >= 0 }?.let { return plain[(it + q) % 4] }
        mirrored.indexOf(orientation).takeIf { it >= 0 }?.let { return mirrored[(it + q) % 4] }
        return plain[q]
    }

    private class Rendered(val bitmap: Bitmap, val sample: Int)

    private fun render(ctx: android.content.Context, src: Pictures.Source, info: Pictures.Info, turns: Int, crop: RectF, fullSizeOnly: Boolean): Rendered {
        // The part kept, in the stored picture's pixels.
        val toFinal = Pictures.turnMatrix(info.orientation, turns, info.rawWidth.toFloat(), info.rawHeight.toFloat())
        val whole = RectF(0f, 0f, info.rawWidth.toFloat(), info.rawHeight.toFloat())
        toFinal.mapRect(whole)
        val cut = RectF(crop.left * whole.width(), crop.top * whole.height(), crop.right * whole.width(), crop.bottom * whole.height())
        val back = Matrix()
        toFinal.invert(back)
        back.mapRect(cut)
        val raw = Rect(cut.left.roundToInt(), cut.top.roundToInt(), cut.right.roundToInt(), cut.bottom.roundToInt())
        if (!raw.intersect(0, 0, info.rawWidth, info.rawHeight) || raw.width() < 1 || raw.height() < 1) throw Problem("Nothing of the picture is left to save")

        val dec = if (info.hasParts) Pictures.regionDecoder(ctx, src) else null
        try {
            // Halved until it fits: the result, plus (without decoding in parts) the whole picture it comes from.
            val budget = budget()
            fun bytes(s: Int): Long {
                val result = (raw.width() / s).toLong() * (raw.height() / s) * 4
                return if (dec != null) result else result + (info.rawWidth / s).toLong() * (info.rawHeight / s) * 4
            }
            var sample = 1
            while (bytes(sample) > budget) sample *= 2
            if (sample > 1 && fullSizeOnly) throw Problem(TOO_BIG)
            // Whole samples only, so each band lands on whole pixels of the result.
            raw.right = min(info.rawWidth, raw.left + max(sample, raw.width() / sample * sample))
            raw.bottom = min(info.rawHeight, raw.top + max(sample, raw.height() / sample * sample))
            val w = (raw.width() / sample).coerceAtLeast(1)
            val h = (raw.height() / sample).coerceAtLeast(1)
            val turn = Pictures.turnMatrix(info.orientation, turns, w.toFloat(), h.toFloat())
            val size = RectF(0f, 0f, w.toFloat(), h.toFloat())
            turn.mapRect(size)
            val out = Bitmap.createBitmap(size.width().roundToInt(), size.height().roundToInt(), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(out)
                if (dec != null) inBands(dec, raw, sample, turn, canvas) else whole(ctx, src, raw, sample, turn, canvas)
            } catch (e: Throwable) {
                out.recycle()
                throw e
            }
            return Rendered(out, sample)
        } finally {
            dec?.recycle()
        }
    }

    /** The part [raw] a band of rows at a time, each turned into its place in the result. */
    private fun inBands(dec: BitmapRegionDecoder, raw: Rect, sample: Int, turn: Matrix, canvas: Canvas) {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val m = Matrix()
        var y = raw.top
        while (y < raw.bottom) {
            val r = Rect(raw.left, y, raw.right, min(y + BAND * sample, raw.bottom))
            val band = dec.decodeRegion(r, opts) ?: throw IOException("A part of the picture couldn't be read")
            m.set(turn)
            m.preTranslate(0f, ((y - raw.top) / sample).toFloat())
            // No smoothing: quarter turns move whole pixels, so the photo stays exactly as sharp.
            canvas.drawBitmap(band, m, null)
            band.recycle()
            y = r.bottom
        }
    }

    /** GIF and BMP (Android can't decode parts of them): the whole picture, then the part. */
    private fun whole(ctx: android.content.Context, src: Pictures.Source, raw: Rect, sample: Int, turn: Matrix, canvas: Canvas) {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val full = (if (src.file != null) BitmapFactory.decodeFile(src.file.path, opts) else src.open(ctx).use { BitmapFactory.decodeStream(it, null, opts) })
            ?: throw IOException("The picture couldn't be read")
        val m = Matrix(turn)
        m.preTranslate(-raw.left / sample.toFloat(), -raw.top / sample.toFloat())
        canvas.drawBitmap(full, m, null)
        full.recycle()
    }

    private fun workDir(ctx: android.content.Context) = File(ctx.cacheDir, "edit").apply { mkdirs() }

    private fun encode(ctx: android.content.Context, bmp: Bitmap, mime: String, lossless: Boolean): File {
        // 3.0: a lossless WebP stays lossless where Android can write it so (10 and later).
        val keepAll = mime == "image/webp" && lossless && Build.VERSION.SDK_INT >= 29
        val (format, ext) = when (mime) {
            "image/jpeg" -> Bitmap.CompressFormat.JPEG to "jpg"
            "image/webp" -> webp(keepAll) to "webp"
            else -> Bitmap.CompressFormat.PNG to "png"
        }
        val f = File(workDir(ctx), "edited.$ext")
        FileOutputStream(f).use { if (!bmp.compress(format, if (keepAll) 100 else QUALITY, it)) throw IOException("The picture couldn't be written") }
        return f
    }

    // 2.9: WEBP is lossy below quality 100 on every Android; Android 11 only renamed it WEBP_LOSSY.
    // 3.0: at 100 it is lossless from Android 10 (WEBP_LOSSLESS from 11); on Android 7 still lossy.
    @Suppress("DEPRECATION")
    private fun webp(lossless: Boolean): Bitmap.CompressFormat = when {
        Build.VERSION.SDK_INT >= 30 -> if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        else -> Bitmap.CompressFormat.WEBP
    }

    /** The camera's details worth keeping (not its size or orientation, which change). */
    private val KEPT = arrayOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, "ISOSpeedRatings", ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    /**
     * [jpeg] with the original's camera details and "upright" as its orientation. Done on a copy:
     * Android 7 rewrites the file to add them, and a failure there must not cost the picture.
     */
    private fun withExif(from: ExifInterface?, jpeg: File): File {
        val copy = File(jpeg.parentFile, "with-details.jpg")
        try {
            jpeg.copyTo(copy, overwrite = true)
            val x = ExifInterface(copy.path)
            if (from != null) for (tag in KEPT) from.getAttribute(tag)?.let { x.setAttribute(tag, it) }
            x.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            x.saveAttributes()
            if (readable(copy)) {
                jpeg.delete()
                return copy
            }
        } catch (e: Exception) {
            L.w("Couldn't keep the photo's details", e)
        }
        copy.delete()
        return jpeg
    }

    private fun readable(f: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    /**
     * [temp] becomes [dest]. On the phone's storage it is copied next to it first and then renamed
     * over it, so a save cut short never leaves half a picture; on a memory card it goes through
     * the card's access.
     */
    private fun writeOver(ctx: android.content.Context, temp: File, dest: File) {
        val access = Volumes.accessFor(ctx, dest) ?: throw Problem(Transfers.CARD_TEXT)
        val dir = dest.parentFile ?: throw IOException("No folder")
        if (!access.mkdirs(dir)) throw Problem("Swift couldn't make the folder “${dir.name}”")
        if (access === PlainAccess) {
            val part = File(dir, ".${dest.name}.part")
            try {
                temp.copyTo(part, overwrite = true)
                if (!part.renameTo(dest)) throw IOException("Couldn't put the picture in place")
            } finally {
                part.delete()
            }
        } else {
            access.output(dest).use { out -> temp.inputStream().use { it.copyTo(out) } }
        }
    }
}
