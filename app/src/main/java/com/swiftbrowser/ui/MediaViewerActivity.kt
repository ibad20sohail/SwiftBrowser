package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.Docs
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Pictures, videos and music opened from Files (2.8), on a screen of their own rather than a browser
 * tab: pictures zoom (pinch, double tap) and swipe to the next one in the folder; videos and music
 * play with Android's own player, with a seek bar, and music goes on to the next song of the folder.
 * The bars hide with a tap, so the picture or video has the whole screen.
 */
class MediaViewerActivity : AppCompatActivity() {
    enum class Kind { IMAGE, VIDEO, AUDIO }

    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var title: TextView
    private lateinit var count: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var barsShown = true

    /** The files of this kind in the folder, by name, and the one on screen. */
    private var files: List<File> = emptyList()
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        Looks.apply(this)
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }
        val kind = file?.let { kindOf(it.name) }
        if (file == null || kind == null || !file.canRead()) {
            Messages.show(this, "Swift can't open this file")
            finish()
            return
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        files = siblings(file, kind)
        index = files.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        // The bar first (the music screen names its song in it), drawn over the picture or video.
        buildTopBar(kind)
        updateTitle()
        when (kind) {
            Kind.IMAGE -> showPictures()
            Kind.VIDEO -> showVideo(file)
            Kind.AUDIO -> showMusic()
        }
        topBar.bringToFront()
        SystemBars.keepHidden(this)
    }

    /** The other files of the same kind in the folder, by name (as Files lists them). */
    private fun siblings(file: File, kind: Kind): List<File> {
        val all = file.parentFile?.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && kindOf(it.name) == kind }
        return (all ?: listOf(file)).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).ifEmpty { listOf(file) }
    }

    private fun current(): File = files[index.coerceIn(0, files.lastIndex)]

    // ------------------------------------------------------------------ the bar at the top

    private fun buildTopBar(kind: Kind) {
        val white = Color.WHITE
        fun button(icon: Int, label: String, click: () -> Unit) = ImageButton(this, null, 0, R.style.Swift_ToolbarButton).apply {
            setImageDrawable(Ui.icon(this@MediaViewerActivity, icon, white))
            contentDescription = label
            setOnClickListener { click() }
        }
        title = TextView(this).apply {
            setTextColor(white)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        count = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12.5f
            isSingleLine = true
        }
        val size = Ui.dp(this, 48)
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MediaViewerActivity, 4), 0, Ui.dp(this@MediaViewerActivity, 4), 0)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000),
            )
            addView(button(R.drawable.ic_back, "Back") { finish() }, LinearLayout.LayoutParams(size, size))
            addView(LinearLayout(this@MediaViewerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@MediaViewerActivity, 8), 0, 0, 0)
                addView(title)
                addView(count)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button(R.drawable.ic_share, "Share") { share() }, LinearLayout.LayoutParams(size, size))
            addView(button(R.drawable.ic_more, "More") { more() }, LinearLayout.LayoutParams(size, size))
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64), Gravity.TOP))
        if (kind == Kind.AUDIO) topBar.background = null
    }

    private fun updateTitle() {
        val f = current()
        title.text = f.name
        val where = if (files.size > 1) "${index + 1} of ${files.size} · " else ""
        count.text = where + UrlUtils.formatBytes(f.length())
    }

    private fun share() {
        val f = current()
        val uri = Docs.shareUri(this, f) ?: return Messages.show(this, "Swift couldn't share this file")
        val type = UrlUtils.mimeFromName(f.name) ?: "*/*"
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}"))
        } catch (_: Exception) {
            Messages.show(this, "No app on this phone can share it")
        }
    }

    private fun more() {
        val f = current()
        Sheets.actions(this, f.name, listOfNotNull(UrlUtils.formatBytes(f.length()), f.parent).joinToString(" · "), listOf(
            Sheets.Action(R.drawable.ic_reopen, "Open with another app") { openWith() },
            Sheets.Action(R.drawable.ic_folder, "Show in Files") {
                FilesActivity.open(this, f.parentFile)
                finish()
            },
            Sheets.Action(R.drawable.ic_info, "Details") {
                val changed = DateUtils.formatDateTime(this, f.lastModified(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
                Messages.show(this, "${UrlUtils.formatBytes(f.length())} · changed $changed\n${f.path}", long = true)
            },
        ), null)
    }

    private fun openWith() {
        val f = current()
        val uri = Docs.shareUri(this, f) ?: return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, UrlUtils.mimeFromName(f.name) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, "Open with").putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(this, MediaViewerActivity::class.java))))
        } catch (_: Exception) {
            Messages.show(this, "No app on this phone can open it")
        }
    }

    /** A tap shows or hides the bars (pictures and videos have the whole screen without them). */
    private fun toggleBars(show: Boolean = !barsShown) {
        barsShown = show
        topBar.animate().alpha(if (show) 1f else 0f).setDuration(Motion.SHORT).withEndAction {
            topBar.visibility = if (barsShown) View.VISIBLE else View.INVISIBLE
        }.start()
        if (show) topBar.visibility = View.VISIBLE
        controls?.let { c ->
            c.animate().alpha(if (show) 1f else 0f).setDuration(Motion.SHORT).withEndAction {
                c.visibility = if (barsShown) View.VISIBLE else View.INVISIBLE
            }.start()
            if (show) c.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ pictures

    private val decoder = Executors.newSingleThreadExecutor()

    /** Pictures decoded at about the screen's size (a camera photo in full would not fit in memory). */
    private val bitmaps = object : LruCache<String, Bitmap>(3) {}

    @SuppressLint("NotifyDataSetChanged")
    private fun showPictures() {
        val pager = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MediaViewerActivity, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
            itemAnimator = null
        }
        PagerSnapHelper().attachToRecyclerView(pager)
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = files.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = ZoomImageView(this@MediaViewerActivity).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    onTap = { toggleBars() }
                }
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val v = holder.itemView as ZoomImageView
                val f = files[position]
                v.tag = f.path
                v.setPicture(bitmaps.get(f.path))
                if (bitmaps.get(f.path) == null) loadPicture(f) { bmp -> if (v.tag == f.path) v.setPicture(bmp) }
            }
        }
        pager.scrollToPosition(index)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val pos = (rv.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
                if (pos >= 0 && pos != index) {
                    index = pos
                    updateTitle()
                }
            }
        })
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun loadPicture(f: File, done: (Bitmap?) -> Unit) {
        val dm = resources.displayMetrics
        val max = maxOf(dm.widthPixels, dm.heightPixels).coerceAtMost(2048)
        decoder.execute {
            val bmp = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= max || bounds.outHeight / (sample * 2) >= max) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.let { rotated(f, it) }
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                L.w("Couldn't show a picture", e)
                null
            }
            handler.post {
                if (isDestroyed) return@post
                if (bmp != null) bitmaps.put(f.path, bmp) else Messages.show(this, "Swift can't show ${f.name}")
                done(bmp)
            }
        }
    }

    /** Camera photos say which way up they are (EXIF): turned to match, as galleries show them. */
    private fun rotated(f: File, bmp: Bitmap): Bitmap {
        val ext = f.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext != "jpg" && ext != "jpeg") return bmp
        val degrees = try {
            when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        if (degrees == 0f) return bmp
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true).also { if (it !== bmp) bmp.recycle() }
        } catch (_: OutOfMemoryError) {
            bmp
        }
    }

    // ------------------------------------------------------------------ videos and music: the controls

    private var controls: LinearLayout? = null
    private lateinit var playButton: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var length: TextView
    private var seeking = false

    private fun buildControls(withSkip: Boolean, onPlay: () -> Unit, onSeek: (Int) -> Unit, onSkip: (Int) -> Unit) {
        val white = Color.WHITE
        fun button(icon: Int, label: String, sizeDp: Int, click: () -> Unit) = ImageButton(this, null, 0, R.style.Swift_ToolbarButton).apply {
            setImageDrawable(Ui.icon(this@MediaViewerActivity, icon, white))
            contentDescription = label
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = Ui.dp(this@MediaViewerActivity, sizeDp / 5)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { click() }
        }
        elapsed = TextView(this).apply { setTextColor(white); textSize = 12.5f }
        length = TextView(this).apply { setTextColor(white); textSize = 12.5f }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(Ui.accent(this@MediaViewerActivity))
            thumbTintList = ColorStateList.valueOf(Ui.accent(this@MediaViewerActivity))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = time(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    seeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    onSeek(bar.progress)
                }
            })
        }
        playButton = button(R.drawable.ic_pause, "Pause", 64) { onPlay() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (withSkip) addView(button(R.drawable.ic_media_previous, "Previous", 52) { onSkip(-1) }, LinearLayout.LayoutParams(Ui.dp(this@MediaViewerActivity, 52), Ui.dp(this@MediaViewerActivity, 52)))
            addView(playButton, LinearLayout.LayoutParams(Ui.dp(this@MediaViewerActivity, 64), Ui.dp(this@MediaViewerActivity, 64)).apply {
                setMargins(Ui.dp(this@MediaViewerActivity, 24), 0, Ui.dp(this@MediaViewerActivity, 24), 0)
            })
            if (withSkip) addView(button(R.drawable.ic_media_next, "Next", 52) { onSkip(1) }, LinearLayout.LayoutParams(Ui.dp(this@MediaViewerActivity, 52), Ui.dp(this@MediaViewerActivity, 52)))
        }
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MediaViewerActivity, 16), Ui.dp(this@MediaViewerActivity, 12), Ui.dp(this@MediaViewerActivity, 16), Ui.dp(this@MediaViewerActivity, 16))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xAA000000.toInt(), 0x00000000),
            )
            addView(LinearLayout(this@MediaViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(elapsed)
                addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(length)
            })
            addView(row)
        }
        controls = c
        root.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun time(ms: Int) = DateUtils.formatElapsedTime((ms / 1000).toLong())

    private fun setPlaying(playing: Boolean) {
        playButton.setImageDrawable(Ui.icon(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play, Color.WHITE))
        playButton.contentDescription = if (playing) "Pause" else "Play"
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ videos

    private var video: VideoView? = null

    /** Hides the bars a few seconds after they were shown, while the video plays. */
    private val autoHide = Runnable { if (video?.isPlaying == true && barsShown) toggleBars(false) }

    private val ticker = object : Runnable {
        override fun run() {
            val pos = video?.currentPosition ?: player?.currentPosition ?: return
            if (!seeking) {
                seek.progress = pos
                elapsed.text = time(pos)
            }
            handler.postDelayed(this, 500)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showVideo(file: File) {
        val v = VideoView(this)
        video = v
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // Taps anywhere, on the video too, show and hide the controls.
        val tap = View.OnClickListener {
            toggleBars()
            handler.removeCallbacks(autoHide)
            if (barsShown) handler.postDelayed(autoHide, 3500)
        }
        root.setOnClickListener(tap)
        v.setOnClickListener(tap)
        buildControls(withSkip = false, onPlay = {
            if (v.isPlaying) v.pause() else v.start()
            setPlaying(v.isPlaying)
            handler.removeCallbacks(autoHide)
            if (v.isPlaying) handler.postDelayed(autoHide, 3500)
        }, onSeek = { v.seekTo(it) }, onSkip = {})
        v.setOnPreparedListener { mp ->
            seek.max = mp.duration
            length.text = time(mp.duration)
            v.start()
            setPlaying(true)
            handler.post(ticker)
            handler.postDelayed(autoHide, 3500)
        }
        v.setOnCompletionListener {
            setPlaying(false)
            toggleBars(true)
        }
        v.setOnErrorListener { _, what, extra ->
            L.w("Video couldn't play ($what, $extra)")
            Messages.show(this, "This phone can't play this video here. Try Open with another app.", long = true)
            true
        }
        v.setVideoURI(Uri.fromFile(file))
    }

    // ------------------------------------------------------------------ music

    private var player: MediaPlayer? = null
    private lateinit var art: ImageView
    private lateinit var artist: TextView

    private fun showMusic() {
        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.shape(this@MediaViewerActivity, 0xFF22252B.toInt(), 16)
            clipToOutline = true
        }
        val songTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            id = View.generateViewId()
            tag = "song"
        }
        artist = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            isSingleLine = true
        }
        val side = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.7f).toInt()
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(art, LinearLayout.LayoutParams(side, side))
            addView(songTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@MediaViewerActivity, 24) })
            addView(artist, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@MediaViewerActivity, 4) })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            // Between the bar at the top and the controls at the bottom.
            setMargins(Ui.dp(this@MediaViewerActivity, 24), Ui.dp(this@MediaViewerActivity, 72), Ui.dp(this@MediaViewerActivity, 24), Ui.dp(this@MediaViewerActivity, 140))
        })
        buildControls(withSkip = files.size > 1, onPlay = {
            val p = player ?: return@buildControls
            if (p.isPlaying) p.pause() else p.start()
            setPlaying(p.isPlaying)
        }, onSeek = { player?.seekTo(it) }, onSkip = { step -> playSong(index + step) })
        playSong(index)
    }

    /** Plays song [i] of the folder (wrapping round), with its name, artist and cover. */
    private fun playSong(i: Int) {
        if (files.isEmpty()) return
        index = (i + files.size) % files.size
        val f = current()
        updateTitle()
        val meta = MediaMetadataRetriever()
        var name: String? = null
        var by: String? = null
        var cover: Bitmap? = null
        try {
            meta.setDataSource(f.path)
            name = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            by = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            cover = meta.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 2 }) }
        } catch (_: Exception) {
        } finally {
            try {
                meta.release()
            } catch (_: Exception) {
            }
        }
        root.findViewWithTag<TextView>("song")?.text = name?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension
        artist.text = by.orEmpty()
        if (cover != null) {
            art.scaleType = ImageView.ScaleType.CENTER_CROP
            art.setImageBitmap(cover)
        } else {
            art.scaleType = ImageView.ScaleType.CENTER
            art.setImageDrawable(Ui.icon(this, R.drawable.ic_headphones, 0x99FFFFFF.toInt()))
        }
        player?.release()
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(f.path)
            p.setOnPreparedListener {
                seek.max = it.duration
                length.text = time(it.duration)
                it.start()
                setPlaying(true)
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            // The next song of the folder, as music players do; the last one stops.
            p.setOnCompletionListener { if (index < files.lastIndex) playSong(index + 1) else setPlaying(false) }
            p.setOnErrorListener { _, what, extra ->
                L.w("Song couldn't play ($what, $extra)")
                Messages.show(this, "This phone can't play ${f.name}")
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            L.w("Couldn't play a song", e)
            Messages.show(this, "This phone can't play ${f.name}")
        }
    }

    // ------------------------------------------------------------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.apply(this)
    }

    override fun onPause() {
        // Leaving the screen stops what plays (it isn't a music app running in the background).
        video?.let { if (it.isPlaying) it.pause() }
        player?.let { if (it.isPlaying) it.pause() }
        if (video != null || player != null) setPlaying(false)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        video?.stopPlayback()
        decoder.shutdownNow()
        bitmaps.evictAll()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"

        private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        private val VIDEOS = setOf("mp4", "m4v", "3gp", "3g2", "webm", "mkv", "mov", "ts")
        private val SOUNDS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid", "midi", "wma")

        /** What this screen shows for a file of that name; null for anything else. */
        fun kindOf(name: String): Kind? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            in IMAGES -> Kind.IMAGE
            in VIDEOS -> Kind.VIDEO
            in SOUNDS -> Kind.AUDIO
            else -> null
        }

        fun open(from: Context, file: File) {
            val i = Intent(from, MediaViewerActivity::class.java).putExtra(EXTRA_PATH, file.path)
            if (from !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            from.startActivity(i)
        }
    }
}

/**
 * A picture that fits the screen and zooms: pinch, double tap (in and back), and drag when zoomed.
 * While zoomed it keeps the finger (the pictures don't change under a drag); zoomed out, a swipe
 * goes to the next picture.
 */
class ZoomImageView(context: Context) : ImageView(context) {
    var onTap: (() -> Unit)? = null
    private val drawMatrix = Matrix()
    private var zoom = 1f
    private val bounds = RectF()

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setPicture(bmp: Bitmap?) {
        setImageBitmap(bmp)
        zoom = 1f
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        zoom = 1f
        fit()
    }

    /** The whole picture in the middle of the view. */
    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        val s = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        drawMatrix.reset()
        drawMatrix.postScale(s, s)
        drawMatrix.postTranslate((width - d.intrinsicWidth * s) / 2f, (height - d.intrinsicHeight * s) / 2f)
        imageMatrix = drawMatrix
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val target = (zoom * factor).coerceIn(1f, 5f)
        val f = target / zoom
        zoom = target
        drawMatrix.postScale(f, f, fx, fy)
        keepInside()
    }

    /** No empty edge where the picture could still fill the view; centred when smaller than it. */
    private fun keepInside() {
        val d = drawable ?: return
        bounds.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(bounds)
        val dx = when {
            bounds.width() <= width -> width / 2f - bounds.centerX()
            bounds.left > 0 -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> height / 2f - bounds.centerY()
            bounds.top > 0 -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
        if (zoom <= 1.001f) fit()
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) {
                zoom = 1f
                fit()
            } else {
                zoomBy(2.5f, e.x, e.y)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1.01f) return false
            drawMatrix.postTranslate(-dx, -dy)
            keepInside()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        // Zoomed in or pinching: the pager doesn't take the drag.
        parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f || event.pointerCount > 1 || scaler.isInProgress)
        return true
    }
}
