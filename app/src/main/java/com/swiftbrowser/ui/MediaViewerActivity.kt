package com.swiftbrowser.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.files.FileKind
import com.swiftbrowser.files.FileOpener
import com.swiftbrowser.files.FileOps
import com.swiftbrowser.files.FileThumbs
import com.swiftbrowser.files.Transfers
import com.swiftbrowser.files.Volumes
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Pictures on a screen of their own (2.8), over the screen they were opened from.
 *
 * 2.9: pictures only (videos and music have their own player). The folder's pictures in the order
 * Files lists them: swipe from one to the next, pinch or double tap to zoom (a big photo stays
 * sharp close up), and the strip of small pictures at the bottom jumps to any of them. The bars
 * (a tap hides them, so the picture has the whole screen) share, edit (turn and cut), delete and
 * show the picture's details; ⋮ turns it, sets it as the wallpaper or a contact's photo, plays a
 * slideshow, or opens it in another app or in Files.
 *
 * "Open with Swift" from other apps comes here too: a file opens with its folder around it;
 * another app's link (content://) shows just that picture, and an edit of it is saved as a copy
 * in Pictures/Swift.
 */
class MediaViewerActivity : AppCompatActivity(), Messages.DarkScreen {
    // 3.0.3: a black screen in both modes: its messages are dark too.
    override val darkMessages: Boolean get() = true

    enum class Kind { IMAGE, VIDEO, AUDIO }

    /** One picture: of the folder, or the one another app sent. */
    private class Pic(val source: Pictures.Source, var name: String, var time: Long, var size: Long) {
        /** Its place in the pager, kept when the list around it changes. */
        val id = ids.incrementAndGet()
        val file: File? get() = source.file
        val key: String get() = source.key

        /** Read with its first decoding (on the main thread from then on). */
        var info: Pictures.Info? = null

        /** It couldn't be read (not tried again until it changes). */
        var failed = false
    }

    private lateinit var root: FrameLayout
    private lateinit var pager: RecyclerView
    private lateinit var topBar: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var strip: RecyclerView
    private lateinit var title: TextView
    private lateinit var count: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var barsShown = true

    private val pics = ArrayList<Pic>()
    private var index = 0

    /** Where decoding works around: the picture on screen, or the slideshow's (read off the main thread). */
    @Volatile
    private var focus = 0

    private val pagerAdapter = PagerAdapter()
    private val stripAdapter = StripAdapter()

    // 2.9: one thread decodes pictures (the one on screen first, then its neighbours), one decodes
    // detail for the zoom (it never holds a swipe up), one writes (edits, turns).
    private val decoder = thread("picture-decode")
    private val detailer = thread("picture-detail")
    private val work = thread("picture-work")

    /**
     * Pictures decoded at the screen's size, by their key. 2.9: by size, not by number (2.8 kept
     * 3): a sixteenth of what Swift may use, 8 to 24 MB. With Swift's 512 MB on a Moto E4 that is
     * 24 MB, six to twelve photos, so swiping back and forth doesn't decode them again.
     */
    private val bitmaps = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).coerceIn(8L shl 20, 24L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Looks.apply(this)
        super.onCreate(savedInstanceState)
        // 3.0: another app's link (or path) to Swift's own private files is refused: shown here, it
        // could then be shared or copied out.
        if (refused(intent)) {
            L.w("Picture viewer: refused a link to Swift's own files")
            android.widget.Toast.makeText(applicationContext, ViewerAccess.REFUSED, android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val first = firstPicture(intent)
        if (first == null) {
            Messages.show(this, "Swift can't show this picture")
            finish()
            return
        }
        // 3.0: a picture of Private (or one Files asks to protect) stays out of screenshots and recent apps.
        if (ViewerAccess.secure(this, intent, first.file)) SecureScreen.set(this, true)
        // Black from the first frame (the window is in Swift's background colour otherwise).
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        blackBars()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        pics += first
        buildPager()
        buildTopBar()
        buildBottom()
        updateTitle()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    slides != null -> stopSlideshow()
                    editor != null -> editor?.requestClose()
                    else -> finish()
                }
            }
        })
        SystemBars.keepHidden(this)
        if (first.file != null) listFolder(first) else readSharedName(first)
    }

    @Suppress("DEPRECATION")
    private fun blackBars() {
        // When Swift isn't full screen, the phone's bars match the picture's background.
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    /** 3.0: what this screen won't show: see ViewerAccess. */
    private fun refused(i: Intent): Boolean {
        if (i.action == Intent.ACTION_VIEW) return i.data?.let { ViewerAccess.refused(this, it) } == true
        val f = i.getStringExtra(EXTRA_PATH)?.let { File(it) } ?: return false
        return !ViewerAccess.mayOpen(this, i, f)
    }

    /** The picture asked for: a file of Swift's (Files), or one from another app ("Open with"). */
    private fun firstPicture(i: Intent): Pic? {
        if (i.action == Intent.ACTION_VIEW) {
            val uri = i.data ?: return null
            return when (uri.scheme) {
                "file" -> uri.path?.let { File(it) }?.takeIf { it.canRead() }?.let { Pic(Pictures.Source.of(it), it.name, it.lastModified(), it.length()) }
                "content" -> Pic(Pictures.Source.of(uri), uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Picture", 0L, -1L)
                else -> null
            }
        }
        val f = i.getStringExtra(EXTRA_PATH)?.let { File(it) } ?: return null
        if (kindOf(f.name) != Kind.IMAGE || !f.canRead()) return null
        return Pic(Pictures.Source.of(f), f.name, f.lastModified(), f.length())
    }

    /** The other pictures of the folder, by name as Files lists them, read off the main thread. */
    private fun listFolder(first: Pic) {
        val f = first.file ?: return
        work.execute {
            val found = f.parentFile?.listFiles { c -> !c.name.startsWith(".") && kindOf(c.name) == Kind.IMAGE && c.path != f.path }
                ?.filter { it.isFile }
                ?.map { Pic(Pictures.Source.of(it), it.name, it.lastModified(), it.length()) }
                .orEmpty()
            val all = (found + first).sortedWith { a, b -> FileOps.naturalCompare(a.name, b.name) }
            handler.post { if (!isDestroyed) folderListed(all) }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun folderListed(all: List<Pic>) {
        if (all.size <= 1) return
        val shown = current()
        pics.clear()
        pics += all
        index = pics.indexOf(shown).coerceAtLeast(0)
        focus = index
        // Stable ids: the picture on screen keeps its page (no flash) as the others come round it.
        pagerAdapter.notifyDataSetChanged()
        pager.scrollToPosition(index)
        strip.visibility = View.VISIBLE
        stripAdapter.notifyDataSetChanged()
        marked = -1
        markStrip(smooth = false)
        updateTitle()
        prefetch()
    }

    /** What another app calls its picture, and its size, for the title and the details. */
    private fun readSharedName(p: Pic) {
        val uri = p.source.uri ?: return
        work.execute {
            val (name, size) = Pictures.nameAndSize(this, uri)
            handler.post {
                if (isDestroyed) return@post
                if (!name.isNullOrBlank()) p.name = name
                if (size >= 0) p.size = size
                updateTitle()
            }
        }
    }

    private fun current(): Pic = pics[index.coerceIn(0, pics.lastIndex)]

    // ------------------------------------------------------------------ the pictures

    private fun buildPager() {
        val lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        pager = RecyclerView(this).apply {
            layoutManager = lm
            setHasFixedSize(true)
            itemAnimator = null
            adapter = pagerAdapter
        }
        val snap = PagerSnapHelper()
        snap.attachToRecyclerView(pager)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val v = snap.findSnapView(lm) ?: return
                val pos = rv.getChildAdapterPosition(v)
                if (pos != RecyclerView.NO_POSITION && pos != index) {
                    index = pos
                    pageChanged()
                }
            }
        })
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private inner class PagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemCount() = pics.size

        override fun getItemId(position: Int) = pics[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = PictureView(this@MediaViewerActivity).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                onTap = { toggleBars() }
                onSettled = { wantDetail(it) }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = show(holder.itemView as PictureView, pics[position])

        // A page put aside lets go of its picture (it stays in the cache while there is room).
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            val v = holder.itemView as PictureView
            v.tag = null
            v.setPicture(null, 0, 0)
        }
    }

    /** [p] on page [v]: from memory at once, or decoded. [again]: the file changed (the old picture stays until the new one is ready). */
    private fun show(v: PictureView, p: Pic, again: Boolean = false) {
        if (v.tag === p && v.hasPicture && !again) return
        val keep = v.tag === p && v.hasPicture
        v.tag = p
        val cached = bitmaps.get(p.key)
        val info = p.info
        if (cached != null && info != null) {
            v.setPicture(cached, info.width, info.height)
            shown(v, p)
            return
        }
        if (!keep) v.setPicture(null, 0, 0)
        v.message = if (p.failed) CANT_SHOW else null
        if (p.failed) return
        load(p) { bmp ->
            val i = p.info
            if (v.tag !== p) return@load
            if (bmp == null || i == null) {
                v.setPicture(null, 0, 0)
                v.message = CANT_SHOW
            } else {
                v.setPicture(bmp, i.width, i.height)
                shown(v, p)
            }
        }
    }

    /** A picture is ready on its page: the one on screen plays (a GIF), gets its detail and its neighbours. */
    private fun shown(v: PictureView, p: Pic) {
        if (p !== current()) return
        updateTitle()
        startGif(v, p)
        v.settleSoon()
        prefetch()
    }

    /** After the picture on screen changed: its name and place in the strip; only it plays and keeps detail. */
    private fun pageChanged() {
        focus = index
        updateTitle()
        markStrip(smooth = true)
        pager.post { settleViews() }
        prefetch()
    }

    private fun settleViews() {
        if (isDestroyed) return
        val now = pics.getOrNull(index)
        for (i in 0 until pager.childCount) {
            val v = pager.getChildAt(i) as? PictureView ?: continue
            val p = v.tag as? Pic ?: continue
            when {
                // Skipped while it was far away (a quick jump): decoded now.
                !v.hasPicture -> show(v, p)
                p === now -> {
                    startGif(v, p)
                    v.settleSoon()
                }
                else -> {
                    v.reset()
                    v.stopMovie()
                }
            }
        }
    }

    private fun goTo(pos: Int) {
        if (pics.isEmpty()) return
        index = pos.coerceIn(0, pics.lastIndex)
        pager.scrollToPosition(index)
        pageChanged()
    }

    // ------------------------------------------------------------------ decoding

    /** Asked for and not decoded yet: what to do when each one is. */
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /**
     * [p] decoded at the screen's size, from memory or off the main thread; [done] runs on the main
     * thread (null: it can't be read). [urgent]: decoded even when far from the picture on screen.
     */
    private fun load(p: Pic, urgent: Boolean = false, done: ((Bitmap?) -> Unit)?) {
        val key = p.key
        bitmaps.get(key)?.let { bmp ->
            if (p.info != null) {
                done?.invoke(bmp)
                return
            }
        }
        waiting[key]?.let { list ->
            if (done != null) list += done
            return
        }
        waiting[key] = ArrayList<(Bitmap?) -> Unit>().also { if (done != null) it += done }
        val at = pics.indexOf(p)
        val boxW = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val boxH = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        decoder.execute {
            // Pictures swiped far past by now aren't decoded: their pages ask again if they come back.
            if (!urgent && abs(at - focus) > 2) {
                handler.post { waiting.remove(key) }
                return@execute
            }
            var info = p.info
            val bmp = try {
                if (info == null) info = Pictures.info(this, p.source)
                info?.let { Pictures.decodeFit(this, p.source, it, boxW, boxH) }
            } catch (e: OutOfMemoryError) {
                L.w("No memory for a picture")
                bitmaps.evictAll()
                null
            } catch (e: Exception) {
                L.w("Couldn't show a picture", e)
                null
            }
            handler.post {
                val callbacks = waiting.remove(key)
                if (isDestroyed) return@post
                if (info != null) p.info = info
                if (bmp != null) bitmaps.put(key, bmp) else p.failed = true
                callbacks?.forEach { it(bmp) }
            }
        }
    }

    /** The pictures either side, so a swipe shows them at once. */
    private fun prefetch() {
        for (d in intArrayOf(1, -1)) pics.getOrNull(index + d)?.let { if (!it.failed) load(it, done = null) }
    }

    // ------------------------------------------------------------------ sharp zoom

    /** The picture on screen's decoder for parts (used on the detail thread only). */
    private var regionKey: String? = null
    private var region: BitmapRegionDecoder? = null

    /**
     * The zoom settled on [v]: the part on screen decoded at full detail when the picture drawn is
     * coarser there. One part at a time, about a screenful of pixels (at most four).
     */
    private fun wantDetail(v: PictureView) {
        val p = v.tag as? Pic ?: return
        val info = p.info ?: return
        if (p !== current() || !info.hasParts || slides != null) return
        val part = v.partWanted() ?: return
        val ticket = v.detailWanted
        val scale = v.scale
        detailer.execute {
            if (ticket != v.detailWanted) return@execute
            val dec = regionFor(p) ?: return@execute
            val got = try {
                Pictures.decodePart(dec, info, part, scale)
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                L.w("Couldn't decode a part of a picture", e)
                null
            }
            if (got != null) handler.post { if (!isDestroyed) v.setDetail(got.bitmap, got.rect, ticket) }
        }
    }

    /** On the detail thread: [p]'s decoder, made once while it is on screen. */
    private fun regionFor(p: Pic): BitmapRegionDecoder? {
        if (regionKey == p.key) return region
        region?.recycle()
        region = Pictures.regionDecoder(this, p.source)
        regionKey = p.key
        return region
    }

    // ------------------------------------------------------------------ GIFs

    private var gifTicket = 0

    /** A GIF on screen plays; it is read again each time (all its frames take memory while it plays). */
    private fun startGif(v: PictureView, p: Pic) {
        if (v.isPlaying || p.info?.mime != "image/gif" || p.size > Pictures.MAX_GIF_BYTES) return
        val ticket = ++gifTicket
        decoder.execute {
            val movie = Pictures.movie(this, p.source)
            handler.post {
                if (movie != null && ticket == gifTicket && !isDestroyed && v.tag === p && p === current() && !v.isPlaying) v.play(movie)
            }
        }
    }

    // ------------------------------------------------------------------ the bars

    private fun whiteRipple() = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, null)

    private fun buildTopBar() {
        val white = Color.WHITE
        title = TextView(this).apply {
            setTextColor(white)
            Ui.size(this, Ui.Type.SUBTITLE)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        count = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            Ui.size(this, Ui.Type.CAPTION, keepFont = true)
            isSingleLine = true
        }
        val size = Ui.dp(this, 48)
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MediaViewerActivity, 4), 0, Ui.dp(this@MediaViewerActivity, 12), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000))
            addView(ImageButton(this@MediaViewerActivity, null, 0, R.style.Swift_ToolbarButton).apply {
                setImageDrawable(Ui.icon(this@MediaViewerActivity, R.drawable.ic_back, white))
                contentDescription = "Back"
                background = whiteRipple()
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(size, size))
            addView(LinearLayout(this@MediaViewerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@MediaViewerActivity, 8), 0, 0, 0)
                addView(title)
                addView(count)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64), Gravity.TOP))
    }

    /** The strip of small pictures and the bar of buttons, at the bottom. */
    private fun buildBottom() {
        thumbPx = Ui.dp(this, 44)
        thumbGap = Ui.dp(this, 3)
        strip = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MediaViewerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = stripAdapter
            itemAnimator = null
            setHasFixedSize(true)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = if (pics.size > 1) View.VISIBLE else View.GONE
            // Half the width on each side, so the first and last pictures can be in the middle too.
            addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) v.post {
                    val side = ((right - left) - thumbPx - 2 * thumbGap) / 2
                    v.setPadding(side, 0, side, 0)
                    markStrip(smooth = false)
                }
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@MediaViewerActivity, 4), 0, Ui.dp(this@MediaViewerActivity, 4), 0)
            val lp = { LinearLayout.LayoutParams(0, Ui.dp(this@MediaViewerActivity, 52), 1f) }
            addView(barButton(R.drawable.ic_share, "Share") { share() }, lp())
            addView(barButton(R.drawable.ic_edit, "Edit") { edit() }, lp())
            if (current().file != null) addView(barButton(R.drawable.ic_delete, "Delete") { askDelete() }, lp())
            addView(barButton(R.drawable.ic_info, "Info") { showInfo() }, lp())
            addView(barButton(R.drawable.ic_more, "More") { more() }, lp())
        }
        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@MediaViewerActivity, 24), 0, 0)
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000))
            addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MediaViewerActivity, 56)))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /** An icon of the bottom bar (2.9: no words under it; its name is said by TalkBack and shown when held). */
    private fun barButton(icon: Int, label: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = whiteRipple()
        contentDescription = label
        isClickable = true
        isFocusable = true
        addView(ImageView(this@MediaViewerActivity).apply {
            setImageDrawable(Ui.icon(this@MediaViewerActivity, icon, Color.WHITE))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(this@MediaViewerActivity, 24), Ui.dp(this@MediaViewerActivity, 24)))
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, label)
        // 3.0.3: a press springs.
        Ui.pressable(this, 0.9f)
        setOnClickListener { click() }
    }

    private fun updateTitle() {
        val p = current()
        title.text = p.name
        val parts = ArrayList<String>()
        if (pics.size > 1) parts += "${index + 1} of ${pics.size}"
        if (p.size >= 0) parts += UrlUtils.formatBytes(p.size)
        p.info?.let { parts += "${it.width} × ${it.height}" }
        count.text = parts.joinToString(" · ")
    }

    /** A tap shows or hides the bars and the strip (the picture has the whole screen without them). */
    private fun toggleBars(show: Boolean = !barsShown) {
        barsShown = show
        for (v in listOf<View>(topBar, bottom)) {
            v.animate().cancel()
            if (show) v.visibility = View.VISIBLE
            v.animate().alpha(if (show) 1f else 0f).setDuration(Motion.SHORT).withEndAction {
                v.visibility = if (barsShown) View.VISIBLE else View.INVISIBLE
            }.start()
        }
    }

    /** A message above the bottom bar while it shows. */
    private fun say(text: String, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null) {
        val over = barsShown && editor == null && slides == null
        Messages.show(this, text, action, long = long, bottomOffset = if (over) bottom.height else 0, onAction = onAction)
    }

    // ------------------------------------------------------------------ the strip of small pictures

    private var thumbPx = 0
    private var thumbGap = 0

    /** The strip's picture marked as the one on screen. */
    private var marked = -1

    private class StripHolder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var request: FileThumbs.Request? = null
    }

    /**
     * 2.9: the small pictures are the Files screen's (FileThumbs): made two at a time off the main
     * thread at 44 dp (about 8 KB each), kept in its memory cache (a 24th of Swift's memory, 4 to
     * 12 MB: 12 MB on a Moto E4), and shared with Files, so a folder seen there is ready here.
     */
    private inner class StripAdapter : RecyclerView.Adapter<StripHolder>() {
        override fun getItemCount() = pics.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StripHolder {
            val iv = ImageView(this@MediaViewerActivity).apply {
                layoutParams = RecyclerView.LayoutParams(thumbPx, thumbPx).apply {
                    marginStart = thumbGap
                    marginEnd = thumbGap
                    topMargin = Ui.dp(this@MediaViewerActivity, 6)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.shape(this@MediaViewerActivity, 0x33FFFFFF, 8)
                clipToOutline = true
            }
            val h = StripHolder(iv)
            iv.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos != index) goTo(pos)
            }
            return h
        }

        override fun onBindViewHolder(holder: StripHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(MARK)) mark(holder, position) else onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: StripHolder, position: Int) {
            val p = pics[position]
            holder.request?.cancel()
            holder.request = null
            holder.image.tag = p
            holder.image.setImageDrawable(null)
            holder.image.contentDescription = p.name
            mark(holder, position)
            val f = p.file ?: return
            holder.request = FileThumbs.load(this@MediaViewerActivity, f, p.time, FileKind.IMAGE, thumbPx) { bmp ->
                if (holder.image.tag === p) holder.image.setImageBitmap(bmp)
            }
        }

        override fun onViewRecycled(holder: StripHolder) {
            holder.request?.cancel()
            holder.request = null
        }

        private fun mark(h: StripHolder, position: Int) {
            val on = position == index
            h.image.alpha = if (on) 1f else 0.55f
            h.image.foreground = if (on) {
                GradientDrawable().apply {
                    cornerRadius = Ui.dpf(this@MediaViewerActivity, 8f)
                    setStroke(Ui.dp(this@MediaViewerActivity, 2), Ui.accent(this@MediaViewerActivity))
                }
            } else {
                null
            }
        }
    }

    /** Marks the picture on screen in the strip and brings it to the middle. */
    private fun markStrip(smooth: Boolean) {
        if (strip.visibility != View.VISIBLE || pics.isEmpty()) return
        if (marked in pics.indices && marked != index) stripAdapter.notifyItemChanged(marked, MARK)
        stripAdapter.notifyItemChanged(index, MARK)
        marked = index
        val lm = strip.layoutManager as LinearLayoutManager
        val v = lm.findViewByPosition(index)
        if (smooth && v != null) strip.smoothScrollBy((v.left + v.right) / 2 - strip.width / 2, 0) else lm.scrollToPositionWithOffset(index, 0)
    }

    // ------------------------------------------------------------------ share, set as, open with, Files

    /** A link other apps may read: Swift's file provider for a file, the other app's own link otherwise. */
    private fun linkFor(p: Pic): Uri? = p.file?.let { Docs.shareUri(this, it) } ?: p.source.uri

    private fun mimeOf(p: Pic) = p.info?.mime ?: UrlUtils.mimeFromName(p.name) ?: "image/*"

    private fun share() {
        val p = current()
        val uri = linkFor(p) ?: return say("Swift couldn't share this picture")
        try {
            val send = Intent(Intent.ACTION_SEND).setType(mimeOf(p)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share ${p.name}"))
        } catch (_: Exception) {
            say("No app on this phone can share it")
        }
    }

    /** Android's "Set as" list: the wallpaper apps, a contact's photo and the like. */
    private fun setAs() {
        val p = current()
        val uri = linkFor(p) ?: return say("Swift couldn't hand this picture over")
        val mime = mimeOf(p)
        val intent = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, mime).putExtra("mimeType", mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, "Set as"))
        } catch (_: Exception) {
            say("No app on this phone can use it as a wallpaper or a contact's photo")
        }
    }

    private fun openWith() {
        val p = current()
        val uri = linkFor(p) ?: return say("Swift couldn't hand this picture over")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(p)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, "Open with").putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MediaViewerActivity::class.java))))
        } catch (_: Exception) {
            say("No app on this phone can open it")
        }
    }

    /** ⋮: turning, the slideshow, and handing the picture to other apps or to Files. */
    private fun more() {
        val p = current()
        val list = ArrayList<Sheets.Action>()
        if (p.file != null) {
            list += Sheets.Action(R.drawable.ic_pic_rotate_left, "Rotate left", primary = true) { turn(p, -1) }
            list += Sheets.Action(R.drawable.ic_pic_rotate_right, "Rotate right", primary = true) { turn(p, 1) }
        }
        if (pics.size > 1) list += Sheets.Action(R.drawable.ic_pic_slideshow, "Slideshow", primary = true) { startSlideshow() }
        list += Sheets.Action(R.drawable.ic_pic_wallpaper, "Set as wallpaper or contact photo") { setAs() }
        list += Sheets.Action(R.drawable.ic_reopen, "Open with another app") { openWith() }
        p.file?.parentFile?.let { dir ->
            list += Sheets.Action(R.drawable.ic_folder, "Show in Files") {
                FilesActivity.open(this, dir)
                finish()
            }
        }
        val sub = listOfNotNull(p.size.takeIf { it >= 0 }?.let { UrlUtils.formatBytes(it) }, p.file?.parent).joinToString(" · ")
        Sheets.actions(this, p.name, sub, list, iconRes = R.drawable.ic_image)
    }

    private fun showInfo() {
        val p = current()
        val info = p.info
        work.execute {
            val details = PictureInfo.read(this, p.source, p.name, p.size, info)
            handler.post { if (!isDestroyed && !isFinishing) PictureInfo.show(this, details) }
        }
    }

    // ------------------------------------------------------------------ delete

    private fun askDelete() {
        val p = current()
        val f = p.file ?: return
        val delete = {
            // 3.0: the recycle bin on a memory card needs Swift's access to that card too.
            withChanges(listOf(f) + com.swiftbrowser.files.RecycleBin.binsFor(this, listOf(f))) {
                MediaDelete.delete(this, listOf(f)) { deleted ->
                    if (isDestroyed) return@delete
                    if (deleted == 0) say("Swift couldn't delete “${p.name}”") else removed(p)
                }
            }
        }
        // 2.9: into the recycle bin at once (its message offers Undo); asked first only when it
        // would be deleted for good.
        if (MediaDelete.canUseBin(this, listOf(f))) {
            delete()
        } else {
            Dialogs.confirm(this, "Delete “${p.name}”?", "Swift can't bring it back.", "Delete", danger = true) { delete() }
        }
    }

    /** [p] is gone: the next picture takes its place (the one before, at the end); none left, the screen closes. */
    private fun removed(p: Pic) {
        val at = pics.indexOf(p)
        if (at < 0) return
        pics.removeAt(at)
        bitmaps.remove(p.key)
        if (pics.isEmpty()) {
            finish()
            return
        }
        pagerAdapter.notifyItemRemoved(at)
        stripAdapter.notifyItemRemoved(at)
        if (at < index) index--
        marked = -1
        if (pics.size < 2) strip.visibility = View.GONE
        goTo(index)
        say("Deleted “${p.name}”")
    }

    // ------------------------------------------------------------------ turning and editing

    /** Turns [p]'s file a quarter turn ([turns] 1: right, -1: left), with Undo. */
    private fun turn(p: Pic, turns: Int, undo: Boolean = false) {
        val f = p.file ?: return
        val info = p.info ?: return say("The picture is still opening. Try again in a moment.")
        if (!PictureEdit.keepsKind(info)) return say(PictureEdit.whyNotInPlace(info), long = true)
        withChanges(listOf(f)) {
            // A JPEG only changes a tag (at once); others are written again, which takes a moment.
            val note = if (info.mime == "image/jpeg") null else Messages.sticky(this, "Turning the picture…")
            work.execute {
                val problem = try {
                    PictureEdit.turnFile(this, p.source, info, turns)
                    null
                } catch (e: PictureEdit.Problem) {
                    e.message
                } catch (e: OutOfMemoryError) {
                    "This phone ran out of memory for this picture"
                } catch (e: Exception) {
                    L.w("Couldn't turn a picture", e)
                    "Swift couldn't turn the picture"
                }
                handler.post {
                    note?.dismiss()
                    if (isDestroyed) return@post
                    if (problem != null) return@post say(problem, long = true)
                    refresh(p)
                    if (!undo) say(if (turns > 0) "Turned right" else "Turned left", "Undo") { turn(p, -turns, undo = true) }
                }
            }
        }
    }

    /** [p]'s file was written again: its new picture, size and small picture. */
    private fun refresh(p: Pic) {
        val f = p.file ?: return
        p.time = f.lastModified()
        p.size = f.length()
        p.info = null
        p.failed = false
        bitmaps.remove(p.key)
        val key = p.key
        detailer.execute {
            if (regionKey == key) {
                region?.recycle()
                region = null
                regionKey = null
            }
        }
        for (i in 0 until pager.childCount) (pager.getChildAt(i) as? PictureView)?.let { if (it.tag === p) show(it, p, again = true) }
        val at = pics.indexOf(p)
        if (at >= 0 && strip.visibility == View.VISIBLE) stripAdapter.notifyItemChanged(at)
        updateTitle()
    }

    private var editor: PictureEditor? = null

    private fun edit() {
        val p = current()
        if (p.failed) return say(CANT_SHOW)
        // The editor shows the picture at the screen's size (at once when it is in memory).
        load(p, urgent = true) { preview ->
            val info = p.info
            if (preview == null || info == null || p !== current() || editor != null || slides != null || isFinishing) return@load
            openEditor(p, info, preview)
        }
    }

    private fun openEditor(p: Pic, info: Pictures.Info, preview: Bitmap) {
        val ed = PictureEditor(this, preview, onSave = { turns, crop -> saveEdit(p, info, turns, crop) }, onClose = { closeEditor() })
        editor = ed
        root.addView(ed.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Motion.fadeIn(ed.view, fromDy = 0)
    }

    private fun closeEditor() {
        val ed = editor ?: return
        editor = null
        Motion.fadeOut(ed.view, toDy = 0) { root.removeView(ed.view) }
    }

    /** Save: a copy next to the original, or over it (asked twice); another app's picture: a copy in Pictures/Swift. */
    private fun saveEdit(p: Pic, info: Pictures.Info, turns: Int, crop: RectF) {
        val ext = PictureEdit.extension(info, p.name)
        val file = p.file
        if (file == null) {
            val dir = File(Volumes.internalRoot(), Environment.DIRECTORY_PICTURES + "/Swift")
            withWriteAccess { writeEdit(p, info, turns, crop, PictureEdit.copyIn(dir, p.name, ext), replace = false) }
            return
        }
        val dir = file.parentFile ?: return
        if (!PictureEdit.keepsKind(info)) {
            // GIF and BMP can't be written back as they were: a PNG copy.
            withChanges(listOf(dir)) { writeEdit(p, info, turns, crop, PictureEdit.copyIn(dir, p.name, ext), replace = false) }
            return
        }
        val copy = PictureEdit.copyIn(dir, p.name, ext)
        MaterialAlertDialogBuilder(this)
            .setTitle("Save the edited picture")
            .setItems(arrayOf("Save as a copy (“${copy.name}”)", "Replace the original")) { _, which ->
                if (which == 0) {
                    withChanges(listOf(dir)) { writeEdit(p, info, turns, crop, PictureEdit.copyIn(dir, p.name, ext), replace = false) }
                } else {
                    Dialogs.confirm(this, "Replace “${p.name}”?", "The original picture is overwritten with the edited one. This can't be undone.", "Replace", danger = true) {
                        withChanges(listOf(file)) { writeEdit(p, info, turns, crop, file, replace = true) }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun writeEdit(p: Pic, info: Pictures.Info, turns: Int, crop: RectF, dest: File, replace: Boolean) {
        val ed = editor
        ed?.setBusy(true)
        // Room for the full-size picture: the pictures on standby go (they are decoded again when
        // needed); the one on screen stays, its page holds it anyway.
        val shown = bitmaps.get(p.key)
        bitmaps.evictAll()
        if (shown != null) bitmaps.put(p.key, shown)
        val note = Messages.sticky(this, "Saving the picture…")
        work.execute {
            var saved: PictureEdit.Saved? = null
            val problem = try {
                saved = PictureEdit.save(this, p.source, info, turns, crop, dest, fullSizeOnly = replace)
                null
            } catch (e: PictureEdit.Problem) {
                e.message
            } catch (e: OutOfMemoryError) {
                "This phone ran out of memory for this picture. Close some tabs and try again."
            } catch (e: Exception) {
                L.w("Couldn't save an edited picture", e)
                "Swift couldn't save the picture"
            }
            handler.post {
                note?.dismiss()
                if (isDestroyed) return@post
                ed?.setBusy(false)
                val s = saved ?: return@post say(problem ?: "Swift couldn't save the picture", long = true)
                if (editor === ed) closeEditor()
                edited(p, s, replace, PictureEdit.pngForLossless(info))
            }
        }
    }

    private fun edited(p: Pic, s: PictureEdit.Saved, replace: Boolean, asPng: Boolean = false) {
        // A picture too big to edit at full size on this phone was saved smaller: said, not hidden.
        val smaller = when (s.sample) {
            1 -> ""
            2 -> " It is half the size (${s.width} × ${s.height}): the full picture is too big for this phone to edit."
            4 -> " It is a quarter of the size (${s.width} × ${s.height}): the full picture is too big for this phone to edit."
            else -> " It is ${s.width} × ${s.height}, smaller than the original: the full picture is too big for this phone to edit."
        } + if (asPng) " It's a PNG: this phone can't save a WebP picture without losing some quality." else ""
        val long = smaller.isNotEmpty()
        when {
            replace -> {
                refresh(p)
                say("Picture saved")
            }
            p.file == null -> say("Saved in Pictures › Swift as “${s.file.name}”.$smaller", "Show", long) { open(this, s.file) }
            else -> {
                addCopy(s.file)
                say("Saved a copy: “${s.file.name}”.$smaller", long = long)
            }
        }
    }

    /** The saved copy joins the folder's pictures in its place by name, and is shown. */
    private fun addCopy(f: File) {
        val p = Pic(Pictures.Source.of(f), f.name, f.lastModified(), f.length())
        var at = pics.indexOfFirst { FileOps.naturalCompare(it.name, f.name) > 0 }
        if (at < 0) at = pics.size
        pics.add(at, p)
        pagerAdapter.notifyItemInserted(at)
        stripAdapter.notifyItemInserted(at)
        marked = -1
        if (pics.size > 1) strip.visibility = View.VISIBLE
        goTo(at)
    }

    // ------------------------------------------------------------------ slideshow

    private var slides: FrameLayout? = null
    private var slideViews = emptyArray<ImageView>()
    private var slideFront = 0
    private var slideAt = 0
    private val nextSlide = Runnable { slideshowStep() }

    /** Full screen, the next picture every 4 seconds with a cross-fade, round and round; a tap stops it. */
    private fun startSlideshow() {
        if (pics.size < 2 || slides != null) return
        toggleBars(false)
        val a = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val b = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f
        }
        val box = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            contentDescription = "Slideshow. Tap to stop."
            setOnClickListener { stopSlideshow() }
            addView(a, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        slides = box
        slideViews = arrayOf(a, b)
        slideFront = 0
        slideAt = index
        focus = index
        load(current(), urgent = true) { bmp -> if (slides === box && bmp != null && a.drawable == null) a.setImageBitmap(bmp) }
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.alpha = 0f
        // The pages underneath aren't drawn while it plays (the slow graphics chip has enough to do).
        box.animate().alpha(1f).setDuration(Motion.NORMAL).withEndAction { if (slides === box) pager.visibility = View.INVISIBLE }.start()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.postDelayed(nextSlide, SLIDE_MS)
        load(pics[(index + 1) % pics.size], urgent = true, done = null)
        Messages.show(this, "Slideshow. Tap to stop.")
    }

    private fun slideshowStep() {
        val box = slides ?: return
        val next = (slideAt + 1) % pics.size
        val p = pics[next]
        focus = next
        load(p, urgent = true) { bmp ->
            if (slides !== box) return@load
            slideAt = next
            if (bmp != null) {
                val front = slideViews[slideFront]
                val back = slideViews[1 - slideFront]
                back.setImageBitmap(bmp)
                back.alpha = 0f
                back.bringToFront()
                back.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
                    front.setImageDrawable(null)
                    front.alpha = 0f
                }.start()
                slideFront = 1 - slideFront
            }
            handler.postDelayed(nextSlide, SLIDE_MS)
            // The one after, ready in time.
            pics.getOrNull((next + 1) % pics.size)?.let { load(it, urgent = true, done = null) }
        }
    }

    /** Back to the pictures, at the one the slideshow had reached. */
    private fun stopSlideshow() {
        val box = slides ?: return
        slides = null
        handler.removeCallbacks(nextSlide)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pager.visibility = View.VISIBLE
        if (slideAt != index && slideAt in pics.indices) goTo(slideAt) else focus = index
        box.animate().cancel()
        box.animate().alpha(0f).setDuration(Motion.NORMAL).withEndAction { root.removeView(box) }.start()
        toggleBars(true)
    }

    // ------------------------------------------------------------------ permission to change files

    private var afterWrite: (() -> Unit)? = null
    private val askWrite = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val run = afterWrite
        afterWrite = null
        if (granted) run?.invoke() else say("Swift needs access to your files to change them")
    }

    /** Runs [action] once Swift may write to the phone's storage (Android asks the first time). */
    private fun withWriteAccess(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > 28 || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterWrite = action
            askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private var cardAsked: Volumes.Volume? = null
    private var afterCard: (() -> Unit)? = null
    private var cardTriedPicker = false
    private val askCard: androidx.activity.result.ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val v = cardAsked ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK && Volumes.onAccessResult(this, v, r.data)) {
            val run = afterCard
            afterCard = null
            run?.invoke()
        } else if (r.resultCode == RESULT_OK) {
            afterCard = null
            Dialogs.info(this, "Choose the SD card itself", "Swift needs the whole SD card, not a folder in it. Try again and pick the card at the top.")
        } else if (!cardTriedPicker) {
            // The short question was refused or isn't there: Android's folder picker instead.
            cardTriedPicker = true
            try {
                askCard.launch(Volumes.pickerIntent(v))
            } catch (_: Exception) {
                afterCard = null
                say(Transfers.CARD_TEXT, long = true)
            }
        } else {
            afterCard = null
            say("Swift can only read the SD card until you allow it to change files there", long = true)
        }
    }

    /** Runs [action] once Swift may change [files] (as Files does: on an SD card Android is asked once, for good). */
    private fun withChanges(files: Collection<File>, action: () -> Unit) = withWriteAccess {
        val v = Volumes.cardWithoutAccess(this, files)
        if (v == null) {
            action()
            return@withWriteAccess
        }
        Dialogs.confirm(
            this, "Allow changes on the SD card",
            "Android lets Swift change files on the SD card only once you allow it. On the next screen, tap Allow (or choose the SD card and tap Select).",
            "Continue",
        ) {
            cardAsked = v
            afterCard = action
            cardTriedPicker = false
            try {
                askCard.launch(Volumes.accessIntent(this, v))
            } catch (e: Exception) {
                L.w("No way to ask for the card", e)
                afterCard = null
                say(Transfers.CARD_TEXT, long = true)
            }
        }
    }

    // ------------------------------------------------------------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.apply(this)
    }

    override fun onResume() {
        super.onResume()
        NightLight.attach(this)
    }

    override fun onPause() {
        // A slideshow doesn't go on out of sight (nor keep the screen on).
        stopSlideshow()
        super.onPause()
    }

    /** Short of memory: the pictures on standby go (they are decoded again when needed). */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) bitmaps.evictAll()
    }

    override fun onDestroy() {
        if (::root.isInitialized) {
            handler.removeCallbacksAndMessages(null)
            bitmaps.evictAll()
        }
        decoder.shutdownNow()
        detailer.execute {
            region?.recycle()
            region = null
        }
        detailer.shutdown()
        // An edit being written is finished first.
        work.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val CANT_SHOW = "Swift can't show this picture"
        private const val MARK = "mark"
        private const val SLIDE_MS = 4000L
        private const val FADE_MS = 700L
        private val ids = AtomicLong()

        private fun thread(name: String): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, name) }

        private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        private val VIDEOS = setOf("mp4", "m4v", "3gp", "3g2", "webm", "mkv", "mov", "ts")
        private val SOUNDS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid", "midi", "wma")

        /**
         * What a file of that name is, for opening it; null for anything else. 2.9: only pictures
         * open on this screen; VIDEO and AUDIO tell callers to use the player.
         */
        fun kindOf(name: String): Kind? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            in IMAGES -> Kind.IMAGE
            in VIDEOS -> Kind.VIDEO
            in SOUNDS -> Kind.AUDIO
            else -> null
        }

        /**
         * Shows picture [file] with its folder around it. 3.0: [secure] for a picture of Private (kept
         * out of screenshots); Swift's own opens are marked as such (ViewerAccess).
         */
        fun open(from: Context, file: File, secure: Boolean = false) {
            if (kindOf(file.name) != Kind.IMAGE) {
                // 2.9: not a picture (a video or a song, until callers send those to the player): another app.
                if (from is Activity) FileOpener.openWith(from, file) else L.w("Not a picture for the picture viewer")
                return
            }
            val i = intentFor(from, file)
            if (secure) i.putExtra(com.swiftbrowser.core.AppFiles.EXTRA_SECURE, true)
            if (from !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            from.startActivity(i)
        }

        /** 3.0: what shows picture [file] (a finished download's notification too). */
        fun intentFor(ctx: Context, file: File): Intent = ViewerAccess.mark(Intent(ctx, MediaViewerActivity::class.java).putExtra(EXTRA_PATH, file.path))
    }
}
