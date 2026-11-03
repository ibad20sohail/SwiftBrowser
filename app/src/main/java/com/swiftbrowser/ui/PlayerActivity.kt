package com.swiftbrowser.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.AppFiles
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.media.LocalMedia
import com.swiftbrowser.media.LocalPlayback
import com.swiftbrowser.media.MusicService
import com.swiftbrowser.media.PlayerSettings
import com.swiftbrowser.media.Subtitles
import com.swiftbrowser.media.WatchPositions
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 2.9: Swift's video and music player, on a screen of its own: videos and songs from Files (the rest
 * of the folder is the playlist), files other apps open with Swift, and website videos. AndroidX
 * Media3's ExoPlayer plays them with the phone's hardware decoders onto a SurfaceView, the lightest
 * way to show video on a slow graphics chip.
 *
 * Videos fill the black screen, with VLC's gestures (see PlayerGestures) and controls that hide
 * while playing; music shows its cover in Swift's colours and goes on in the background
 * (MusicService), the player being shared with it (LocalPlayback). Menus are bottom sheets.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity(), PlayerGestures.Host, LocalPlayback.Watcher {
    enum class Kind { VIDEO, AUDIO }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager

    private lateinit var root: FrameLayout
    private lateinit var frame: VideoFrame
    private lateinit var surface: SurfaceView
    private lateinit var music: LinearLayout
    private lateinit var cover: ImageView
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var subtitleText: TextView
    private lateinit var subtitlePicture: ImageView
    private lateinit var gestures: PlayerGestures
    private lateinit var hud: LinearLayout
    private lateinit var hudIcon: ImageView
    private lateinit var hudText: TextView
    private lateinit var hudSub: TextView
    private lateinit var hudLevel: ProgressBar
    private lateinit var spinner: ProgressBar
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var countView: TextView
    private lateinit var controls: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var length: TextView
    private lateinit var playButton: ImageButton
    private lateinit var lockButton: ImageButton
    private lateinit var fitButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var rotateButton: ImageButton
    private lateinit var subtitlesButton: ImageButton
    private lateinit var playlistButton: ImageButton
    private lateinit var unlockButton: ImageButton
    private lateinit var boostBadge: TextView

    // 3.0.2: the redone controls (after VLC's player).
    private lateinit var chips: LinearLayout
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var back10: ImageButton
    private lateinit var forward10: ImageButton
    private lateinit var speedButton: ImageButton
    private var chipsShown = ""
    private var problem: View? = null

    /** Icon buttons: white over a video, in the text colour on the music screen. */
    private val icons = ArrayList<ImageButton>()

    private var player: ExoPlayer? = null
    private var videoMode = true
    private var controlsShown = true
    private var locked = false
    private var gesturesEnabled = true

    /** Closed on purpose (Back, Close): what plays was dealt with already. */
    private var closing = false
    private var showRemaining = false

    /** A finger on the seek bar or a sideways swipe: the bar shows where it goes, not where it is. */
    private var scrubbing = false
    private var previewSeeks = false
    private var lastPreviewAt = 0L

    /** The video or song the screen shows (to notice another one starting). */
    private var shownEntry: LocalPlayback.Entry? = null
    private var warnedTracks: LocalPlayback.Entry? = null

    /** 3.0.1: the video whose decoding by the chip was looked at (once each). */
    private var hardwareChecked: LocalPlayback.Entry? = null

    // Gestures in progress.
    private var swipeFrom = 0f
    private var lastVolume = -1
    private var boostLimit = 1f
    private var seekFrom = 0L
    private var seekTarget = 0L
    private var holdSpeed = 0f
    private var tapSide = 0
    private var tapAt = 0L
    private var tapTotal = 0L

    // Subtitles: a file shown by this screen, or the video's own (shown through the player).
    private var subFile: Subtitles.Track? = null
    private var subFiles: List<File> = emptyList()
    private val subAdded = ArrayList<Subtitles.Track>()
    private var subOff = false
    private var subDelayMs = 0L
    private var subShown: List<Cue> = emptyList()
    private var subToken = 0
    private var resumeAfterPick = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Looks.apply(this)
        super.onCreate(savedInstanceState)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // The volume keys change the music volume here, also while nothing plays.
        volumeControlStream = AudioManager.STREAM_MUSIC
        buildViews()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 3.0: a locked screen stays (a pocket or a child's hand pressing Back).
                if (locked) {
                    showUnlock()
                    say("Unlock first: tap the lock")
                    return
                }
                close()
            }
        })
        SystemBars.keepHidden(this) { !videoMode }
        LocalPlayback.watch(this)
        val again = savedInstanceState != null || intent?.action == ACTION_SHOW
        if (again && LocalPlayback.player != null) {
            attach()
        } else if (intent?.action == ACTION_SHOW || !start(intent)) {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_SHOW) {
            attach()
            return
        }
        if (locked) setLocked(false)
        start(intent)
    }

    // ------------------------------------------------------------------ what to play

    /** Plays what [i] asks for; false if it asks for nothing this screen plays. */
    private fun start(i: Intent?): Boolean {
        hideProblem()
        return when (i?.action) {
            ACTION_PLAY_FILE -> {
                val path = i.getStringExtra(EXTRA_PATH) ?: return false
                val f = File(path)
                // 3.0: a path into Swift's own storage only from Swift itself (this screen is open to all apps).
                if (!ViewerAccess.mayOpen(this, i, f)) {
                    refuse(kindOf(f.name) != Kind.AUDIO)
                    return true
                }
                playFile(f, folder = true, incognito = i.getBooleanExtra(EXTRA_INCOGNITO, false), secure = ViewerAccess.secure(this, i, f))
                true
            }
            ACTION_PLAY_STREAM -> {
                val url = i.getStringExtra(EXTRA_URL) ?: return false
                // 3.0: a website video only from Swift's own pages (this run of Swift: one restored after
                // Android closed Swift isn't played again), and only from the web, never a file:// path.
                if (!ViewerAccess.isOwn(i) || !UrlUtils.isHttp(url)) return false
                val headers = i.getBundleExtra(EXTRA_HEADERS)?.let { b -> b.keySet().associateWith { b.getString(it).orEmpty() } } ?: emptyMap()
                playStream(url, i.getStringExtra(EXTRA_TITLE), headers, i.getBooleanExtra(EXTRA_INCOGNITO, false), i.getBooleanExtra(EXTRA_DASH, false))
                true
            }
            Intent.ACTION_VIEW -> {
                val data = i.data ?: return false
                // 3.0: another app's link to Swift's own files (its data folder, its file provider).
                if (ViewerAccess.refused(this, data)) {
                    L.w("Player: refused a link to Swift's own files from another app")
                    refuse(i.type?.startsWith("audio/") != true)
                    return true
                }
                playShared(data, i.type)
                true
            }
            else -> false
        }
    }

    /** 3.0: what this screen won't open, said on it (as for a file that can't be read). */
    private fun refuse(video: Boolean) {
        if (LocalPlayback.current != null) {
            // What plays goes on; the message says why the other wasn't opened.
            say("${ViewerAccess.REFUSED}.")
            return
        }
        applyMode(video)
        titleView.text = ""
        showProblem("${ViewerAccess.REFUSED}.", openWith = false, retry = false)
    }

    private fun entryFor(f: File, kind: Kind, incognito: Boolean = false, secure: Boolean = false) =
        LocalPlayback.Entry(Uri.fromFile(f), f.nameWithoutExtension, kind == Kind.VIDEO, file = f, incognito = incognito, secure = secure)

    /**
     * A file on the phone, where it was left off; with [folder], the folder's others are the playlist.
     * 3.0: [incognito] (an incognito download) and [secure] (a file of Private): nothing about them is
     * remembered or shown on the lock screen, and the screen is kept out of screenshots.
     */
    private fun playFile(f: File, folder: Boolean, incognito: Boolean = false, secure: Boolean = false) {
        val kind = kindOf(f.name) ?: Kind.VIDEO
        if (!f.canRead()) {
            applyMode(kind == Kind.VIDEO)
            titleView.text = f.name
            showProblem("Swift can't open this file. It may have been moved, or Swift isn't allowed to read it.", openWith = false, retry = false)
            return
        }
        val resume = if (incognito || secure) null else LocalMedia.position(this, f)
        val token = LocalPlayback.load(this, listOf(entryFor(f, kind, incognito, secure)), 0, resume ?: C.TIME_UNSET)
        attach()
        // Once the controls are laid out, so the message sits above them.
        if (resume != null) root.post { say("Going on from ${time(resume)}", "Start over") { LocalPlayback.seekTo(0) } }
        if (!folder) return
        // The rest of the folder is read without holding up the start, then put around it.
        LocalPlayback.io.execute {
            val (before, after) = folderAround(f, kind)
            mainHandler.post {
                LocalPlayback.addAround(token, before.map { entryFor(it, kind, incognito, secure) }, after.map { entryFor(it, kind, incognito, secure) })
            }
        }
    }

    /** A website video: HLS (.m3u8) or a plain file, fetched with the page's headers. */
    private fun playStream(url: String, title: String?, headers: Map<String, String>, incognito: Boolean, dashHint: Boolean = false) {
        val path = url.substringBefore('?').substringBefore('#')
        val hls = path.lowercase(Locale.ROOT).endsWith(".m3u8") || url.contains(".m3u8", ignoreCase = true)
        // 3.0.2: a DASH stream, by its address or as the page's own request said (a manifest without .mpd).
        val dash = !hls && (dashHint || path.lowercase(Locale.ROOT).endsWith(".mpd"))
        val name = title?.takeIf { it.isNotBlank() } ?: UrlUtils.host(url)?.removePrefix("www.") ?: "Video"
        val entry = LocalPlayback.Entry(Uri.parse(url), name, kindOf(path) != Kind.AUDIO, stream = true, hls = hls, dash = dash, headers = headers, incognito = incognito)
        LocalPlayback.load(this, listOf(entry), 0, C.TIME_UNSET)
        attach()
    }

    /** "Open with Swift" from another app: that one video or song (a shared file's folder can't be read). */
    private fun playShared(uri: Uri, type: String?) {
        when (uri.scheme) {
            "file" -> uri.path?.let { playFile(File(it), folder = false) }
            "http", "https" -> playStream(uri.toString(), null, emptyMap(), false)
            else -> {
                val mime = type ?: try {
                    contentResolver.getType(uri)
                } catch (_: Exception) {
                    null
                }
                val guess = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
                val video = when {
                    mime?.startsWith("audio/") == true -> false
                    mime?.startsWith("video/") == true -> true
                    else -> kindOf(guess) != Kind.AUDIO
                }
                val entry = LocalPlayback.Entry(uri, guess.substringBeforeLast('.').ifBlank { if (video) "Video" else "Song" }, video)
                LocalPlayback.load(this, listOf(entry), 0, C.TIME_UNSET)
                attach()
                L.d("Player: opened a ${if (video) "video" else "song"} from another app")
                LocalPlayback.io.execute {
                    val name = displayName(uri) ?: return@execute
                    mainHandler.post {
                        entry.title = name.substringBeforeLast('.').ifBlank { name }
                        LocalPlayback.refreshInfo()
                    }
                }
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
        null
    }

    /** The folder's other videos or songs, by name as people count (2 before 10): those before [f] and after it. */
    private fun folderAround(f: File, kind: Kind): Pair<List<File>, List<File>> {
        val none = emptyList<File>() to emptyList<File>()
        val all = f.parentFile?.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && kindOf(it.name) == kind }
            ?.sortedWith { a, b -> naturalCompare(a.name, b.name) } ?: return none
        val i = all.indexOfFirst { it.path == f.path }
        if (i < 0) return none
        // A folder of thousands: the 500 on each side are plenty.
        return all.subList(maxOf(0, i - 500), i).toList() to all.subList(i + 1, minOf(all.size, i + 501)).toList()
    }

    // ------------------------------------------------------------------ the shared player

    /** Shows the shared player on this screen: its picture, its state and what it plays. */
    private fun attach() {
        val p = LocalPlayback.player ?: return
        if (player !== p) {
            player?.let {
                it.removeListener(events)
                it.clearVideoSurfaceView(surface)
            }
            player = p
            p.addListener(events)
            p.setVideoSurfaceView(surface)
        }
        p.videoSize.let { frame.setVideoSize(it.width, it.height, it.pixelWidthHeightRatio) }
        onEntryChanged()
        updatePlaying()
        updateModes()
        updateProgress()
    }

    private val events = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                )
            ) {
                updatePlaying()
            }
            if (events.containsAny(Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) updateModes()
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) updateProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            handler.removeCallbacks(showSpinner)
            if (playbackState == Player.STATE_BUFFERING) {
                // Only a wait worth seeing: quick ones (a seek in a file) show nothing.
                handler.postDelayed(showSpinner, 400)
            } else {
                spinner.visibility = View.GONE
            }
            if (playbackState != Player.STATE_IDLE) hideProblem()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = onEntryChanged()

        override fun onTracksChanged(tracks: Tracks) = checkTracks(tracks)

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            frame.setVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            applyRotation()
        }

        override fun onCues(cueGroup: CueGroup) {
            if (subFile == null) showCues(cueGroup.cues)
        }

        override fun onPlayerError(error: PlaybackException) = showError(error)
    }

    /** Another video or song plays (or the screen came back to one): its look, title and subtitles. */
    private fun onEntryChanged() {
        val e = LocalPlayback.current ?: return
        if (e !== shownEntry) {
            shownEntry = e
            warnedTracks = null
            hideProblem()
            applyMode(e.video)
            loadSubtitles(e)
            // 3.0: a file of Private is kept out of screenshots and recent apps; an incognito one too
            // unless Settings allow screenshots in incognito.
            if (e.secure) SecureScreen.set(this, true) else SecureScreen.forIncognito(this, e.incognito)
        }
        updateInfo()
        updateProgress()
        updateBoost()
    }

    override fun onInfoChanged() {
        val e = LocalPlayback.current ?: return
        if (e === shownEntry && e.video != videoMode) applyMode(e.video)
        updateInfo()
        updateBoost()
        // 3.0: a call taking the sound away (or giving it back) shows on the play button.
        updatePlaying()
    }

    /** 3.0: the volume boost's sign, in a corner while it is on (a tap turns it off). */
    private fun updateBoost() {
        val b = LocalPlayback.boost
        if (b <= 0f) {
            boostBadge.visibility = View.GONE
            return
        }
        boostBadge.text = "Boost ${100 + (b * 100).roundToInt()}%"
        boostBadge.visibility = View.VISIBLE
    }

    override fun onReleased() {
        if (isFinishing) return
        closing = true
        finish()
    }

    // ------------------------------------------------------------------ the screen

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame = VideoFrame(this)
        surface = SurfaceView(this)
        frame.addView(surface)
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        cover = ImageView(this).apply {
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        songTitle = Ui.text(this, Ui.Type.HEADLINE, "", lines = 2)
        songArtist = Ui.text(this, Ui.Type.BODY_SECONDARY, "", lines = 1)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(songTitle)
            addView(songArtist, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        music = LinearLayout(this).apply {
            visibility = View.GONE
            addView(cover)
            addView(texts)
        }
        root.addView(music, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        subtitleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(Ui.dpf(this@PlayerActivity, 2f), 0f, 0f, Color.BLACK)
            background = Ui.shape(this@PlayerActivity, 0x8C000000.toInt(), 6)
            setPadding(dp(8), dp(2), dp(8), dp(3))
            visibility = View.GONE
        }
        root.addView(subtitleText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(24), 0, dp(24), dp(28))
        })
        subtitlePicture = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        root.addView(subtitlePicture, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))

        gestures = PlayerGestures(this, this)
        root.addView(gestures, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        spinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(Ui.accent(this@PlayerActivity))
            visibility = View.GONE
        }
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        buildHud()
        buildTopBar()
        buildControls()

        // 3.0: shown while the volume boost is on, also with the controls hidden.
        boostBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Ui.MEDIUM
            background = Ui.ripple(this@PlayerActivity, Ui.shape(this@PlayerActivity, 0x99000000.toInt(), 12), 12)
            setPadding(dp(10), dp(4), dp(10), dp(5))
            contentDescription = "Volume boost on. Tap to turn it off"
            visibility = View.GONE
            setOnClickListener {
                LocalPlayback.setBoost(0f)
                updateBoost()
                say("Volume boost off")
            }
        }
        root.addView(boostBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(64), dp(14), 0)
        })

        unlockButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_lock_open)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = Ui.circle(0x99000000.toInt())
            contentDescription = "Unlock the screen"
            visibility = View.GONE
            setOnClickListener { setLocked(false) }
        }
        root.addView(unlockButton, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(20) })
        setContentView(root)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun button(icon: Int, label: String, click: () -> Unit): ImageButton = ImageButton(this, null, 0, R.style.Swift_ToolbarButton).apply {
        setImageResource(icon)
        contentDescription = label
        setOnClickListener { click() }
        icons += this
    }

    private fun buildHud() {
        hudIcon = ImageView(this)
        hudText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Ui.MEDIUM
            gravity = Gravity.CENTER
        }
        hudSub = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
        }
        hudLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Ui.accent(this@PlayerActivity))
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        }
        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.shape(this@PlayerActivity, 0xB3000000.toInt(), Ui.R_MEDIUM)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            visibility = View.GONE
            addView(hudIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(hudText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(hudSub)
            addView(hudLevel, LinearLayout.LayoutParams(dp(120), dp(4)).apply { topMargin = dp(10) })
        }
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /**
     * 3.0.2, after VLC's player: Back, the title (and "2 of 7"), then the sound and subtitles, turning,
     * the playlist and More. Under the title, chips for only what isn't as usual (see [updateChips]).
     */
    private fun buildTopBar() {
        titleView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Ui.MEDIUM
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        countView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        rotateButton = button(R.drawable.ic_rotate, "Turn the screen") { rotationSheet() }
        subtitlesButton = button(R.drawable.ic_subtitles, "Sound and subtitles") { tracksSheet() }
        playlistButton = button(R.drawable.ic_view_list, "Playlist") { playlistSheet() }
        val size = dp(48)
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            // Room below for the shade to fade out, so the title reads over any picture.
            setPadding(dp(4), dp(4), dp(4), dp(36))
            addView(button(R.drawable.ic_back, "Back") { close() }, LinearLayout.LayoutParams(size, size))
            addView(LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(12), dp(4), 0)
                addView(titleView)
                addView(countView)
                addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(subtitlesButton, LinearLayout.LayoutParams(size, size))
            addView(rotateButton, LinearLayout.LayoutParams(size, size))
            addView(playlistButton, LinearLayout.LayoutParams(size, size))
            addView(button(R.drawable.ic_more, "More") { moreSheet() }, LinearLayout.LayoutParams(size, size))
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private fun buildControls() {
        elapsed = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minWidth = dp(48)
        }
        length = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            minWidth = dp(48)
            minHeight = dp(40)
            contentDescription = "Length (tap for the time left)"
            setOnClickListener {
                showRemaining = !showRemaining
                updateProgress()
            }
        }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(Ui.accent(this@PlayerActivity))
            thumbTintList = ColorStateList.valueOf(Ui.accent(this@PlayerActivity))
            secondaryProgressTintList = ColorStateList.valueOf(0x66FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    elapsed.text = time(value.toLong())
                    previewAt(value.toLong())
                }

                override fun onStartTrackingTouch(bar: SeekBar) = scrubStart()

                override fun onStopTrackingTouch(bar: SeekBar) {
                    scrubEnd(bar.progress.toLong())
                    scheduleHide()
                }
            })
        }
        playButton = ImageButton(this).apply {
            background = Ui.circle(Ui.accent(this@PlayerActivity))
            imageTintList = ColorStateList.valueOf(Ui.onAccent(this@PlayerActivity))
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_pause)
            contentDescription = "Pause"
            Ui.pressable(this, 0.92f)
            setOnClickListener {
                LocalPlayback.toggle()
                scheduleHide()
            }
        }
        lockButton = button(R.drawable.ic_lock, "Lock the screen") { setLocked(true) }
        fitButton = button(R.drawable.ic_aspect_ratio, "Picture size") { nextFit() }
        shuffleButton = button(R.drawable.ic_shuffle, "Shuffle") { toggleShuffle() }
        repeatButton = button(R.drawable.ic_repeat, "Repeat") { nextRepeat() }
        previousButton = button(R.drawable.ic_media_previous, "Previous") { LocalPlayback.previous() }
        nextButton = button(R.drawable.ic_media_next, "Next") { LocalPlayback.next() }
        // 3.0.2: back and on by the double tap's step, as buttons too (VLC's -10 and +10).
        back10 = button(R.drawable.ic_fast_rewind, "Back ${PlayerSettings.doubleTapSeconds} seconds") { jump(-1) }
        forward10 = button(R.drawable.ic_fast_forward, "On ${PlayerSettings.doubleTapSeconds} seconds") { jump(1) }
        speedButton = button(R.drawable.ic_speed, "Playback speed") { speedSheet() }
        val small = dp(44)
        // Lock and picture size at the start, the playing in the middle, speed at the end; which of
        // them show depends on video or music, a playlist and the width (see arrangeButtons).
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(lockButton, LinearLayout.LayoutParams(small, small))
            addView(shuffleButton, LinearLayout.LayoutParams(small, small))
            addView(fitButton, LinearLayout.LayoutParams(small, small))
            addView(View(this@PlayerActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(previousButton, LinearLayout.LayoutParams(small, small))
            addView(back10, LinearLayout.LayoutParams(small, small))
            addView(playButton, LinearLayout.LayoutParams(dp(60), dp(60)).apply { setMargins(dp(14), 0, dp(14), 0) })
            addView(forward10, LinearLayout.LayoutParams(small, small))
            addView(nextButton, LinearLayout.LayoutParams(small, small))
            addView(View(this@PlayerActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(speedButton, LinearLayout.LayoutParams(small, small))
            addView(repeatButton, LinearLayout.LayoutParams(small, small))
        }
        // The times above a bar as wide as the screen (3.0.2: the bar no longer shares its line).
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            addView(elapsed)
            addView(View(this@PlayerActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(length)
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(44), dp(8), dp(10))
            // Pulled down onto the bar, so the times read as the bar's own (the length keeps its tap room).
            addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = -dp(10) })
            addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /** 3.0.2: the buttons that make sense now: video or music, a playlist or one item, what fits across. */
    private fun arrangeButtons() {
        val video = videoMode
        val queue = LocalPlayback.entries.size > 1
        val dm = resources.displayMetrics
        val narrow = dm.widthPixels / dm.density < 420
        for (v in listOf(lockButton, fitButton, rotateButton, subtitlesButton, speedButton)) v.visibility = if (video) View.VISIBLE else View.GONE
        for (v in listOf(shuffleButton, repeatButton)) v.visibility = if (video) View.GONE else View.VISIBLE
        // Previous and next only for a playlist (music keeps them: they also restart the song).
        val steps = if (queue || !video) View.VISIBLE else View.GONE
        previousButton.visibility = steps
        nextButton.visibility = steps
        // Across a phone held upright, a playlist's buttons take the jumps' place.
        val jumps = if (video && !(queue && narrow)) View.VISIBLE else View.GONE
        back10.visibility = jumps
        forward10.visibility = jumps
    }

    /** The jump buttons: back or on by the double tap's step. */
    private fun jump(direction: Int) {
        if (player == null) return
        val step = PlayerSettings.doubleTapSeconds * 1000L
        LocalPlayback.seekBy(direction * step)
        showHud(if (direction < 0) R.drawable.ic_fast_rewind else R.drawable.ic_fast_forward, (if (direction < 0) "−" else "+") + "${step / 1000} s", null, null)
        hideHudSoon()
        scheduleHide()
    }

    /**
     * 3.0.2, VLC's chips: under the title, only what isn't as usual (a speed, the sleep timer, the
     * subtitles' timing). A tap opens its choices, holding puts it back. Looked at with the progress.
     */
    private fun updateChips() {
        val p = player ?: return
        val speed = p.playbackParameters.speed
        val sleep = LocalPlayback.sleepLabel()
        val delay = if (subFile != null && subDelayMs != 0L) "Subtitles ${delayLabel()}" else null
        val key = "$speed|$sleep|$delay|$videoMode"
        if (key == chipsShown) return
        chipsShown = key
        chips.removeAllViews()
        if (abs(speed - 1f) > 0.01f) {
            chips.addView(chip(R.drawable.ic_speed, PlayerSettings.speedLabel(speed), "Speed ${PlayerSettings.speedLabel(speed)}. Hold for normal speed", { speedSheet() }) {
                player?.setPlaybackSpeed(1f)
                say("Normal speed")
                updateChips()
            })
        }
        if (sleep != null) {
            chips.addView(chip(R.drawable.ic_timer, "Sleep: $sleep", "Sleep timer: $sleep. Hold to turn it off", { sleepSheet() }) {
                LocalPlayback.cancelSleep()
                say("Sleep timer off")
                updateChips()
            })
        }
        if (delay != null) {
            chips.addView(chip(R.drawable.ic_subtitles, delay, "$delay. Hold to put the subtitles back in time", { tracksSheet() }) {
                subDelayMs = 0
                saveSubtitleDelay()
                refreshSubtitles()
                updateChips()
            })
        }
        chips.visibility = if (chips.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun chip(icon: Int, text: String, description: String, onTap: () -> Unit, onReset: () -> Unit): TextView = TextView(this).apply {
        val fg = if (videoMode) Color.WHITE else Palette.color(this@PlayerActivity, R.color.text_primary)
        this.text = text
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Ui.MEDIUM
        isSingleLine = true
        val mark = Ui.icon(this@PlayerActivity, icon, fg)?.mutate()?.apply { setBounds(0, 0, dp(14), dp(14)) }
        setCompoundDrawablesRelative(mark, null, null, null)
        compoundDrawablePadding = dp(5)
        val fill = if (videoMode) 0x33FFFFFF else Palette.color(this@PlayerActivity, R.color.surface_alt)
        background = Ui.ripple(this@PlayerActivity, Ui.shape(this@PlayerActivity, fill, 14), 14)
        setPadding(dp(10), dp(5), dp(11), dp(6))
        contentDescription = description
        setOnClickListener { onTap() }
        setOnLongClickListener {
            onReset()
            true
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
    }

    /** Video (black, controls over the picture) or music (Swift's colours, the cover, controls that stay). */
    private fun applyMode(video: Boolean) {
        videoMode = video
        val fg = if (video) Color.WHITE else Palette.color(this, R.color.text_primary)
        val muted = if (video) 0xCCFFFFFF.toInt() else Palette.color(this, R.color.text_secondary)
        val bg = if (video) Color.BLACK else Palette.color(this, R.color.page_bg)
        root.setBackgroundColor(bg)
        setBarColours(bg)
        frame.visibility = if (video) View.VISIBLE else View.GONE
        music.visibility = if (video) View.GONE else View.VISIBLE
        // 3.0.2: deeper shades (as VLC's), so the controls read over a bright picture; the rest of
        // the picture is left alone (no dimming, no blur).
        topBar.background = if (video) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0)) else null
        controls.background = if (video) GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE0000000.toInt(), 0x99000000.toInt(), 0)) else null
        controls.setPadding(dp(8), if (video) dp(44) else dp(8), dp(8), dp(10))
        // Over a video a light ring; on the music screen the accent's round button.
        playButton.background = if (video) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x40000000)
                setStroke(dp(2), Color.WHITE)
            }
        } else {
            Ui.circle(Ui.accent(this))
        }
        playButton.imageTintList = ColorStateList.valueOf(if (video) Color.WHITE else Ui.onAccent(this))
        val tint = ColorStateList.valueOf(fg)
        for (b in icons) b.imageTintList = tint
        titleView.setTextColor(fg)
        countView.setTextColor(muted)
        elapsed.setTextColor(fg)
        length.setTextColor(fg)
        seek.progressBackgroundTintList = ColorStateList.valueOf(if (video) 0x55FFFFFF else Palette.color(this, R.color.outline))
        arrangeButtons()
        chipsShown = ""
        if (!video) {
            if (locked) setLocked(false)
            showControls(true)
            showCues(emptyList())
            layoutMusic()
        }
        updateModes()
        updatePlaying()
        applyRotation()
        applyBars()
    }

    @Suppress("DEPRECATION")
    private fun setBarColours(colour: Int) {
        window.statusBarColor = colour
        window.navigationBarColor = if (videoMode) Color.BLACK else colour
    }

    /** The cover above the titles upright; turned, side by side so both fit between the bars. */
    private fun layoutMusic() {
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        music.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        music.gravity = Gravity.CENTER
        val top = dp(64)
        val bottom = dp(150)
        val side = if (landscape) {
            (dm.heightPixels - top - bottom).coerceAtLeast(dp(96))
        } else {
            minOf((dm.widthPixels * 0.72f).toInt(), (dm.heightPixels * 0.42f).toInt())
        }
        cover.layoutParams = LinearLayout.LayoutParams(side, side)
        val texts = music.getChildAt(1)
        texts.layoutParams = if (landscape) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(24) }
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) }
        }
        val align = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
        songTitle.gravity = align
        songArtist.gravity = align
        music.setPadding(dp(28), top, dp(28), bottom)
    }

    private fun applyBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        // A video always has the whole screen; music as the rest of Swift.
        if (videoMode || SwiftApp.prefs.fullScreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** How the screen turns (Settings or the turn button): a video as it is, with the phone, or fixed. */
    private fun applyRotation() {
        val want = if (!videoMode) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            when (PlayerSettings.rotation) {
                PlayerSettings.ROTATE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                PlayerSettings.ROTATE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                PlayerSettings.ROTATE_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                else -> {
                    val s = player?.videoSize
                    if (s == null || s.width <= 0 || s.height <= 0) {
                        requestedOrientation
                    } else if (s.width * s.pixelWidthHeightRatio >= s.height) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                }
            }
        }
        if (requestedOrientation != want) requestedOrientation = want
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!videoMode) layoutMusic()
        arrangeButtons()
        applySubtitleSize()
        handler.post { moveSubtitles() }
    }

    // ------------------------------------------------------------------ showing the state

    private fun updateInfo() {
        val e = LocalPlayback.current ?: return
        val n = LocalPlayback.entries.size
        val position = if (n > 1) "${LocalPlayback.index + 1} of $n" else null
        if (videoMode) {
            titleView.text = e.title
        } else {
            titleView.text = when {
                e.file != null -> e.file.parentFile?.name ?: "Music"
                e.stream && !e.incognito -> UrlUtils.host(e.uri.toString())?.removePrefix("www.") ?: "Music"
                else -> "Music"
            }
            songTitle.text = LocalPlayback.displayTitle()
            songArtist.text = LocalPlayback.tagArtist.orEmpty()
            songArtist.visibility = if (songArtist.text.isNullOrBlank()) View.GONE else View.VISIBLE
            showCover()
        }
        countView.text = position.orEmpty()
        countView.visibility = if (countView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        playlistButton.visibility = if (n > 1) View.VISIBLE else View.GONE
        arrangeButtons()
        updateChips()
    }

    private fun showCover() {
        val art = LocalPlayback.art
        if (art != null) {
            cover.background = Ui.shape(this, Palette.color(this, R.color.surface_alt), Ui.R_LARGE)
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setPadding(0, 0, 0, 0)
            cover.imageTintList = null
            cover.setImageBitmap(art)
        } else {
            // No picture of its own: a big note in the accent's soft colour.
            cover.background = Ui.shape(this, Ui.accentContainer(this), Ui.R_LARGE)
            cover.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (cover.layoutParams?.width ?: dp(240)) / 4
            cover.setPadding(pad, pad, pad, pad)
            cover.setImageResource(R.drawable.ic_music_note)
            cover.imageTintList = ColorStateList.valueOf(Ui.onAccentContainer(this))
        }
    }

    private fun updatePlaying() {
        val playing = LocalPlayback.playing
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = if (playing) "Pause" else "Play"
        // The screen stays on while a video plays (music lets it turn off).
        if (videoMode && playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (playing) {
            scheduleHide()
        } else {
            handler.removeCallbacks(autoHide)
            if (videoMode && !locked && player?.playbackState == Player.STATE_ENDED) showControls(true)
        }
    }

    private fun updateModes() {
        val p = player ?: return
        val fg = if (videoMode) Color.WHITE else Palette.color(this, R.color.text_primary)
        val accent = Ui.accent(this)
        val shuffle = p.shuffleModeEnabled
        shuffleButton.imageTintList = ColorStateList.valueOf(if (shuffle) accent else fg)
        shuffleButton.alpha = if (shuffle) 1f else 0.6f
        shuffleButton.contentDescription = if (shuffle) "Shuffle: on" else "Shuffle: off"
        val repeat = p.repeatMode
        repeatButton.setImageResource(if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatButton.imageTintList = ColorStateList.valueOf(if (repeat != Player.REPEAT_MODE_OFF) accent else fg)
        repeatButton.alpha = if (repeat != Player.REPEAT_MODE_OFF) 1f else 0.6f
        repeatButton.contentDescription = "Repeat: " + repeatLabel(repeat)
    }

    private fun repeatLabel(mode: Int) = when (mode) {
        Player.REPEAT_MODE_ONE -> if (videoMode) "this video" else "this song"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    private fun updateProgress() {
        val p = player ?: return
        val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = p.currentPosition.coerceAtLeast(0)
        if (!scrubbing) {
            seek.max = d.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            seek.progress = pos.toInt()
            seek.secondaryProgress = p.bufferedPosition.toInt()
            elapsed.text = time(pos)
        }
        seek.isEnabled = d > 0 && p.isCurrentMediaItemSeekable
        length.text = when {
            p.isCurrentMediaItemLive -> "Live"
            d <= 0 -> "–:––"
            showRemaining -> "−" + time(d - pos)
            else -> time(d)
        }
    }

    /** Twice a second while the screen is seen. */
    private val ticker = object : Runnable {
        override fun run() {
            if (controlsShown || !videoMode) {
                updateProgress()
                updateChips()
            }
            handler.postDelayed(this, 500)
        }
    }

    private val showSpinner = Runnable { spinner.visibility = View.VISIBLE }

    private fun time(ms: Long) = WatchPositions.format(ms.coerceAtLeast(0) / 1000.0)

    /** A message above the controls when they are shown. */
    private fun say(text: String, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null) =
        Messages.show(this, text, action = action, long = long, bottomOffset = if (controlsShown) controls.height else 0, onAction = onAction)

    // ------------------------------------------------------------------ showing and hiding the controls

    private val autoHide = Runnable {
        if (videoMode && controlsShown && LocalPlayback.playing && !scrubbing && problem == null) showControls(false)
    }

    /** The controls go 4 seconds after the last touch while a video plays (3.0.2: 3.5 before); paused, they stay. */
    private fun scheduleHide() {
        handler.removeCallbacks(autoHide)
        if (videoMode && controlsShown && LocalPlayback.playing) handler.postDelayed(autoHide, 4000)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (controlsShown) scheduleHide()
    }

    private fun showControls(show: Boolean) {
        if (show && locked) return
        if (!show && !videoMode) return
        controlsShown = show
        for (v in arrayOf<View>(topBar, controls)) {
            v.animate().cancel()
            if (show) {
                if (v.visibility != View.VISIBLE) {
                    v.alpha = 0f
                    v.visibility = View.VISIBLE
                }
                v.animate().alpha(1f).setDuration(Motion.SHORT).withLayer().start()
            } else {
                v.animate().alpha(0f).setDuration(Motion.SHORT).withLayer().withEndAction { if (!controlsShown) v.visibility = View.INVISIBLE }.start()
            }
        }
        moveSubtitles()
        if (show) {
            updateProgress()
            scheduleHide()
        } else {
            handler.removeCallbacks(autoHide)
        }
    }

    private fun setLocked(on: Boolean) {
        locked = on
        if (on) {
            showControls(false)
            showUnlock()
            say("The screen is locked. Tap the lock to unlock it.")
        } else {
            handler.removeCallbacks(hideUnlock)
            unlockButton.visibility = View.GONE
            showControls(true)
        }
    }

    private val hideUnlock = Runnable {
        unlockButton.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction {
            unlockButton.visibility = View.GONE
            unlockButton.alpha = 1f
        }.start()
    }

    private fun showUnlock() {
        unlockButton.animate().cancel()
        unlockButton.alpha = 1f
        unlockButton.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 3000)
    }

    // ------------------------------------------------------------------ the HUD (gestures' feedback)

    private fun showHud(icon: Int, text: String, sub: String?, level: Float?, side: Int = 0) {
        handler.removeCallbacks(hideHud)
        placeHud(side)
        hud.animate().cancel()
        hud.alpha = 1f
        hud.visibility = View.VISIBLE
        hudIcon.setImageDrawable(Ui.icon(this, icon, Color.WHITE))
        hudText.text = text
        hudSub.text = sub
        hudSub.visibility = if (sub == null) View.GONE else View.VISIBLE
        if (level == null) {
            hudLevel.visibility = View.GONE
        } else {
            hudLevel.visibility = View.VISIBLE
            hudLevel.progress = (level * 1000).roundToInt()
        }
    }

    /** 3.0.2: a double tap's HUD on its side of the screen, where the eyes are; the rest in the middle. */
    private fun placeHud(side: Int) {
        val lp = hud.layoutParams as? FrameLayout.LayoutParams ?: return
        val gravity = when {
            side < 0 -> Gravity.CENTER_VERTICAL or Gravity.START
            side > 0 -> Gravity.CENTER_VERTICAL or Gravity.END
            else -> Gravity.CENTER
        }
        if (lp.gravity == gravity) return
        lp.gravity = gravity
        lp.marginStart = if (side != 0) dp(48) else 0
        lp.marginEnd = if (side != 0) dp(48) else 0
        hud.layoutParams = lp
    }

    private val hideHud = Runnable {
        hud.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction {
            hud.visibility = View.GONE
            hud.alpha = 1f
        }.start()
    }

    private fun hideHudSoon(ms: Long = 700) {
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, ms)
    }

    // ------------------------------------------------------------------ gestures

    override fun locked() = locked

    override fun gesturesOn() = gesturesEnabled

    override fun onTap() {
        if (locked) {
            showUnlock()
            return
        }
        if (videoMode && problem == null) showControls(!controlsShown)
    }

    override fun onDoubleTap(side: Int) {
        if (player == null) return
        if (side == 0) {
            LocalPlayback.toggle()
            showHud(if (LocalPlayback.playing) R.drawable.ic_play else R.drawable.ic_pause, if (LocalPlayback.playing) "Playing" else "Paused", null, null)
            hideHudSoon()
            return
        }
        val step = PlayerSettings.doubleTapSeconds * 1000L
        val now = SystemClock.uptimeMillis()
        // Tapping on at the same side adds up (−10 s, −20 s…), as the HUD shows.
        tapTotal = if (side == tapSide && now - tapAt < 1200) tapTotal + step else step
        tapSide = side
        tapAt = now
        LocalPlayback.seekBy(side * step)
        showHud(if (side < 0) R.drawable.ic_fast_rewind else R.drawable.ic_fast_forward, (if (side < 0) "−" else "+") + "${tapTotal / 1000} s", null, null, side)
        hideHudSoon()
    }

    override fun onHold(start: Boolean) {
        val p = player ?: return
        if (start) {
            if (!LocalPlayback.playing) return
            holdSpeed = p.playbackParameters.speed
            val fast = if (holdSpeed >= 2f) 4f else 2f
            p.setPlaybackSpeed(fast)
            showHud(R.drawable.ic_fast_forward, "${PlayerSettings.speedLabel(fast)} speed", "Let go to go back to ${PlayerSettings.speedLabel(holdSpeed)}", null)
        } else if (holdSpeed > 0f) {
            p.setPlaybackSpeed(holdSpeed)
            holdSpeed = 0f
            hideHudSoon(200)
        }
    }

    override fun onSwipeStart(kind: Int) {
        when (kind) {
            PlayerGestures.BRIGHTNESS -> swipeFrom = brightness()
            PlayerGestures.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                swipeFrom = audio.getStreamVolume(AudioManager.STREAM_MUSIC) + LocalPlayback.boost * max
                lastVolume = -1
                // Looked at once a swipe (it asks Android about the phone's outputs).
                boostLimit = LocalPlayback.maxBoost()
            }
            PlayerGestures.SEEK -> {
                val p = player ?: return
                seekFrom = p.currentPosition
                seekTarget = seekFrom
                scrubStart()
            }
        }
    }

    override fun onSwipe(kind: Int, amount: Float) {
        when (kind) {
            PlayerGestures.BRIGHTNESS -> {
                val b = (swipeFrom + amount * 1.2f).coerceIn(0.01f, 1f)
                setBrightness(b)
                showHud(R.drawable.ic_brightness, "Brightness", "${(b * 100).roundToInt()}%", b)
            }
            PlayerGestures.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                // 2.9.2: past the phone's loudest the swipe goes on into the boost, up to 200%.
                val at = (swipeFrom + amount * max * 1.2f).coerceIn(0f, max * 2f)
                val v = at.roundToInt().coerceIn(0, max)
                if (v != lastVolume) {
                    lastVolume = v
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    } catch (e: SecurityException) {
                        L.w("Player: volume not allowed", e)
                    }
                }
                // 3.0: with headphones in, no further than +4 dB (140%), and the HUD says why.
                val limit = boostLimit
                val wanted = ((at - max) / max).coerceIn(0f, 1f)
                val boost = wanted.coerceAtMost(limit)
                if (abs(boost - LocalPlayback.boost) >= 0.02f || (boost == 0f) != (LocalPlayback.boost == 0f)) LocalPlayback.setBoost(boost)
                if (boost > 0f) {
                    val held = limit < 1f && wanted > limit
                    showHud(R.drawable.ic_volume, "Volume boost", "${100 + (boost * 100).roundToInt()}%" + if (held) " · the most with headphones" else "", boost / limit)
                } else {
                    showHud(R.drawable.ic_volume, "Volume", if (v == 0) "Off" else "${v * 100 / max}%", v.toFloat() / max)
                }
                updateBoost()
            }
            PlayerGestures.SEEK -> {
                val p = player ?: return
                val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
                if (!p.isCurrentMediaItemSeekable) return
                // The width of the screen is three minutes (the whole of anything shorter).
                val range = minOf(d, 180_000L)
                seekTarget = (seekFrom + (amount * range).toLong()).coerceIn(0, d)
                val delta = seekTarget - seekFrom
                showHud(
                    if (delta < 0) R.drawable.ic_fast_rewind else R.drawable.ic_fast_forward,
                    (if (delta < 0) "−" else "+") + time(abs(delta)),
                    "${time(seekTarget)} / ${time(d)}",
                    seekTarget.toFloat() / d,
                )
                if (controlsShown) {
                    seek.progress = seekTarget.toInt()
                    elapsed.text = time(seekTarget)
                }
                previewAt(seekTarget)
            }
        }
    }

    override fun onSwipeEnd(kind: Int) {
        if (kind == PlayerGestures.SEEK) {
            val moved = seekTarget != seekFrom
            scrubEnd(if (moved) seekTarget else null)
            hideHudSoon(if (moved) 300 else 0)
        } else {
            hideHudSoon()
        }
    }

    /** The window's own brightness while swiping started, or the phone's. */
    private fun brightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    /** Only this screen's brightness: the phone's own comes back when it closes. */
    private fun setBrightness(b: Float) {
        val lp = window.attributes
        lp.screenBrightness = b
        window.attributes = lp
    }

    // ------------------------------------------------------------------ seeking with a finger

    private fun scrubStart() {
        val p = player ?: return
        scrubbing = true
        handler.removeCallbacks(autoHide)
        // A file shows the picture where the finger is as it moves (the player's scrubbing mode,
        // made for this); a website video only goes there at the end, to spare the connection.
        previewSeeks = LocalPlayback.current?.stream != true && videoMode
        if (previewSeeks) p.setScrubbingModeEnabled(true)
    }

    private fun previewAt(ms: Long) {
        if (!scrubbing || !previewSeeks) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewAt < 100) return
        lastPreviewAt = now
        player?.seekTo(ms)
    }

    private fun scrubEnd(ms: Long?) {
        val p = player ?: return
        scrubbing = false
        if (previewSeeks) p.setScrubbingModeEnabled(false)
        previewSeeks = false
        if (ms != null) LocalPlayback.seekTo(ms)
        updateProgress()
    }

    // ------------------------------------------------------------------ buttons

    private fun nextFit() {
        val all = VideoFrame.Fit.entries
        frame.fit = all[(frame.fit.ordinal + 1) % all.size]
        showHud(R.drawable.ic_aspect_ratio, frame.fit.label, null, null)
        hideHudSoon(900)
    }

    private fun toggleShuffle() {
        val p = player ?: return
        p.shuffleModeEnabled = !p.shuffleModeEnabled
        say(if (p.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
    }

    private fun nextRepeat() {
        val p = player ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        say("Repeat: " + repeatLabel(p.repeatMode))
    }

    private fun toggleBackground() {
        val on = !LocalPlayback.background
        LocalPlayback.background = on
        say(if (on) "The sound goes on when you leave Swift or turn the screen off" else "The video pauses when you leave it")
    }

    /** Leaves the player: a video stops (where it was is kept); music goes on unless Settings say otherwise. */
    private fun close() {
        val e = LocalPlayback.current
        val goesOn = e != null && !e.video && PlayerSettings.backgroundAudio
        // 3.0: a song another app opened (its content:// link) may only be read while this screen
        // lives: it goes behind instead of closing, so seeking, repeating and the next song still
        // work. Closing the music (the notification's Close) closes it then.
        if (goesOn && e?.file == null && e?.stream == false) {
            moveTaskToBack(true)
            return
        }
        closing = true
        if (!goesOn) LocalPlayback.release()
        finish()
    }

    // ------------------------------------------------------------------ menus

    private fun choiceRow(text: String, chosen: Boolean, icon: Int, onClick: () -> Unit): TextView =
        Sheets.row(this, if (chosen) R.drawable.ic_check else icon, text) { onClick() }.apply {
            if (chosen) {
                setTextColor(Ui.accent(this@PlayerActivity))
                setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(this@PlayerActivity, R.drawable.ic_check, Ui.accent(this@PlayerActivity)), null, null, null)
            }
        }

    private fun moreSheet() {
        val e = LocalPlayback.current ?: return
        val p = player ?: return
        val f = e.file
        val n = LocalPlayback.entries.size
        val sub = listOfNotNull(
            f?.let { UrlUtils.formatBytes(it.length()) },
            if (n > 1) "${LocalPlayback.index + 1} of $n" else null,
            if (e.stream && !e.incognito) UrlUtils.host(e.uri.toString())?.removePrefix("www.") else null,
        ).joinToString(" · ")
        val actions = ArrayList<Sheets.Action>()
        actions += Sheets.Action(R.drawable.ic_speed, "Speed ${PlayerSettings.speedLabel(p.playbackParameters.speed)}", primary = true) { speedSheet() }
        actions += Sheets.Action(R.drawable.ic_timer, LocalPlayback.sleepLabel()?.let { "Sleep: $it" } ?: "Sleep timer", primary = true) { sleepSheet() }
        if (videoMode) {
            actions += Sheets.Action(R.drawable.ic_headphones, if (LocalPlayback.background) "Stop playing in background" else "Play in background", primary = true) { toggleBackground() }
            actions += Sheets.Action(if (p.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat, "Repeat: ${repeatLabel(p.repeatMode)}") { nextRepeat() }
        }
        if (videoMode && !e.private) actions += Sheets.Action(R.drawable.ic_image, "Screenshot") { screenshot() }
        if (n > 1) actions += Sheets.Action(R.drawable.ic_view_list, "Playlist") { playlistSheet() }
        actions += Sheets.Action(R.drawable.ic_reopen, "Open with another app") { openWith() }
        if (f != null) {
            actions += Sheets.Action(R.drawable.ic_share, "Share") { share(f) }
            actions += Sheets.Action(R.drawable.ic_folder, "Show in Files") {
                FilesActivity.open(this, f.parentFile)
                close()
            }
        }
        actions += Sheets.Action(R.drawable.ic_info, "Details") { details() }
        Sheets.actions(this, e.title, sub, actions, icon = if (videoMode || e.private) null else LocalPlayback.smallArt, iconRes = if (videoMode) R.drawable.ic_movie else R.drawable.ic_music_note)
    }

    /** 3.0: Android 7 asks for storage access the first time a screenshot is saved in Pictures. */
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) screenshot() else say("Swift needs access to your files to save screenshots")
    }

    /** 2.9.2: the picture on screen, saved in Pictures › Swift (the video's own size, up to 1920 wide). */
    private fun screenshot() {
        val p = player ?: return
        if (android.os.Build.VERSION.SDK_INT <= 28 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                askStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } catch (_: Exception) {
                say("Swift needs access to your files to save screenshots")
            }
            return
        }
        val size = p.videoSize
        if (size.width <= 0 || size.height <= 0) {
            say("There's no picture to save yet")
            return
        }
        // As it looks: some videos' pixels aren't square.
        val wide = size.width * size.pixelWidthHeightRatio
        val scale = minOf(1f, 1920f / wide)
        val w = (wide * scale).roundToInt().coerceAtLeast(1)
        val h = (size.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = try {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            say("Not enough memory for a screenshot")
            return
        }
        val title = LocalPlayback.current?.title ?: "Video"
        val at = time(p.currentPosition).replace(':', '.')
        try {
            android.view.PixelCopy.request(surface, bitmap, { result ->
                if (result != android.view.PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    say("Couldn't take a screenshot of this video")
                    return@request
                }
                LocalPlayback.io.execute {
                    val saved = try {
                        @Suppress("DEPRECATION")
                        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Swift").apply { mkdirs() }
                        var f = File(dir, UrlUtils.sanitizeFileName("$title $at") + ".jpg")
                        var k = 2
                        while (f.exists()) f = File(dir, UrlUtils.sanitizeFileName("$title $at ($k)") + ".jpg").also { k++ }
                        f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                        android.media.MediaScannerConnection.scanFile(applicationContext, arrayOf(f.path), arrayOf("image/jpeg"), null)
                        f
                    } catch (e: Exception) {
                        L.w("Player: saving a screenshot", e)
                        null
                    } finally {
                        bitmap.recycle()
                    }
                    handler.post {
                        if (isDestroyed) return@post
                        if (saved == null) say("Couldn't save the screenshot") else say("Saved in Pictures › Swift", "Show") { MediaViewerActivity.open(this, saved) }
                    }
                }
            }, handler)
        } catch (e: Exception) {
            bitmap.recycle()
            L.w("Player: screenshot", e)
            say("Couldn't take a screenshot of this video")
        }
    }

    private fun speedSheet() {
        val p = player ?: return
        val speeds = PlayerSettings.SPEEDS
        val now = p.playbackParameters.speed
        val selected = speeds.indices.minByOrNull { abs(speeds[it] - now) } ?: 3
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, "Playback speed", "Voices keep their pitch"))
        col.addView(Sheets.chips(this, speeds.map { PlayerSettings.speedLabel(it) }, selected) { i -> player?.setPlaybackSpeed(speeds[i]) })
        if (gesturesEnabled) col.addView(Sheets.note(this, "Holding a finger on the video plays it at 2× until you let go."))
        Sheets.sheet(this, col).showImmersive()
    }

    private fun sleepSheet() {
        val kind = if (videoMode) "video" else "song"
        val left = when {
            LocalPlayback.sleepAtEnd -> "Playing stops at the end of this $kind"
            LocalPlayback.sleepEndsAt > 0 -> "Playing stops in ${LocalPlayback.sleepLabel()?.removeSuffix(" left")}"
            else -> "Playing stops after a while; the sound fades out"
        }
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, "Sleep timer", left))
        lateinit var d: BottomSheetDialog
        for (m in LocalPlayback.SLEEP_CHOICES) {
            col.addView(Sheets.row(this, R.drawable.ic_timer, "In $m minutes") {
                d.dismiss()
                LocalPlayback.setSleep(m)
                say("Playing stops in $m minutes")
            })
        }
        col.addView(Sheets.row(this, R.drawable.ic_timer, "At the end of this $kind") {
            d.dismiss()
            LocalPlayback.setSleep(LocalPlayback.SLEEP_END)
            say("Playing stops at the end of this $kind")
        })
        if (LocalPlayback.sleepLabel() != null) {
            col.addView(Sheets.row(this, R.drawable.ic_close, "Turn the timer off") {
                d.dismiss()
                LocalPlayback.cancelSleep()
                say("Sleep timer off")
            })
        }
        d = Sheets.sheet(this, col)
        d.showImmersive()
    }

    private fun rotationSheet() {
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, "Turn the screen", "For every video, until you change it"))
        lateinit var d: BottomSheetDialog
        for (key in PlayerSettings.ROTATIONS) {
            col.addView(choiceRow(PlayerSettings.rotationLabel(key), key == PlayerSettings.rotation, R.drawable.ic_rotate) {
                d.dismiss()
                PlayerSettings.rotation = key
                applyRotation()
            })
        }
        d = Sheets.sheet(this, col)
        d.showImmersive()
    }

    private fun playlistSheet() {
        val entries = LocalPlayback.entries
        if (entries.size < 2) return
        val now = LocalPlayback.index
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, if (videoMode) "Videos in this folder" else "Songs in this folder", "${entries.size}, by name"))
        lateinit var d: BottomSheetDialog
        val accent = Ui.accent(this)
        val text = Palette.color(this, R.color.text_primary)
        val adapter = RowAdapter<Int>(
            bind = { h, i ->
                val e = entries[i]
                h.set(if (i == now) R.drawable.ic_play else if (e.video) R.drawable.ic_movie else R.drawable.ic_music_note, e.title, if (i == now) "Playing now" else null)
                h.title.setTextColor(if (i == now) accent else text)
            },
            onClick = { i ->
                d.dismiss()
                if (i != LocalPlayback.index) LocalPlayback.jumpTo(i)
            },
        )
        adapter.items = entries.indices.toList()
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            this.adapter = adapter
        }
        // A long list scrolls in a part of the sheet, showing the one playing.
        val height = minOf(entries.size * dp(64), (resources.displayMetrics.heightPixels * 0.6f).toInt())
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(now, dp(64))
        d = Sheets.sheet(this, col)
        d.showImmersive()
    }

    // ------------------------------------------------------------------ subtitles

    /** A new video: its subtitle file next to it if there is one, otherwise its own default ones. */
    private fun loadSubtitles(e: LocalPlayback.Entry) {
        subToken++
        subFile = null
        subFiles = emptyList()
        subAdded.clear()
        subReaders.clear()
        subOff = false
        subDelayMs = 0
        handler.removeCallbacks(subTicker)
        showCues(emptyList())
        setPlayerText(true, null)
        val f = e.file ?: return
        if (!e.video) return
        // 3.0.1: the timing chosen for this file last time.
        subDelayMs = LocalMedia.subtitleDelay(this, f)
        val token = subToken
        val lang = PlayerSettings.subtitleLanguage
        LocalPlayback.io.execute {
            val beside = Subtitles.besideVideo(f, lang)
            // 2.9.2: also a torrent's Subs folder; from there only an English file shows by itself
            // (3.0.1: or one in the language chosen in Settings).
            val found = beside + Subtitles.inSubsFolder(f, lang)
            val firstFile = beside.firstOrNull() ?: found.firstOrNull { Subtitles.inLanguage(it, lang.ifEmpty { "en" }) }
            val first = firstFile?.let { Subtitles.read(it) }
            handler.post {
                if (token != subToken || isDestroyed) return@post
                subFiles = found
                // A subtitle file named like the video is what its owner wants: shown rather than the video's own.
                if (first != null && !subOff && subFile == null) {
                    firstFile?.let { ff -> subReaders[first] = { enc -> Subtitles.read(ff, enc) } }
                    useSubtitles(first)
                    L.d("Player: showing a subtitle file found next to the video")
                }
            }
        }
    }

    /** 3.0: how each subtitle file shown can be read again in another encoding (the subtitle sheet's choice). */
    private val subReaders = HashMap<Subtitles.Track, (String) -> Subtitles.Track?>()

    /** 3.0: the encodings to read the subtitles in, when their letters come out wrong. */
    private fun encodingSheet() {
        val track = subFile ?: return
        val reader = subReaders[track] ?: return
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, "Text encoding", "Choose another when letters show as boxes or strange signs"))
        lateinit var d: BottomSheetDialog
        for ((name, label) in Subtitles.ENCODINGS) {
            col.addView(choiceRow(label, name.equals(track.encoding, ignoreCase = true), R.drawable.ic_translate) {
                d.dismiss()
                val token = subToken
                LocalPlayback.io.execute {
                    val again = try {
                        reader(name)
                    } catch (e: Exception) {
                        L.w("Player: subtitles in another encoding", e)
                        null
                    }
                    handler.post {
                        if (token != subToken || isDestroyed) return@post
                        if (again == null) {
                            say("Swift can't read these subtitles that way")
                            return@post
                        }
                        subReaders[again] = reader
                        val at = subAdded.indexOf(track)
                        if (at >= 0) subAdded[at] = again
                        useSubtitles(again)
                    }
                }
            })
        }
        d = Sheets.sheet(this, col)
        d.showImmersive()
    }

    /** The player's own subtitles of the video on or off, [override] choosing which. */
    private fun setPlayerText(on: Boolean, override: TrackSelectionOverride?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
        if (override != null) b.setOverrideForType(override)
        p.trackSelectionParameters = b.build()
    }

    private fun useSubtitles(track: Subtitles.Track) {
        subOff = false
        subFile = track
        setPlayerText(false, null)
        subShown = emptyList()
        handler.removeCallbacks(subTicker)
        handler.post(subTicker)
    }

    private fun subtitlesOff() {
        subOff = true
        subFile = null
        handler.removeCallbacks(subTicker)
        setPlayerText(false, null)
        showCues(emptyList())
    }

    private fun useVideoSubtitles(g: Tracks.Group, t: Int) {
        subOff = false
        subFile = null
        handler.removeCallbacks(subTicker)
        showCues(emptyList())
        setPlayerText(true, TrackSelectionOverride(g.mediaTrackGroup, t))
    }

    private fun readSubtitles(f: File) {
        val token = subToken
        LocalPlayback.io.execute {
            val track = Subtitles.read(f)
            handler.post {
                if (token != subToken || isDestroyed) return@post
                if (track == null) {
                    say("Swift can't read “${f.name}” as subtitles")
                } else {
                    subReaders[track] = { enc -> Subtitles.read(f, enc) }
                    useSubtitles(track)
                }
            }
        }
    }

    /** "Add subtitles…": a file picked with Android's picker (any type: subtitle files have no reliable one). */
    private val pickSubtitles = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val again = resumeAfterPick
        resumeAfterPick = false
        if (again) LocalPlayback.play()
        if (uri == null) return@registerForActivityResult
        val token = subToken
        LocalPlayback.io.execute {
            val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "subtitles"
            val track = try {
                contentResolver.openInputStream(uri)?.use { Subtitles.read(it, name) }
            } catch (e: Exception) {
                L.w("Couldn't read picked subtitles", e)
                null
            }
            handler.post {
                if (token != subToken || isDestroyed) return@post
                if (track == null) {
                    say("Swift can't read “$name” as subtitles. It reads .srt, .vtt, .ass and .ssa files.", long = true)
                } else {
                    subAdded += track
                    subReaders[track] = { enc -> contentResolver.openInputStream(uri)?.use { Subtitles.read(it, name, enc) } }
                    useSubtitles(track)
                    say("Showing $name")
                }
            }
        }
    }

    /**
     * 3.0.2: sound and subtitles in one sheet (VLC's Tracks): the sound tracks first when the video
     * has several, then the subtitles with their size, colour, background, timing and letters.
     */
    private fun tracksSheet() {
        val p = player ?: return
        val col = Sheets.column(this)
        lateinit var d: BottomSheetDialog
        val sounds = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val severalSounds = sounds.sumOf { it.length } > 1
        col.addView(Sheets.header(this, if (severalSounds) "Sound and subtitles" else "Subtitles", null))
        if (severalSounds) {
            col.addView(Sheets.label(this, "Sound"))
            var s = 0
            for (g in sounds) {
                for (t in 0 until g.length) {
                    s++
                    val name = trackName(g.getTrackFormat(t), s, "Sound") + if (!g.isTrackSupported(t, true)) " (this phone can't play it)" else ""
                    col.addView(choiceRow(name, g.isTrackSelected(t), R.drawable.ic_volume) {
                        d.dismiss()
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t))
                            .build()
                    })
                }
            }
            col.addView(Sheets.label(this, "Subtitles", top = 16))
        }
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val ownOn = subFile == null && !subOff && groups.any { it.isSelected }
        col.addView(choiceRow("Off", subFile == null && !ownOn, R.drawable.ic_close) {
            d.dismiss()
            subtitlesOff()
        })
        var n = 0
        for (g in groups) {
            for (t in 0 until g.length) {
                n++
                col.addView(choiceRow(trackName(g.getTrackFormat(t), n, "Subtitles"), subFile == null && !subOff && g.isTrackSelected(t), R.drawable.ic_subtitles) {
                    d.dismiss()
                    useVideoSubtitles(g, t)
                })
            }
        }
        for (f in subFiles) {
            col.addView(choiceRow(f.name, subFile?.label == f.name, R.drawable.ic_subtitles) {
                d.dismiss()
                readSubtitles(f)
            })
        }
        for (track in subAdded) {
            if (subFiles.any { it.name == track.label }) continue
            col.addView(choiceRow(track.label, subFile === track, R.drawable.ic_subtitles) {
                d.dismiss()
                useSubtitles(track)
            })
        }
        col.addView(Sheets.row(this, R.drawable.ic_add, "Add subtitles…") {
            d.dismiss()
            resumeAfterPick = LocalPlayback.playing
            try {
                pickSubtitles.launch(arrayOf("*/*"))
            } catch (_: Exception) {
                resumeAfterPick = false
                say("This phone has no app to pick files with")
            }
        })
        col.addView(Sheets.label(this, "Text size"))
        val sizes = PlayerSettings.SUBTITLE_SIZES
        val size = PlayerSettings.subtitleSize
        col.addView(Sheets.chipRow(this, sizes.map { "$it%" }, sizes.indices.minByOrNull { abs(sizes[it] - size) } ?: 1) { i ->
            PlayerSettings.subtitleSize = sizes[i]
            applySubtitleSize()
        })
        col.addView(Sheets.label(this, "Colour", top = 16))
        col.addView(Sheets.chipRow(this, PlayerSettings.SUBTITLE_COLOURS.map { it.first }, PlayerSettings.subtitleColour) { i ->
            PlayerSettings.subtitleColour = i
            applySubtitleSize()
        })
        col.addView(Sheets.label(this, "Background", top = 16))
        col.addView(Sheets.chipRow(this, PlayerSettings.SUBTITLE_BACKGROUNDS, PlayerSettings.subtitleBackground) { i ->
            PlayerSettings.subtitleBackground = i
            applySubtitleSize()
        })
        col.addView(Sheets.label(this, "Timing", top = 16))
        col.addView(delayRow(subFile != null))
        col.addView(Sheets.note(this, if (subFile != null) "Earlier if the words come after the voices, later if they come before." else "Timing can be changed for subtitle files. The video's own subtitles keep its timing."))
        // 3.0: a subtitle file's letters garbled (an old file in another alphabet's encoding): read it another way.
        val shown = subFile
        if (shown != null && subReaders[shown] != null) {
            col.addView(Sheets.label(this, "Letters", top = 16))
            col.addView(Sheets.row(this, R.drawable.ic_translate, "Text encoding: ${Subtitles.encodingLabel(shown.encoding)}") {
                d.dismiss()
                encodingSheet()
            })
        }
        d = Sheets.sheet(this, col)
        d.showImmersive()
    }

    /** Earlier · how far · Later, a quarter of a second a tap; tapping the middle puts it back. */
    private fun delayRow(enabled: Boolean): View {
        val value = Ui.text(this, Ui.Type.SUBTITLE, delayLabel()).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                subDelayMs = 0
                text = delayLabel()
                refreshSubtitles()
                saveSubtitleDelay()
            }
        }
        fun pill(label: String, step: Long) = Ui.text(this, Ui.Type.LABEL, label).apply {
            gravity = Gravity.CENTER
            background = Ui.ripple(this@PlayerActivity, Ui.shape(this@PlayerActivity, Palette.color(this@PlayerActivity, R.color.surface_alt), 20), 20)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            setOnClickListener {
                subDelayMs += step
                value.text = delayLabel()
                refreshSubtitles()
                saveSubtitleDelay()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(pill("Earlier", -250))
            addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill("Later", 250))
        }
    }

    /** 3.0.1: the timing stays with the file (a file on the phone; a website's video isn't kept). */
    private fun saveSubtitleDelay() {
        val f = LocalPlayback.current?.file ?: return
        LocalMedia.saveSubtitleDelay(this, f, subDelayMs)
    }

    private fun delayLabel(): String {
        val s = String.format(Locale.US, "%.2f", abs(subDelayMs) / 1000.0)
        return when {
            subDelayMs > 0 -> "$s s later"
            subDelayMs < 0 -> "$s s earlier"
            else -> "In time"
        }
    }

    private fun refreshSubtitles() {
        handler.removeCallbacks(subTicker)
        if (subFile != null) handler.post(subTicker)
    }

    /** A subtitle file's lines, checked ten times a second (with the delay applied). */
    private val subTicker = object : Runnable {
        override fun run() {
            val track = subFile ?: return
            val p = player ?: return
            // Out of sight (a video's sound in the background) nothing needs them.
            if (!LocalPlayback.screenVisible) return
            showCues(track.at((p.currentPosition - subDelayMs) * 1000))
            handler.postDelayed(this, 100)
        }
    }

    private fun applySubtitleSize() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (landscape) 21f else 17f) * PlayerSettings.subtitleSize / 100f)
        // 2.9.2: their colour, and a box (see-through or dark) or none behind them.
        subtitleText.setTextColor(PlayerSettings.SUBTITLE_COLOURS[PlayerSettings.subtitleColour].second)
        val box = when (PlayerSettings.subtitleBackground) {
            0 -> 0x8C000000.toInt()
            1 -> 0xE6000000.toInt()
            else -> 0
        }
        subtitleText.background = if (box != 0) Ui.shape(this, box, 6) else null
        // Without a box, a heavier shadow keeps them readable on a bright picture.
        subtitleText.setShadowLayer(Ui.dpf(this, if (box == 0) 3.5f else 2f), 0f, 0f, Color.BLACK)
    }

    /** Subtitles rise above the controls while they are shown. */
    private fun moveSubtitles() {
        val up = if (controlsShown && videoMode) (controls.height - dp(20)).coerceAtLeast(0).toFloat() else 0f
        subtitleText.animate().translationY(-up).setDuration(Motion.SHORT).start()
    }

    private fun showCues(cues: List<Cue>) {
        if (cues == subShown) return
        subShown = cues
        val text = SpannableStringBuilder()
        var picture: Cue? = null
        for (c in cues) {
            if (c.bitmap != null) {
                if (picture == null) picture = c
                continue
            }
            val t = c.text ?: continue
            if (text.isNotEmpty()) text.append('\n')
            text.append(t)
        }
        val showText = text.isNotEmpty() && videoMode
        subtitleText.text = if (showText) text else null
        subtitleText.visibility = if (showText) View.VISIBLE else View.GONE
        placePicture(if (videoMode) picture else null)
    }

    /** A picture subtitle (DVD and Blu-ray ones inside MKV files), where it says on the picture. */
    private fun placePicture(c: Cue?) {
        val bmp = c?.bitmap
        if (c == null || bmp == null || bmp.width <= 0) {
            subtitlePicture.visibility = View.GONE
            subtitlePicture.setImageBitmap(null)
            return
        }
        val r = frame.picture
        if (r.width() <= 0) return
        val w = (if (c.size != Cue.DIMEN_UNSET) r.width() * c.size else r.width() * 0.8f).roundToInt().coerceAtLeast(1)
        val h = (if (c.bitmapHeight != Cue.DIMEN_UNSET) r.height() * c.bitmapHeight else w.toFloat() * bmp.height / bmp.width).roundToInt().coerceAtLeast(1)
        var x = r.left + (if (c.position != Cue.DIMEN_UNSET) r.width() * c.position else r.width() / 2f)
        x -= when (c.positionAnchor) {
            Cue.ANCHOR_TYPE_MIDDLE -> w / 2f
            Cue.ANCHOR_TYPE_END -> w.toFloat()
            Cue.ANCHOR_TYPE_START -> 0f
            else -> if (c.position == Cue.DIMEN_UNSET) w / 2f else 0f
        }
        var y = r.top + (if (c.line != Cue.DIMEN_UNSET && c.lineType == Cue.LINE_TYPE_FRACTION) r.height() * c.line else r.height() * 0.92f)
        y -= when (c.lineAnchor) {
            Cue.ANCHOR_TYPE_MIDDLE -> h / 2f
            Cue.ANCHOR_TYPE_END -> h.toFloat()
            Cue.ANCHOR_TYPE_START -> 0f
            else -> if (c.line == Cue.DIMEN_UNSET) h.toFloat() else 0f
        }
        subtitlePicture.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START).apply {
            leftMargin = x.roundToInt()
            topMargin = y.roundToInt()
        }
        subtitlePicture.setImageBitmap(bmp)
        subtitlePicture.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ tracks and errors

    /** Sound or picture this phone can't decode (DTS sound, some HEVC): said once, with a way out. */
    private fun checkTracks(tracks: Tracks) {
        val e = LocalPlayback.current ?: return
        if (tracks.isEmpty) return
        if (e === shownEntry && e.video != videoMode) applyMode(e.video)
        if (warnedTracks === e) return
        // 2.9.2: 10-bit HEVC (small "x265 10bit" films). The phone's decoders say it is beyond them
        // rather than unsupported, so ExoPlayer tries anyway and the picture stays still: said plainly.
        val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.takeIf { it.length > 0 }?.getTrackFormat(0)
        if (video != null && tenBit(video) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, false)) {
            warnedTracks = e
            L.w("Player: 10-bit HEVC (${video.codecs}) beyond the phone's decoders")
            say("This video is 10-bit HEVC, which this phone's chip can't play. Look for an x264 or 8-bit version.", action = "Open with", long = true) { openWith() }
            return
        }
        val noPicture = tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true)
        val noSound = tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO, true)
        if (!noPicture && !noSound) {
            // 3.0.1: it plays, but maybe not on the chip (only Android's software decoder, or too big
            // or fast for the chip): said once, so a stutter isn't a mystery. Asked off the main thread.
            if (video != null && hardwareChecked !== e) {
                hardwareChecked = e
                LocalPlayback.io.execute {
                    val hint = com.swiftbrowser.media.HardwareVideo.hint(video.sampleMimeType, video.width, video.height, video.frameRate)
                    if (hint != null) {
                        handler.post {
                            if (LocalPlayback.current === e && !isDestroyed && warnedTracks !== e) {
                                warnedTracks = e
                                L.w("Player: ${video.sampleMimeType} ${video.width}x${video.height} isn't for the phone's chip")
                                say(hint, long = true)
                            }
                        }
                    }
                }
            }
            return
        }
        warnedTracks = e
        val type = if (noPicture) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
        val format = tracks.groups.firstOrNull { it.type == type }?.takeIf { it.length > 0 }?.getTrackFormat(0)
        val codec = format?.let { codecName(it) }
        val what = if (noPicture) "picture" else "sound"
        L.w("Player: no decoder for the $what (${codec ?: "unknown"}, ${format?.codecs})")
        say("This phone can't play this video's $what" + (codec?.let { " ($it)" } ?: "") + ". Another app may.", action = "Open with", long = true) { openWith() }
    }

    private fun showError(error: PlaybackException) {
        val e = LocalPlayback.current
        val network = e?.stream == true
        val message = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "Swift couldn't reach the video. Check the connection and try again."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            -> "The site didn't let Swift play this video outside its page."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This file isn't there any more."
            // 3.0: another app's song whose access ended (that app took it back): opened again, it plays.
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                if (e != null && e.file == null && !e.stream) "Swift can't read this any more. Open it again from its app to play it." else "Swift isn't allowed to read this file."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            -> "Swift can't read this kind of file, or the file is damaged."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            -> "This phone can't play this format."
            else -> if (error.errorCode in 6000..6999) "This video is protected and can't be played here." else "Swift can't play this."
        }
        showProblem(message, openWith = e != null, retry = network || error.errorCode in 2000..2999)
    }

    /** What went wrong, in the middle of the screen, with what can be done about it. */
    private fun showProblem(message: String, openWith: Boolean, retry: Boolean) {
        hideProblem()
        handler.removeCallbacks(showSpinner)
        spinner.visibility = View.GONE
        if (locked) setLocked(false)
        showControls(true)
        val fg = if (videoMode) Color.WHITE else Palette.color(this, R.color.text_primary)
        fun pill(text: String, main: Boolean, click: () -> Unit) = Ui.text(this, Ui.Type.LABEL, text, if (main) Ui.onAccent(this) else fg).apply {
            gravity = Gravity.CENTER
            minWidth = dp(220)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = Ui.ripple(this@PlayerActivity, Ui.shape(this@PlayerActivity, if (main) Ui.accent(this@PlayerActivity) else Color.TRANSPARENT, 24, if (main) null else (fg and 0x66FFFFFF)), 24)
            setOnClickListener { click() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
            addView(ImageView(this@PlayerActivity).apply {
                setImageDrawable(Ui.icon(this@PlayerActivity, R.drawable.ic_error, fg))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(Ui.text(this@PlayerActivity, Ui.Type.BODY, message, fg).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(18))
            })
            val gap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) } }
            if (openWith) addView(pill("Open with another app", true) { openWith() }, gap())
            if (retry) addView(pill("Try again", !openWith) { LocalPlayback.play() }, gap())
            if (player?.hasNextMediaItem() == true) addView(pill("Next", false) { LocalPlayback.next() }, gap())
        }
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        problem = panel
        updatePlaying()
    }

    private fun hideProblem() {
        problem?.let { root.removeView(it) }
        problem = null
    }

    // ------------------------------------------------------------------ handing it on

    /** Android's "Open with" list without Swift's player; what plays here pauses. */
    private fun openWith() {
        val e = LocalPlayback.current ?: return
        val f = e.file
        LocalPlayback.pause()
        if (f == null) {
            val type = when {
                e.hls -> "application/x-mpegURL"
                e.dash -> "application/dash+xml"
                e.stream -> if (e.video) "video/*" else "audio/*"
                else -> try {
                    contentResolver.getType(e.uri)
                } catch (_: Exception) {
                    null
                } ?: if (e.video) "video/*" else "audio/*"
            }
            launchOpenWith(e.uri, type)
            return
        }
        // Files on a memory card may need a copy to be handed on: not on the main thread.
        LocalPlayback.io.execute {
            val uri = Docs.shareUri(this, f)
            handler.post {
                if (isDestroyed) return@post
                if (uri == null) say("Swift couldn't hand this file to another app") else launchOpenWith(uri, UrlUtils.mimeFromName(f.name) ?: if (e.video) "video/*" else "audio/*")
            }
        }
    }

    private fun launchOpenWith(uri: Uri, type: String) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(
                Intent.createChooser(view, "Open with")
                    .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, PlayerActivity::class.java))),
            )
        } catch (_: Exception) {
            say("No app on this phone can open it")
        }
    }

    private fun share(f: File) {
        LocalPlayback.io.execute {
            val uri = Docs.shareUri(this, f)
            handler.post {
                if (isDestroyed) return@post
                if (uri == null) {
                    say("Swift couldn't share this file")
                    return@post
                }
                val send = Intent(Intent.ACTION_SEND).setType(UrlUtils.mimeFromName(f.name) ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(Intent.createChooser(send, "Share ${f.name}"))
                } catch (_: Exception) {
                    say("No app on this phone can share it")
                }
            }
        }
    }

    private fun details() {
        val e = LocalPlayback.current ?: return
        val p = player ?: return
        val lines = ArrayList<String>()
        e.file?.let { f ->
            lines += "Size: ${UrlUtils.formatBytes(f.length())}"
            if (!e.private) lines += "Folder: ${f.parent}"
        }
        if (e.stream && !e.incognito) UrlUtils.host(e.uri.toString())?.let { lines += "From: $it" + if (e.hls) " (stream)" else "" }
        p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { lines += "Length: ${time(it)}" }
        p.videoFormat?.let { v ->
            val rate = v.frameRate
            val fps = when {
                rate <= 0f -> ""
                abs(rate - rate.roundToInt()) < 0.01f -> ", ${rate.roundToInt()} frames a second"
                else -> String.format(Locale.US, ", %.3f frames a second", rate)
            }
            lines += "Picture: ${v.width} × ${v.height}" + (codecName(v)?.let { ", $it" } ?: "") + (if (tenBit(v)) " 10-bit" else "") + fps
        }
        p.audioFormat?.let { a ->
            lines += "Sound: " + listOfNotNull(codecName(a), channels(a.channelCount), if (a.sampleRate > 0) "${a.sampleRate / 1000.0} kHz".replace(".0 ", " ") else null).joinToString(", ")
        }
        Dialogs.info(this, e.title, lines.joinToString("\n").ifBlank { "Nothing more is known about it." })
    }

    /** HEVC in 10 bits (Main 10 profile, "hvc1.2…"), which phones of this age can't decode. */
    private fun tenBit(f: Format): Boolean =
        f.sampleMimeType == MimeTypes.VIDEO_H265 &&
            (f.codecs?.let { it.startsWith("hvc1.2") || it.startsWith("hev1.2") } == true || f.colorInfo?.lumaBitdepth == 10)

    private fun trackName(f: Format, n: Int, fallback: String): String {
        val parts = ArrayList<String>()
        f.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }?.let { code ->
            val lang = Locale.forLanguageTag(code).displayLanguage
            if (lang.isNotBlank() && parts.none { it.contains(lang, ignoreCase = true) }) parts += lang
        }
        channels(f.channelCount)?.let { parts += it }
        if (MimeTypes.isAudio(f.sampleMimeType)) codecName(f)?.let { parts += it }
        return if (parts.isEmpty()) "$fallback $n" else "$n. " + parts.joinToString(" · ")
    }

    private fun channels(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "mono"
        count == 2 -> "stereo"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count channels"
    }

    private fun codecName(f: Format): String? = when (f.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "H.265 (HEVC)"
        MimeTypes.VIDEO_VP8 -> "VP8"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_MP4V -> "MPEG-4"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_H263 -> "H.263"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC-3)"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        MimeTypes.AUDIO_RAW -> "PCM"
        MimeTypes.AUDIO_AMR_NB, MimeTypes.AUDIO_AMR_WB -> "AMR"
        null -> null
        else -> f.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)
    }

    // ------------------------------------------------------------------ keys and the screen's life

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE -> LocalPlayback.toggle()
            KeyEvent.KEYCODE_MEDIA_PLAY -> LocalPlayback.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> LocalPlayback.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> LocalPlayback.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> LocalPlayback.previous()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> LocalPlayback.seekBy(-10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> LocalPlayback.seekBy(10_000)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBars()
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        // Settings changed meanwhile apply from now on.
        gesturesEnabled = PlayerSettings.gestures
        applySubtitleSize()
        if (LocalPlayback.player == null) {
            // A file that couldn't be opened: its message stays.
            if (problem != null) return
            // Closed from the notification while away.
            closing = true
            finish()
            return
        }
        LocalPlayback.screenVisible = true
        attach()
        LocalPlayback.setPictureOff(false)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        refreshSubtitles()
        MusicService.sync(this)
    }

    override fun onStop() {
        LocalPlayback.screenVisible = false
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(subTicker)
        if (!isFinishing && !isChangingConfigurations) {
            val e = LocalPlayback.current
            if (e != null) {
                // Out of sight: a video pauses unless it plays in the background (then without its
                // picture, which saves the battery); music goes on unless Settings say otherwise.
                val goOn = if (e.video) LocalPlayback.background else PlayerSettings.backgroundAudio
                if (!goOn) LocalPlayback.pause() else if (e.video) LocalPlayback.setPictureOff(true)
            }
        }
        LocalPlayback.saveNow()
        MusicService.sync(this)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        LocalPlayback.unwatch(this)
        player?.let {
            it.removeListener(events)
            it.clearVideoSurfaceView(surface)
        }
        player = null
        // Gone without Back (taken out of recent apps): as Back would have done.
        if (isFinishing && !closing) {
            val e = LocalPlayback.current
            if (e != null && (if (e.video) !LocalPlayback.background else !PlayerSettings.backgroundAudio)) LocalPlayback.release()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY_FILE = "com.swiftbrowser.player.PLAY_FILE"
        const val ACTION_PLAY_STREAM = "com.swiftbrowser.player.PLAY_STREAM"

        /** Back to what plays (the notification). */
        const val ACTION_SHOW = "com.swiftbrowser.player.SHOW"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_INCOGNITO = "incognito"
        private const val EXTRA_DASH = "dash"

        /** Posts that must happen even after the screen has gone (the folder's playlist for music). */
        private val mainHandler = Handler(Looper.getMainLooper())

        /** Endings ExoPlayer reads; whether the phone decodes what is inside is known when it plays. */
        private val VIDEOS = setOf("mp4", "m4v", "mkv", "webm", "3gp", "3g2", "mov", "ts", "m2ts", "mts", "avi", "flv", "mpg", "mpeg")
        private val SOUNDS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "awb", "mka", "weba", "3ga")

        /** A video or music by the file's ending; null for anything else. */
        fun kindOf(name: String): Kind? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            in VIDEOS -> Kind.VIDEO
            in SOUNDS -> Kind.AUDIO
            else -> null
        }

        /**
         * 2.9.2: what plays [file] from outside an activity (a finished download's Play). 3.0:
         * [incognito] for an incognito download (nothing about it is remembered).
         */
        fun intentFor(ctx: Context, file: File, incognito: Boolean = false): Intent =
            ViewerAccess.mark(
                Intent(ctx, PlayerActivity::class.java).setAction(ACTION_PLAY_FILE).putExtra(EXTRA_PATH, file.path)
                    .putExtra(EXTRA_INCOGNITO, incognito).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )

        /**
         * Plays [file] (a video or song on the phone); the others of its folder are the playlist.
         * 3.0: [incognito] as for [intentFor]; [secure]: a file of Private (kept out of screenshots,
         * nothing remembered, nothing on the lock screen).
         */
        fun open(from: Context, file: File, incognito: Boolean = false, secure: Boolean = false) {
            val i = Intent(from, PlayerActivity::class.java).setAction(ACTION_PLAY_FILE).putExtra(EXTRA_PATH, file.path)
                .putExtra(EXTRA_INCOGNITO, incognito)
            if (secure) i.putExtra(AppFiles.EXTRA_SECURE, true)
            if (from !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            from.startActivity(ViewerAccess.mark(i))
        }

        /**
         * Plays a website video: an HLS stream (.m3u8) or a plain file at [url], fetched with the
         * page's [headers] (Referer, User-Agent; no cookies, see GeckoDataSource). [incognito]: nothing
         * about it is kept, logged or shown on the lock screen.
         */
        fun openStream(from: Context, url: String, title: String?, headers: Map<String, String> = emptyMap(), incognito: Boolean = false, dash: Boolean = false) {
            val h = Bundle().apply { for ((k, v) in headers) putString(k, v) }
            val i = Intent(from, PlayerActivity::class.java)
                .setAction(ACTION_PLAY_STREAM)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HEADERS, h)
                .putExtra(EXTRA_INCOGNITO, incognito)
                .putExtra(EXTRA_DASH, dash)
            // 3.0: only Swift's pages hand over a website video.
            ViewerAccess.mark(i)
            if (from !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            from.startActivity(i)
        }

        /** Brings back the player screen as it is (the notification opens it with this). */
        fun showIntent(ctx: Context): Intent = Intent(ctx, PlayerActivity::class.java).setAction(ACTION_SHOW)

        /** "Film 2.mkv" before "Film 10.mkv": numbers in names count as numbers. */
        fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca in '0'..'9' && cb in '0'..'9') {
                    var ei = i
                    while (ei < a.length && a[ei] in '0'..'9') ei++
                    var ej = j
                    while (ej < b.length && b[ej] in '0'..'9') ej++
                    val na = a.substring(i, ei).trimStart('0')
                    val nb = b.substring(j, ej).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                    i = ei
                    j = ej
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
