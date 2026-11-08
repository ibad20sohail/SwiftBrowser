package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.view.MotionEvent
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
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.AppFiles
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.files.FileKind
import com.swiftbrowser.files.FileThumbs
import com.swiftbrowser.media.Delays
import com.swiftbrowser.media.EqualizerRules
import com.swiftbrowser.media.LocalMedia
import com.swiftbrowser.media.LocalPlayback
import com.swiftbrowser.media.MusicService
import com.swiftbrowser.media.PlayerSettings
import com.swiftbrowser.media.SoundEffects
import com.swiftbrowser.media.Subtitles
import com.swiftbrowser.media.TrackChoice
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
 * (MusicService), the player being shared with it (LocalPlayback). 3.0.3: menus are panels on this
 * screen beside the video (PlayerPanel), no longer sheets over it.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity(), PlayerGestures.Host, LocalPlayback.Watcher, PlayerPanel.Host, Messages.DarkScreen {
    // 3.0.3: over a video the screen is black: messages take the dark colours there (music: Swift's).
    override val darkMessages: Boolean get() = videoMode

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

    /** The icon the readout shows, and that it is fading out. */
    private var hudIconShown = 0
    private var hudLeaving = false
    private lateinit var spinner: LoadingIndicator
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var countView: TextView

    // The title and "2 of 7": beside the buttons, or on a line of their own under them (see arrangeUpright).
    private lateinit var titleBox: LinearLayout
    private lateinit var topRow: LinearLayout
    private lateinit var titleGap: View
    private var titleBelow = false
    private lateinit var controls: LinearLayout
    private lateinit var seek: SwiftSlider
    private lateinit var elapsed: TextView
    private lateinit var length: TextView
    private lateinit var playButton: ImageButton

    /** 3.0.4: the play button's icon, morphing between play and pause. */
    private lateinit var playIcon: PlayPauseIcon
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
    private lateinit var speedButton: ImageButton
    private var chipsShown = ""

    /** The chips showing, by their icon, and those before the row was made again: only a new one fades in. */
    private var chipKinds = HashSet<Int>()
    private var chipKindsBefore: Set<Int> = emptySet()
    private var problem: View? = null

    // 3.0.3: menus in a panel beside the video (PlayerPanel); Back closes it first.
    private lateinit var panel: PlayerPanel
    private lateinit var panelBack: OnBackPressedCallback

    /** Where the picture went for an upright panel (its move and scale), so a picture subtitle goes with it. */
    private var videoShift = 0f
    private var videoScale = 1f

    // 3.0.3: "Up next" at the end of a video of a playlist, counting down to the next.
    private var upNext: View? = null
    private var upNextCount: TextView? = null
    private var upNextThumb: FileThumbs.Request? = null
    private var upNextLeft = 0

    /** 3.0.3: where the subtitles were dragged to (a share of the height), and the drag under way (NaN: none). */
    private var subLift = 0f
    private var liftDrag = Float.NaN
    private var liftFrom = 0f

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

    // Subtitles: a file shown by this screen, or the video's own (shown through the player). 3.0.3:
    // their timing is LocalPlayback's (the same for both).
    private var subFile: Subtitles.Track? = null
    private var subFiles: List<File> = emptyList()
    private val subAdded = ArrayList<Subtitles.Track>()
    private var subOff = false
    private var subShown: List<Cue> = emptyList()
    private var subToken = 0
    private var resumeAfterPick = false

    /** 3.0.3: where each subtitle file shown was read from (its path, or a content:// address), to keep it as chosen. */
    private val subSources = HashMap<Subtitles.Track, String>()

    /** 3.0.3: the video's own subtitles chosen last time for this file, chosen again once its tracks are known. */
    private var pendingSubtitles: TrackChoice? = null

    /** 3.0.4: the start and end of the part repeated (A-B), on the seek bar's track. */
    private lateinit var loopMarks: LoopMarks

    // 3.0.4: frame by frame (⋮): a bar above the controls, a frame at a time.
    private lateinit var frameBar: LinearLayout
    private lateinit var frameTime: TextView
    private lateinit var frameBack: OnBackPressedCallback

    /** The bar is open (also read on the playback thread, by frameWatch). */
    @Volatile
    private var frameMode = false

    /** The time (µs) of the frame the picture shows, from the playback thread; C.TIME_UNSET until one is shown. */
    @Volatile
    private var shownFrameUs = C.TIME_UNSET

    /** The seek parameters from before the bar opened, put back as it closes. */
    private var seekBefore: SeekParameters? = null

    /** A step's seek is under way (its frame hasn't been shown yet): further steps wait. */
    private var stepPending = false

    // 3.0.4: the music screen's colours (background, accent, on the accent, the seek bar's rest): as
    // shown now, and from and to while they change. The cover's (CoverColours), or Swift's own.
    private val musicShown = IntArray(4)
    private val musicFrom = IntArray(4)
    private val musicTo = IntArray(4)

    /** The cover's colours shown (null: Swift's own), and the cover they were worked out from. */
    private var coverScheme: CoverColours.Scheme? = null
    private var coverFor: Any? = null

    /** What the cover shows: its picture, or the accent the picture for no cover was made in (null: nothing yet). */
    private var coverDrawn: Any? = null

    /** A song started without its picture yet: the last one stays a moment (see showCover). */
    private var coverWaiting = false

    /** The music screen's colours changing to another song's (300 ms). */
    private val coverFade = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Motion.LONG
        interpolator = Motion.standard
        addUpdateListener {
            val f = it.animatedValue as Float
            for (i in musicShown.indices) musicShown[i] = androidx.core.graphics.ColorUtils.blendARGB(musicFrom[i], musicTo[i], f)
            paintMusic()
        }
    }

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
        // 3.0.4: Back closes frame by frame's bar (after a panel, before the screen).
        frameBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeFrameBar()
        }
        onBackPressedDispatcher.addCallback(this, frameBack)
        // 3.0.3: Back closes a panel first (added last, so asked first).
        panelBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = panel.close()
        }
        onBackPressedDispatcher.addCallback(this, panelBack)
        subLift = PlayerSettings.subtitleLift
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
                it.clearVideoFrameMetadataListener(frameWatch)
            }
            player = p
            p.addListener(events)
            p.setVideoSurfaceView(surface)
            // 3.0.4: each frame's own time as it is shown, for frame by frame.
            shownFrameUs = C.TIME_UNSET
            p.setVideoFrameMetadataListener(frameWatch)
        }
        p.videoSize.let { frame.setVideoSize(it.width, it.height, it.pixelWidthHeightRatio) }
        onEntryChanged()
        // 3.0.4: the play button as it is, without a morph (the screen is only now shown again).
        updatePlaying(animate = false)
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
            // 3.0.3: whether there is a next one to go on to.
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) {
                updateSteps()
            }
        }

        // 3.0.3: a seek away from the end puts Up next away (the video goes on there).
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && upNext != null) hideUpNext()
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
            hintPinch()
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
            // 3.0.3: what a panel showed was about the one before; Up next has happened.
            hideUpNext()
            panel.close()
            // 3.0.4: frame by frame was for the one before too.
            closeFrameBar()
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
        // 3.0.4: a part to repeat set or cleared.
        updateLoopMarks()
        // 3.0: a call taking the sound away (or giving it back) shows on the play button.
        updatePlaying()
    }

    /** 3.0: the volume boost's sign, in a corner while it is on (a tap turns it off). */
    private fun updateBoost() {
        val b = LocalPlayback.boost
        if (b <= 0f) {
            Motion.pop(boostBadge, false)
            return
        }
        setIfChanged(boostBadge, "Boost ${100 + (b * 100).roundToInt()}%")
        Motion.pop(boostBadge, true)
    }

    /** Text that changes while watched: set only when it is different (setting it measures the screen again). */
    private fun setIfChanged(v: TextView, value: CharSequence) {
        if (!TextUtils.equals(v.text, value)) v.text = value
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
            background = Ui.shape(this@PlayerActivity, 0x8C000000.toInt(), Ui.R_XSMALL)
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

        // 3.0.4: Swift's loading indicator (its colours follow the screen, see applyMode).
        spinner = LoadingIndicator(this).apply { visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        buildHud()
        buildTopBar()
        buildControls()

        // 3.0: shown while the volume boost is on, also with the controls hidden.
        boostBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            Ui.size(this, Ui.Type.CAPTION, keepFont = true)
            typeface = Ui.medium(Ui.Type.CAPTION)
            Ui.steadyFigures(this)
            background = Ui.ripple(this@PlayerActivity, Ui.shape(this@PlayerActivity, OverPicture.FILL_SOFT, Ui.R_SMALL), Ui.R_SMALL, Ui.RIPPLE_ON_DARK)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            contentDescription = "Volume boost on. Tap to turn it off"
            visibility = View.GONE
            setOnClickListener {
                LocalPlayback.setBoost(0f)
                updateBoost()
                say("Volume boost off")
            }
        }
        root.addView(boostBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(64), dp(16), 0)
        })

        unlockButton = ImageButton(this).apply {
            // 3.0.4: the lock filled while locked (outlined on "Lock the screen"), as Swift's icons are when on.
            setImageResource(R.drawable.ic_lock_on)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = Ui.ripple(this@PlayerActivity, Ui.circle(OverPicture.FILL_SOFT), UNLOCK_DP / 2, Ui.RIPPLE_ON_DARK)
            Ui.pressable(this, Motion.PRESS_ICON)
            contentDescription = "Unlock the screen"
            visibility = View.GONE
            setOnClickListener {
                Haptics.tick(root)
                setLocked(false)
            }
        }
        root.addView(unlockButton, FrameLayout.LayoutParams(dp(UNLOCK_DP), dp(UNLOCK_DP), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(20) })
        panel = PlayerPanel(root, this)
        setContentView(root)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun button(icon: Int, label: String, click: () -> Unit): ImageButton = ImageButton(this, null, 0, R.style.Swift_ToolbarButton).apply {
        setImageResource(icon)
        contentDescription = label
        // A ripple that can be seen on the dark picture also in the light look (paintText follows the screen's colours).
        background = Ui.rippleBorderless(this@PlayerActivity, Ui.RIPPLE_ON_DARK)
        setOnClickListener { click() }
        // 3.0.3: holding one shows its name, and a press springs.
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, label)
        Ui.pressable(this, Motion.PRESS_ICON, morph = false)
        icons += this
    }

    private fun buildHud() {
        hudIcon = ImageView(this)
        hudText = TextView(this).apply {
            setTextColor(Color.WHITE)
            Ui.size(this, Ui.Type.HEADING, keepFont = true)
            typeface = Ui.MEDIUM
            gravity = Gravity.CENTER
            Ui.steadyFigures(this)
        }
        hudSub = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            Ui.size(this, Ui.Type.LABEL, keepFont = true)
            gravity = Gravity.CENTER
            Ui.steadyFigures(this)
        }
        hudLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = androidx.core.content.ContextCompat.getDrawable(this@PlayerActivity, R.drawable.progress_rounded)
            // The dark look's accent: the box is dark also in the light look.
            progressTintList = ColorStateList.valueOf(Palette.colorFor(this@PlayerActivity, true, Looks.accent.primary))
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        }
        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.shape(this@PlayerActivity, OverPicture.FILL_HUD, Ui.R_MEDIUM)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
            addView(hudIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
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
            Ui.size(this, Ui.Type.HEADING, keepFont = true)
            typeface = Ui.medium(Ui.Type.HEADING)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        countView = TextView(this).apply {
            Ui.size(this, Ui.Type.CAPTION, keepFont = true)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        rotateButton = button(R.drawable.ic_rotate, "Turn the screen") { rotationPanel() }
        subtitlesButton = button(R.drawable.ic_subtitles, "Sound and subtitles") { tracksPanel() }
        playlistButton = button(R.drawable.ic_view_list, "Playlist") { playlistPanel() }
        val size = dp(48)
        titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(4), 0)
            addView(titleView)
            addView(countView)
        }
        // Keeps the buttons at the end while the title is on the line under them.
        titleGap = View(this).apply { visibility = View.GONE }
        topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(button(R.drawable.ic_back, "Back") { close() }, LinearLayout.LayoutParams(size, size))
            addView(titleGap, LinearLayout.LayoutParams(0, 1, 1f))
            addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(subtitlesButton, LinearLayout.LayoutParams(size, size))
            addView(rotateButton, LinearLayout.LayoutParams(size, size))
            addView(playlistButton, LinearLayout.LayoutParams(size, size))
            addView(button(R.drawable.ic_more, "More") { morePanel() }, LinearLayout.LayoutParams(size, size))
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Room below for the shade to fade out, so the title reads over any picture.
            setPadding(dp(4), dp(4), dp(4), dp(36))
            addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // 3.0.4: the chips on a line of their own under the title, as wide as the bar. Upright, the
            // title's column beside four buttons left them about 60 dp ("A 6:39 · tap to set the end" was cut short).
            addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                marginStart = size + dp(8)
            })
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private fun buildControls() {
        elapsed = TextView(this).apply {
            Ui.size(this, Ui.Type.CAPTION, keepFont = true)
            Ui.steadyFigures(this)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            isSingleLine = true
            // As high as the length beside it, so the two read on one line.
            minHeight = dp(48)
            setPadding(0, dp(8), 0, 0)
        }
        length = TextView(this).apply {
            Ui.size(this, Ui.Type.CAPTION, keepFont = true)
            Ui.steadyFigures(this)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            isSingleLine = true
            // The seek bar's finger area covers its lower part; the taller box keeps a place to tap.
            minHeight = dp(48)
            setPadding(0, dp(8), 0, 0)
            background = Ui.ripple(this@PlayerActivity, null, Ui.R_SMALL, Ui.RIPPLE_ON_DARK)
            contentDescription = "Length (tap for the time left)"
            setOnClickListener {
                showRemaining = !showRemaining
                updateProgress()
            }
        }
        // 3.0.4: Swift's own slider (a SeekBar still); its colours and look follow the screen (applyMode).
        seek = SwiftSlider(this, SwiftSlider.Style.VIDEO).apply {
            // Its edges where the times' are, and a 40 dp finger area that looks no taller (no TouchDelegate:
            // it would aim the touch at the bar's middle and the video would jump).
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    setIfChanged(elapsed, time(value.toLong()))
                    previewAt(value.toLong())
                }

                override fun onStartTrackingTouch(bar: SeekBar) = scrubStart()

                override fun onStopTrackingTouch(bar: SeekBar) {
                    scrubEnd(bar.progress.toLong())
                    scheduleHide()
                }
            })
        }
        // 3.0.4: the part repeated (A-B), as ticks on the track (drawn with it, so under the handle).
        loopMarks = LoopMarks(this, Color.WHITE)
        seek.marks = loopMarks
        playIcon = PlayPauseIcon(this)
        playButton = ImageButton(this).apply {
            background = Ui.circle(Ui.accent(this@PlayerActivity))
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(playIcon)
            contentDescription = "Play"
            Ui.pressable(this, Motion.PRESS_ICON)
            setOnClickListener {
                LocalPlayback.toggle()
                scheduleHide()
            }
        }
        lockButton = button(R.drawable.ic_lock, "Lock the screen") {
            // 3.0.3: felt as well as seen (Settings' light vibration).
            Haptics.tick(root)
            setLocked(true)
        }
        fitButton = button(R.drawable.ic_aspect_ratio, "Picture size") { nextFit() }
        shuffleButton = button(R.drawable.ic_shuffle, "Shuffle") { toggleShuffle() }
        repeatButton = button(R.drawable.ic_repeat, "Repeat") { nextRepeat() }
        // Bigger glyphs than the buttons around them, so the play group stands out.
        previousButton = button(R.drawable.ic_media_previous, "Previous") { LocalPlayback.previous() }.apply { bigGlyph(this) }
        nextButton = button(R.drawable.ic_media_next, "Next") { LocalPlayback.next() }.apply { bigGlyph(this) }
        speedButton = button(R.drawable.ic_speed, "Playback speed") { speedPanel() }
        val small = dp(44)
        // 3.0.3: previous and next beside play (skipping back and on is the double taps' alone), at
        // every width. Lock and picture size (music: shuffle) at the start, speed (music: repeat) at
        // the end, the two sides as wide, so play stays in the middle (see arrangeButtons).
        val start = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            addView(lockButton, LinearLayout.LayoutParams(small, small))
            addView(shuffleButton, LinearLayout.LayoutParams(small, small))
            addView(fitButton, LinearLayout.LayoutParams(small, small))
        }
        val end = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(speedButton, LinearLayout.LayoutParams(small, small))
            addView(repeatButton, LinearLayout.LayoutParams(small, small))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(previousButton, LinearLayout.LayoutParams(small, small))
            addView(playButton, LinearLayout.LayoutParams(dp(60), dp(60)).apply { setMargins(dp(8), 0, dp(8), 0) })
            addView(nextButton, LinearLayout.LayoutParams(small, small))
            addView(end, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // The times above a bar as wide as the screen (3.0.2: the bar no longer shares its line).
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            // A fixed width: setting the time then doesn't lay the screen out again every half second.
            addView(elapsed, LinearLayout.LayoutParams(dp(TIME_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(this@PlayerActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(length, LinearLayout.LayoutParams(dp(TIME_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        frameBar = buildFrameBar()
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(44), dp(8), dp(10))
            // 3.0.4: frame by frame's bar, above the times while it is open (it shows and hides with the controls).
            addView(frameBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            })
            // Pulled down onto the bar, so the times read as the bar's own (the length keeps its tap room).
            // 3.0.4: 12 dp: the new bar is 6 dp taller (its handle grows while dragged); its track stays where it was.
            addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = -dp(12) })
            // Its finger area reaches over what is around it, taking no more room.
            addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = -dp(8)
                bottomMargin = -dp(8)
            })
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun bigGlyph(b: ImageButton) {
        b.scaleType = ImageView.ScaleType.FIT_CENTER
        b.setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    /**
     * 3.0.2: the buttons that make sense now. 3.0.3: video: lock · fit | previous · play · next |
     * speed; music: shuffle | previous · play · next | repeat.
     */
    private fun arrangeButtons() {
        val video = videoMode
        for (v in listOf(lockButton, fitButton, rotateButton, subtitlesButton, speedButton)) v.visibility = if (video) View.VISIBLE else View.GONE
        for (v in listOf(shuffleButton, repeatButton)) v.visibility = if (video) View.GONE else View.VISIBLE
        updateSteps()
        arrangeUpright()
    }

    /**
     * Upright the row at the bottom fits a 360 dp phone with smaller gaps beside play. Over a video
     * the title then has a line of its own under the buttons, starting where the chips do (about
     * twelve letters fit beside four buttons). Turned, and on the music screen, as it always was.
     */
    private fun arrangeUpright() {
        val upright = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val gap = dp(if (upright) 8 else 18)
        (playButton.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.leftMargin != gap) {
                lp.setMargins(gap, 0, gap, 0)
                playButton.layoutParams = lp
            }
        }
        val below = upright && videoMode
        if (below == titleBelow) return
        titleBelow = below
        (titleBox.parent as? ViewGroup)?.removeView(titleBox)
        titleGap.visibility = if (below) View.VISIBLE else View.GONE
        titleBox.setPadding(dp(8), if (below) 0 else dp(12), dp(4), 0)
        if (below) {
            topBar.addView(titleBox, 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(48) })
        } else {
            topRow.addView(titleBox, 2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // The boost's sign keeps clear of the title's line.
        (boostBadge.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.topMargin = dp(if (below) 96 else 64)
            boostBadge.layoutParams = lp
        }
    }

    /**
     * 3.0.3: next is greyed out with nothing to go on to (repeating all counts as something);
     * previous always works, starting this one again when there is none before it.
     */
    private fun updateSteps() {
        val has = player?.hasNextMediaItem() == true
        nextButton.isEnabled = has
        nextButton.alpha = if (has) 1f else Ui.DISABLED_ALPHA
    }

    /**
     * 3.0.2, VLC's chips: under the title, only what isn't as usual (a speed, the sleep timer, the
     * subtitles' timing; 3.0.3: also for the video's own subtitles, and the sound's timing; 3.0.4:
     * the part repeated, and night sound). A tap opens its choices, holding puts it back. Looked at
     * with the progress.
     */
    private fun updateChips() {
        val p = player ?: return
        val speed = p.playbackParameters.speed
        val sleep = LocalPlayback.sleepLabel()
        val subDelay = LocalPlayback.subtitleDelayMs
        val soundDelay = LocalPlayback.soundDelayMs
        val subtitlesOn = subFile != null || p.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)
        // 3.0.4: the subtitles button is filled while subtitles show, as Swift's icons are when on.
        Motion.swapIcon(subtitlesButton, OnIcons.of(R.drawable.ic_subtitles, subtitlesOn))
        OnIcons.show(speedButton, R.drawable.ic_speed, abs(speed - 1f) > 0.01f)
        val subChip = if (videoMode && subtitlesOn && subDelay != 0L) "Subtitles ${Delays.label(subDelay)}" else null
        val soundChip = if (videoMode && soundDelay != 0L) "Sound ${Delays.label(soundDelay)}" else null
        // 3.0.4: night sound, while it is on, and the part repeated (A-B) or its start waiting for an end.
        val night = LocalPlayback.nightSoundOn
        val a = LocalPlayback.loopStartMs
        val b = LocalPlayback.loopEndMs
        val loopChip = when {
            a == C.TIME_UNSET -> null
            b == C.TIME_UNSET -> "A ${time(a)} · tap to set the end"
            else -> "A-B ${time(a)}–${time(b)}"
        }
        val key = "$speed|$sleep|$subChip|$soundChip|$night|$loopChip|$videoMode"
        if (key == chipsShown) return
        chipsShown = key
        chipKindsBefore = chipKinds
        chipKinds = HashSet()
        chips.removeAllViews()
        // First, so it is in sight to be tapped.
        if (loopChip != null) {
            val waiting = b == C.TIME_UNSET
            val description = if (waiting) "Repeating a part from ${time(a)}. Tap to end it here, hold to stop" else "Repeating ${time(a)} to ${time(b)}. Hold to stop"
            chips.addView(chip(R.drawable.ic_repeat, loopChip, description, { if (waiting) endLoopHere() else morePanel() }) {
                LocalPlayback.clearLoop()
                say("Stopped repeating the part")
            })
        }
        if (abs(speed - 1f) > 0.01f) {
            chips.addView(chip(R.drawable.ic_speed, PlayerSettings.speedLabel(speed), "Speed ${PlayerSettings.speedLabel(speed)}. Hold for normal speed", { speedPanel() }) {
                player?.setPlaybackSpeed(1f)
                say("Normal speed")
                updateChips()
            })
        }
        if (sleep != null) {
            chips.addView(chip(R.drawable.ic_timer, "Sleep: $sleep", "Sleep timer: $sleep. Hold to turn it off", { sleepPanel() }) {
                LocalPlayback.cancelSleep()
                say("Sleep timer off")
                updateChips()
            })
        }
        if (subChip != null) {
            chips.addView(chip(R.drawable.ic_subtitles, subChip, "$subChip. Hold to put the subtitles back in time", { tracksPanel() }) {
                LocalPlayback.setSubtitleDelay(0)
                refreshSubtitles()
                say("Subtitles in time")
                updateChips()
            })
        }
        if (soundChip != null) {
            chips.addView(chip(R.drawable.ic_volume, soundChip, "$soundChip. Hold to put the sound back in time", { tracksPanel() }) {
                LocalPlayback.setSoundDelay(0)
                say("Sound in time")
                updateChips()
            })
        }
        if (night) chips.addView(chip(R.drawable.ic_night_sound, "Night sound", "Night sound on. Hold to turn it off", { morePanel() }) { setNightSound(false) })
        chips.visibility = if (chips.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun chip(icon: Int, text: String, description: String, onTap: () -> Unit, onReset: () -> Unit): TextView = chipPill(text).apply {
        val fg = chipInk()
        // 3.0.5: A-B, the sleep timer and the delays count while watched.
        Ui.steadyFigures(this)
        isSingleLine = true
        // 3.0.4: cut short with "…" only if many chips together are wider than the bar.
        ellipsize = TextUtils.TruncateAt.END
        val mark = Ui.icon(this@PlayerActivity, icon, fg)?.mutate()?.apply { setBounds(0, 0, dp(14), dp(14)) }
        setCompoundDrawablesRelative(mark, null, null, null)
        compoundDrawablePadding = dp(4)
        setPadding(dp(8), 0, dp(12), 0)
        contentDescription = description
        setOnClickListener { onTap() }
        setOnLongClickListener {
            onReset()
            true
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        // Only a chip that wasn't there fades in: the row is made again whenever one changes.
        chipKinds += icon
        if (icon !in chipKindsBefore) {
            alpha = 0f
            Motion.anim(this, Motion.SHORT, Motion.enter).alpha(1f).start()
        }
    }

    /** On the music screen a tone that goes with the cover (chips used to ignore its colours). */
    private fun chipInk(): Int = if (videoMode) Color.WHITE else coverScheme?.onTrack ?: Palette.color(this, R.color.text_primary)

    private fun chipPill(text: String): TextView {
        val fill = if (videoMode) OverPicture.TINT_CHIP else coverScheme?.track ?: Palette.color(this, R.color.surface_alt)
        return OverPicture.pill(this, text, Ui.Type.CAPTION, chipInk(), fill, CHIP_DP, Ui.R_XSMALL, OverPicture.rippleOf(chipInk()))
    }

    /** Video (black, controls over the picture) or music (Swift's colours, the cover, controls that stay). */
    private fun applyMode(video: Boolean) {
        // 3.0.3: a panel is dark over a video and in Swift's colours on the music screen: made again then.
        if (video != videoMode) {
            panel.close(animate = false)
            if (!video) hideUpNext()
        }
        // 3.0.4: frame by frame is for videos only.
        if (!video) closeFrameBar()
        videoMode = video
        if (video) {
            root.setBackgroundColor(Color.BLACK)
            setBarColours(Color.BLACK)
        }
        frame.visibility = if (video) View.VISIBLE else View.GONE
        music.visibility = if (video) View.GONE else View.VISIBLE
        // 3.0.2: deeper shades (as VLC's), so the controls read over a bright picture; the rest of
        // the picture is left alone (no dimming, no blur).
        topBar.background = if (video) OverPicture.shadeTop() else null
        controls.background = if (video) OverPicture.shadeBottom() else null
        // Music: everything 28 dp from the sides, as its title is, and clear of the phone's edge.
        val sides = dp(if (video) 8 else 18)
        controls.setPadding(sides, if (video) dp(44) else dp(8), sides, if (video) dp(10) else dp(24))
        // Over a video a light ring; on the music screen the accent's round button (3.0.4: or the cover's colour).
        playButton.background = if (video) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x40000000)
                setStroke(dp(2), Color.WHITE)
            }
        } else {
            Ui.circle(Ui.accent(this))
        }
        if (video) {
            playIcon.colour = Color.WHITE
            paintText(Color.WHITE, 0xCCFFFFFF.toInt())
            // 3.0.4: the seek bar as the video's controls are: the handle white, the rest see-through
            // white, the part played in the accent as the dark look has it (light enough on the shade).
            seek.style = SwiftSlider.Style.VIDEO
            seek.setColours(Palette.colorFor(this, true, R.color.accent), 0x40FFFFFF, Color.WHITE, 0x66FFFFFF)
            // White on a dark circle, so it reads on any picture.
            spinner.colour = Color.WHITE
            spinner.container = OverPicture.FILL_SPINNER
        } else {
            // 3.0.4: Swift's colours, or the song's cover's, at once (another song's fade in, see showScheme);
            // the part played a wave.
            seek.style = SwiftSlider.Style.WAVE
            spinner.container = 0
            coverFade.cancel()
            musicColours(coverScheme, musicTo)
            musicTo.copyInto(musicShown)
            paintMusicText()
            paintMusic()
        }
        arrangeButtons()
        chipsShown = ""
        if (!video) {
            if (locked) setLocked(false)
            showControls(true)
            showCues(emptyList())
            layoutMusic()
        }
        updateModes()
        updatePlaying(animate = false)
        applyRotation()
        applyBars()
    }

    /** The screen's text and icons in [fg], the quieter lines in [muted] (3.0.4: and the A-B ticks in [fg]). */
    private fun paintText(fg: Int, muted: Int) {
        val tint = ColorStateList.valueOf(fg)
        val ripple = ColorStateList.valueOf(OverPicture.rippleOf(fg))
        for (b in icons) {
            b.imageTintList = tint
            (b.background as? RippleDrawable)?.setColor(ripple)
        }
        (length.background as? RippleDrawable)?.setColor(ripple)
        titleView.setTextColor(fg)
        countView.setTextColor(muted)
        elapsed.setTextColor(fg)
        length.setTextColor(fg)
        songTitle.setTextColor(fg)
        songArtist.setTextColor(muted)
        loopMarks.colour = fg
    }

    /** 3.0.4: the music screen's text: Swift's colours, or as the cover's colours need them (readable on their background). */
    private fun paintMusicText() {
        paintText(coverScheme?.text ?: Palette.color(this, R.color.text_primary), coverScheme?.secondary ?: Palette.color(this, R.color.text_secondary))
    }

    /** 3.0.4: the music screen's colours for [scheme] (null: Swift's own), into [out]. */
    private fun musicColours(scheme: CoverColours.Scheme?, out: IntArray) {
        out[MUSIC_BG] = scheme?.background ?: Palette.color(this, R.color.page_bg)
        out[MUSIC_ACCENT] = scheme?.accent ?: Ui.accent(this)
        out[MUSIC_ON_ACCENT] = scheme?.onAccent ?: Ui.onAccent(this)
        out[MUSIC_TRACK] = scheme?.track ?: Ui.accentContainer(this)
    }

    /**
     * 3.0.4: the music screen in [musicShown]: its background (and the phone's bars), the play button,
     * the seek bar and the loading indicator. Every frame of a change of song's colours: nothing is made.
     */
    private fun paintMusic() {
        if (videoMode) return
        val bg = musicShown[MUSIC_BG]
        root.setBackgroundColor(bg)
        setBarColours(bg)
        (playButton.background as? GradientDrawable)?.setColor(musicShown[MUSIC_ACCENT])
        playIcon.colour = musicShown[MUSIC_ON_ACCENT]
        seek.setColours(musicShown[MUSIC_ACCENT], musicShown[MUSIC_TRACK])
        spinner.colour = musicShown[MUSIC_ACCENT]
    }

    /**
     * 3.0.4: the music screen in the song's cover's colours (CoverColours): worked out off the main
     * thread the first time and kept for the song, then shown, fading from the last song's. A cover
     * still on its way keeps the last colours a moment, so a change of album fades once, not twice.
     */
    private fun updateCoverColours() {
        val e = LocalPlayback.current ?: return
        val art = LocalPlayback.art
        if (art == null) {
            if (coverFor != null) {
                coverFor = null
                handler.removeCallbacks(coverGone)
                if (coverScheme != null) handler.postDelayed(coverGone, 400)
            }
            return
        }
        handler.removeCallbacks(coverGone)
        if (art === coverFor) return
        coverFor = art
        val night = Palette.isNight(this)
        val key = (if (night) "dark " else "light ") + e.uri
        val kept = CoverColours.kept(key)
        if (kept != null) {
            showScheme(kept.takeIf { it !== CoverColours.NONE })
            return
        }
        val text = Palette.color(this, R.color.text_primary)
        val secondary = Palette.color(this, R.color.text_secondary)
        LocalPlayback.io.execute {
            val scheme = try {
                CoverColours.of(art, night, text, secondary)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            CoverColours.remember(key, scheme)
            handler.post { if (!isDestroyed && coverFor === art) showScheme(scheme) }
        }
    }

    /** No cover came for the song: Swift's own colours again. */
    private val coverGone = Runnable { if (LocalPlayback.art == null) showScheme(null) }

    /** 3.0.4: the music screen changes to [scheme]'s colours (null: Swift's own), fading while it is in sight. */
    private fun showScheme(scheme: CoverColours.Scheme?) {
        coverScheme = scheme
        if (videoMode) return
        // The text at once: its colour only moves as far as it must to stay readable.
        paintMusicText()
        musicColours(scheme, musicTo)
        updateModes()
        // 3.0.5: the chips under the title take the new colours too.
        chipsShown = ""
        updateChips()
        coverFade.cancel()
        if (!Motion.enabled(this) || !LocalPlayback.screenVisible || !music.isShown || musicTo.contentEquals(musicShown)) {
            musicTo.copyInto(musicShown)
            paintMusic()
            return
        }
        musicShown.copyInto(musicFrom)
        coverFade.start()
    }

    /**
     * 3.0.4: the music screen's wave flows while the song plays and the bar is in sight: not while a
     * finger seeks (the bar's own drag flattens it too) or a panel covers it.
     */
    private fun updateWave() {
        seek.waving = !videoMode && LocalPlayback.playing && !scrubbing && !(panel.isOpen && !panel.side)
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
        val bottom = dp(164)
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
        // 3.0.3: a panel goes to the side or the bottom as the screen now is.
        panel.relayout()
        handler.post {
            moveSubtitles()
            placeUpNext()
        }
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
            // Another song's words cross-fade, as its colours do (the same words do nothing).
            Motion.swapText(songTitle, LocalPlayback.displayTitle())
            val artist = LocalPlayback.tagArtist.orEmpty()
            songArtist.visibility = if (artist.isBlank()) View.GONE else View.VISIBLE
            Motion.swapText(songArtist, artist)
            showCover()
        }
        countView.text = position.orEmpty()
        countView.visibility = if (countView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        playlistButton.visibility = if (n > 1) View.VISIBLE else View.GONE
        arrangeButtons()
        updateChips()
    }

    /**
     * A song's picture is read after the song starts: until it comes the last one stays a moment (as
     * the colours do), so a change of song fades once, not to the empty picture and back.
     */
    private fun showCover() {
        val art = LocalPlayback.art
        if (art != null) {
            handler.removeCallbacks(coverNone)
            coverWaiting = false
            drawCover(art)
        } else if (coverDrawn is android.graphics.Bitmap) {
            if (!coverWaiting) {
                coverWaiting = true
                handler.postDelayed(coverNone, COVER_WAIT_MS)
            }
        } else {
            drawCover(null)
        }
        updateCoverColours()
    }

    /** No picture came for the song: the one for no cover. */
    private val coverNone = Runnable {
        coverWaiting = false
        if (LocalPlayback.art == null) drawCover(null)
    }

    /** The cover shows [art] (null: none); nothing happens unless that changes. A real change fades in. */
    private fun drawCover(art: android.graphics.Bitmap?) {
        val key: Any = art ?: Ui.accentContainer(this)
        if (key == coverDrawn) return
        val first = coverDrawn == null
        coverDrawn = key
        if (art != null) {
            cover.background = Ui.shape(this, Palette.color(this, R.color.surface_alt), Ui.R_LARGE)
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setPadding(0, 0, 0, 0)
            cover.imageTintList = null
            cover.setImageBitmap(art)
        } else {
            // 3.0.5: no picture of its own: a calm one in the accent's soft colours, a second tone on the
            // first and a small note (a single note used to fill the whole picture).
            val soft = Ui.accentContainer(this)
            val ink = Ui.onAccentContainer(this)
            cover.background = Ui.shape(this, soft, Ui.R_LARGE)
            cover.scaleType = ImageView.ScaleType.FIT_XY
            cover.setPadding(0, 0, 0, 0)
            cover.imageTintList = null
            cover.setImageDrawable(NoCoverArt(Tones.mix(soft, ink, 0.08), Ui.icon(this, R.drawable.ic_music_note, Tones.mix(ink, soft, 0.3))!!))
        }
        if (!first && LocalPlayback.screenVisible && cover.isShown) {
            cover.animate().cancel()
            cover.alpha = 0f
            Motion.anim(cover, Motion.NORMAL).alpha(1f).withLayer().start()
        }
    }

    /**
     * 3.0.4: [animate]: the play button morphs to its new icon, if it is in sight (not as the screen
     * comes back, or for a change made while it wasn't seen, such as the notification's or headphones').
     */
    private fun updatePlaying(animate: Boolean = true) {
        val playing = LocalPlayback.playing
        playIcon.show(playing, animate && LocalPlayback.screenVisible && playButton.isShown)
        playButton.contentDescription = if (playing) "Pause" else "Play"
        updateWave()
        // The screen stays on while a video plays (music lets it turn off).
        if (videoMode && playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (playing) {
            // 3.0.4: playing closes frame by frame.
            if (frameMode) closeFrameBar()
            scheduleHide()
            // 3.0.3: playing on from the end goes to the next one by itself.
            if (upNext != null) hideUpNext()
        } else {
            handler.removeCallbacks(autoHide)
            if (videoMode && !locked && player?.playbackState == Player.STATE_ENDED) showControls(true)
        }
    }

    private fun updateModes() {
        val p = player ?: return
        // 3.0.4: on the music screen in its colours (the cover's, when shown).
        val fg = if (videoMode) Color.WHITE else coverScheme?.text ?: Palette.color(this, R.color.text_primary)
        val accent = if (videoMode || musicTo[MUSIC_ACCENT] == 0) Ui.accent(this) else musicTo[MUSIC_ACCENT]
        val shuffle = p.shuffleModeEnabled
        // 3.0.4: on, shuffle and repeat are also Google's filled ones.
        Motion.swapIcon(shuffleButton, OnIcons.of(R.drawable.ic_shuffle, shuffle), if (shuffle) 1f else 0.6f)
        shuffleButton.imageTintList = ColorStateList.valueOf(if (shuffle) accent else fg)
        shuffleButton.contentDescription = if (shuffle) "Shuffle: on" else "Shuffle: off"
        val repeat = p.repeatMode
        Motion.swapIcon(
            repeatButton,
            OnIcons.of(if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat, repeat != Player.REPEAT_MODE_OFF),
            if (repeat != Player.REPEAT_MODE_OFF) 1f else 0.6f,
        )
        repeatButton.imageTintList = ColorStateList.valueOf(if (repeat != Player.REPEAT_MODE_OFF) accent else fg)
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
            setIfChanged(elapsed, time(pos))
        }
        seek.isEnabled = d > 0 && p.isCurrentMediaItemSeekable
        setIfChanged(length, when {
            p.isCurrentMediaItemLive -> "Live"
            d <= 0 -> "–:––"
            showRemaining -> "−" + time(d - pos)
            else -> time(d)
        })
        updateLoopMarks()
    }

    /** 3.0.4: A and B on the seek bar, while a part is repeated (or its start waits for an end). */
    private fun updateLoopMarks() {
        val d = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val a = LocalPlayback.loopStartMs
        val b = LocalPlayback.loopEndMs
        loopMarks.set(if (d > 0 && a != C.TIME_UNSET) a.toFloat() / d else Float.NaN, if (d > 0 && b != C.TIME_UNSET) b.toFloat() / d else Float.NaN)
    }

    /** 3.0.4: the start of a part is set and its end not yet (the controls stay for its chip). */
    private val waitingForLoopEnd: Boolean get() = LocalPlayback.loopStartMs != C.TIME_UNSET && LocalPlayback.loopEndMs == C.TIME_UNSET

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

    /** A message above the controls when they are shown (3.0.3: or above an upright panel). */
    private fun say(text: String, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null) {
        val offset = when {
            panel.isOpen && !panel.side -> panel.size
            controlsShown -> controls.height
            else -> 0
        }
        Messages.show(this, text, action = action, long = long, bottomOffset = offset, onAction = onAction)
    }

    // ------------------------------------------------------------------ showing and hiding the controls

    // 3.0.4: not while the end of a part to repeat is waited for (its chip is tapped there), nor
    // while frame by frame's bar is open.
    private val autoHide = Runnable {
        if (videoMode && controlsShown && LocalPlayback.playing && !scrubbing && problem == null && !waitingForLoopEnd && !frameMode) showControls(false)
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
        // 3.0.3: hidden while a panel is open (they come back when it closes).
        if (show && videoMode && panel.isOpen) return
        controlsShown = show
        OverPicture.bars(topBar, controls, show)
        moveSubtitles()
        placeUpNext()
        if (show) {
            updateProgress()
            scheduleHide()
        } else {
            handler.removeCallbacks(autoHide)
        }
    }

    private fun setLocked(on: Boolean) {
        locked = on
        // 3.0.4: frame by frame's bar hides with the controls; Back says to unlock first meanwhile.
        syncFrameBack()
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
        Motion.anim(unlockButton, Motion.SHORT, Motion.exit).alpha(0f).withEndAction {
            unlockButton.visibility = View.GONE
            unlockButton.alpha = 1f
        }.start()
    }

    private fun showUnlock() {
        val coming = unlockButton.visibility != View.VISIBLE
        unlockButton.animate().cancel()
        unlockButton.visibility = View.VISIBLE
        if (coming) {
            unlockButton.alpha = 0f
            Motion.anim(unlockButton, OverPicture.QUICK_MS, Motion.enter).alpha(1f).start()
        } else {
            unlockButton.alpha = 1f
        }
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 3000)
    }

    // ------------------------------------------------------------------ the HUD (gestures' feedback)

    private fun showHud(icon: Int, text: String, sub: String?, level: Float?, side: Int = 0) {
        handler.removeCallbacks(hideHud)
        placeHud(side)
        // Runs on every finger move: an icon and words are set only when they change. Fades in on its first show only.
        if (hud.visibility != View.VISIBLE) {
            hudLeaving = false
            hud.animate().cancel()
            hud.alpha = 0f
            hud.visibility = View.VISIBLE
            Motion.anim(hud, OverPicture.QUICK_MS, Motion.enter).alpha(1f).start()
        } else if (hudLeaving) {
            hudLeaving = false
            hud.animate().cancel()
            hud.alpha = 1f
        }
        if (icon != hudIconShown) {
            hudIconShown = icon
            hudIcon.setImageDrawable(Ui.icon(this, icon, Color.WHITE))
        }
        setIfChanged(hudText, text)
        setIfChanged(hudSub, sub.orEmpty())
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
        hudLeaving = true
        Motion.anim(hud, Motion.SHORT, Motion.exit).alpha(0f).withEndAction {
            hudLeaving = false
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
        // Tapping on at the same side adds up (−10 s, −20 s…), as the HUD shows. 3.0.3: each further
        // tap counts (PlayerGestures), within 1.2 s of the last, as on websites' videos.
        tapTotal = PlayerRules.runTotal(tapTotal, tapSide, tapAt, side, now, step)
        tapSide = side
        tapAt = now
        if (tapTotal == step) {
            // 3.0.3: a run of skips starts: felt, and (once) how to skip further.
            Haptics.tick(gestures)
            if (!PlayerSettings.tapsHintShown) {
                PlayerSettings.tapsHintShown = true
                say("Tap again to skip more")
            }
        }
        LocalPlayback.seekBy(side * step)
        showHud(if (side < 0) R.drawable.ic_fast_rewind else R.drawable.ic_fast_forward, PlayerRules.runLabel(side, tapTotal), null, null, side)
        hideHudSoon()
        // The icon nudges toward the skip, so a tap that only changes the count is still seen.
        hudIcon.animate().cancel()
        Motion.anim(hudIcon, OverPicture.NUDGE_OUT_MS).translationX(Ui.dpf(this, OverPicture.NUDGE_DP) * side).withEndAction {
            Motion.anim(hudIcon, OverPicture.NUDGE_BACK_MS).translationX(0f).start()
        }.start()
    }

    /** 3.0.3: pinching out fills the screen (the black bars are cut off), pinching in fits the whole picture. */
    override fun onPinch(fill: Boolean) {
        if (!videoMode || locked || player == null) return
        val want = if (fill) VideoFrame.Fit.ZOOM else VideoFrame.Fit.FIT
        if (frame.fit == want) return
        frame.fit = want
        Haptics.tick(gestures)
        showHud(R.drawable.ic_aspect_ratio, want.label, null, null)
        hideHudSoon(900)
    }

    /** 3.0.3: [x], [y] (the screen's) on the subtitles' text, give or take a finger's width. */
    override fun onSubtitles(x: Float, y: Float): Boolean {
        if (!videoMode || subtitleText.visibility != View.VISIBLE || subtitleText.text.isNullOrEmpty()) return false
        val r = Rect()
        subtitleText.getHitRect(r)
        r.inset(-dp(16), -dp(16))
        return r.contains(x.toInt(), y.toInt())
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
            PlayerGestures.SUBTITLES -> {
                liftFrom = subLift
                liftDrag = subLift
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
                    setIfChanged(elapsed, time(seekTarget))
                }
                previewAt(seekTarget)
            }
            // 3.0.3: the subtitles follow the finger up or down (for every video, until reset).
            PlayerGestures.SUBTITLES -> {
                liftDrag = PlayerRules.lift(liftFrom + amount)
                moveSubtitles(animate = false)
            }
        }
    }

    override fun onSwipeEnd(kind: Int) {
        when (kind) {
            PlayerGestures.SEEK -> {
                val moved = seekTarget != seekFrom
                scrubEnd(if (moved) seekTarget else null)
                hideHudSoon(if (moved) 300 else 0)
            }
            PlayerGestures.SUBTITLES -> {
                if (!liftDrag.isNaN()) {
                    subLift = liftDrag
                    PlayerSettings.subtitleLift = subLift
                }
                liftDrag = Float.NaN
            }
            else -> hideHudSoon()
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
        // 3.0.4: the music screen's wave lies flat while a finger seeks.
        updateWave()
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
        updateWave()
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

    /** 3.0.4: ⋮ → Repeat a part (A-B): the part starts where the video or song is now (its chip sets the end); or it stops repeating. */
    private fun toggleLoop() {
        val p = player ?: return
        if (LocalPlayback.loopStartMs != C.TIME_UNSET) {
            LocalPlayback.clearLoop()
            say("Stopped repeating the part")
            return
        }
        LocalPlayback.setLoopStart(p.currentPosition)
    }

    /** 3.0.4: the A chip tapped: the part ends where the video or song is now, and goes round from its start. */
    private fun endLoopHere() {
        val p = player ?: return
        if (!LocalPlayback.setLoopEnd(p.currentPosition)) {
            say("The end must come after the start")
            return
        }
        Haptics.tick(chips)
        // The controls stayed for the chip; now they go again as usual.
        scheduleHide()
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
        // 3.0.4: a network folder's song isn't another app's: it closes as files do.
        if (goesOn && e?.file == null && e?.stream == false && e?.network == false) {
            moveTaskToBack(true)
            return
        }
        closing = true
        if (!goesOn) LocalPlayback.release()
        finish()
    }

    // ------------------------------------------------------------------ frame by frame (3.0.4)

    /** ⋮ → Frame by frame's bar: a frame back, the frame's exact time, a frame on, and close. */
    private fun buildFrameBar(): LinearLayout {
        val back = button(R.drawable.ic_chevron_right, "Previous frame") {}.apply { rotation = 180f }
        val on = button(R.drawable.ic_chevron_right, "Next frame") {}
        stepWhileHeld(back, -1)
        stepWhileHeld(on, 1)
        frameTime = TextView(this).apply {
            setTextColor(Color.WHITE)
            Ui.size(this, Ui.Type.BODY, keepFont = true)
            typeface = Ui.MEDIUM
            // Figures all of a width, so the time stays still as it changes.
            Ui.steadyFigures(this)
            gravity = Gravity.CENTER
            minWidth = dp(92)
        }
        val close = button(R.drawable.ic_close, "Close frame by frame") { closeFrameBar() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.shape(this@PlayerActivity, OverPicture.FILL_SOFT, FRAME_BAR_DP / 2)
            setPadding(dp(2), 0, dp(2), 0)
            visibility = View.GONE
            addView(back, LinearLayout.LayoutParams(dp(FRAME_BAR_DP), dp(FRAME_BAR_DP)))
            addView(frameTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(on, LinearLayout.LayoutParams(dp(FRAME_BAR_DP), dp(FRAME_BAR_DP)))
            addView(View(this@PlayerActivity).apply { setBackgroundColor(0x40FFFFFF) }, LinearLayout.LayoutParams(dp(1), dp(22)).apply {
                marginStart = dp(4)
                marginEnd = dp(2)
            })
            addView(close, LinearLayout.LayoutParams(dp(FRAME_BAR_DP), dp(FRAME_BAR_DP)))
        }
    }

    /**
     * [v] steps a frame each tap, and on and on while held (from 0.4 s, every 0.2 s). The touch is
     * still the button's own, so holding it shows its name too.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun stepWhileHeld(v: View, direction: Int) {
        var held = false
        val again = object : Runnable {
            override fun run() {
                held = true
                stepFrame(direction)
                v.postDelayed(this, 200)
            }
        }
        // A tap (or a screen reader's activation) is one step; the click that may end a hold isn't another.
        v.setOnClickListener { if (!held) stepFrame(direction) }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    held = false
                    view.removeCallbacks(again)
                    view.postDelayed(again, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(again)
                    if (held) view.postDelayed({ held = false }, 100)
                }
            }
            false
        }
    }

    /** Each frame as it is shown (on the playback thread): its own time, to step from it exactly. */
    private val frameWatch = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        shownFrameUs = presentationTimeUs
        if (frameMode) handler.post(frameShown)
    }

    /** A step's frame is on the screen: the next step may go. */
    private val frameShown = Runnable {
        stepPending = false
        handler.removeCallbacks(stepGaveUp)
        if (frameMode) showFrameTime()
    }

    /** A step whose frame didn't come in a second (a slow seek, a video without pictures): the next one may go. */
    private val stepGaveUp = Runnable {
        stepPending = false
        if (frameMode) showFrameTime()
    }

    /** The time (µs) of the frame shown, when it is about where the player is (else where the player is). */
    private fun shownFrame(p: ExoPlayer, frameUs: Long): Long {
        val positionUs = p.currentPosition * 1000
        val shown = shownFrameUs
        return if (shown != C.TIME_UNSET && abs(shown - positionUs) < 3 * frameUs) shown else positionUs
    }

    private fun frameDurationUs(p: ExoPlayer): Long = PlayerRules.frameUs(p.videoFormat?.frameRate ?: Format.NO_VALUE.toFloat())

    private fun showFrameTime() {
        val p = player ?: return
        val time = PlayerRules.exactTime(shownFrame(p, frameDurationUs(p)) / 1000)
        frameTime.text = time
        frameTime.contentDescription = "Frame at $time"
    }

    /** One frame back (−1) or on (1) from the one shown; nothing while the last step's frame is still coming. */
    private fun stepFrame(direction: Int) {
        val p = player ?: return
        if (!frameMode || stepPending || p.playbackState == Player.STATE_BUFFERING) return
        val frameUs = frameDurationUs(p)
        val durationUs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it * 1000 } ?: 0L
        val target = PlayerRules.frameStepUs(shownFrame(p, frameUs), frameUs, direction, durationUs) ?: return
        stepPending = true
        handler.removeCallbacks(stepGaveUp)
        handler.postDelayed(stepGaveUp, 1000)
        p.seekTo(target / 1000)
    }

    /**
     * ⋮ → Frame by frame: the video pauses and the bar shows above the controls, which stay while it
     * is open; play, Back or its close button closes it. Seeks are exact while it is open.
     */
    private fun openFrameBar() {
        val p = player ?: return
        if (!videoMode || frameMode || problem != null) return
        LocalPlayback.pause()
        seekBefore = p.seekParameters
        p.setSeekParameters(SeekParameters.EXACT)
        frameMode = true
        stepPending = false
        syncFrameBack()
        showFrameTime()
        frameBar.animate().cancel()
        frameBar.alpha = 0f
        frameBar.visibility = View.VISIBLE
        Motion.anim(frameBar, Motion.SHORT, Motion.enter).alpha(1f).start()
        showControls(true)
        // The controls are taller now: what sits above them follows once they are laid out.
        controls.post {
            moveSubtitles()
            placeUpNext()
        }
        L.d("Player: frame by frame")
    }

    private fun closeFrameBar() {
        if (!frameMode) return
        frameMode = false
        stepPending = false
        handler.removeCallbacks(stepGaveUp)
        syncFrameBack()
        player?.setSeekParameters(seekBefore ?: SeekParameters.DEFAULT)
        seekBefore = null
        // The controls get shorter once it has gone: what sits above them follows then.
        Motion.fadeOut(frameBar, toDy = 0) {
            controls.post {
                moveSubtitles()
                placeUpNext()
            }
        }
        scheduleHide()
    }

    /** Back closes the bar while it is open, unless the screen is locked (then Back says to unlock first). */
    private fun syncFrameBack() {
        frameBack.isEnabled = frameMode && !locked
    }

    // ------------------------------------------------------------------ menus

    /** A choice in a list of choices: just its words, the one chosen in the accent with ✓ at the row's end. */
    private fun choiceRow(ctx: Context, text: String, chosen: Boolean, onClick: () -> Unit): TextView =
        Sheets.row(ctx, R.drawable.ic_check, text) { onClick() }.apply {
            if (chosen) setTextColor(Ui.accent(ctx))
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, if (chosen) Ui.icon(ctx, R.drawable.ic_check, Ui.accent(ctx)) else null, null)
        }

    /** 3.0.3: a menu in the panel beside the video, its content made by [build] in the panel's colours. */
    private fun showPanel(scrolls: Boolean = true, build: (Context) -> View) {
        if (isFinishing) return
        panel.show(build(panel.context()), scrolls)
    }

    /** A choice made in the panel: it closes, unless the choice put another menu in its place. */
    private fun picked(run: () -> Unit) {
        val before = panel.content
        run()
        if (panel.isOpen && panel.content === before) panel.close()
    }

    override fun panelOverVideo(): Boolean = videoMode

    /**
     * 3.0.3: a panel opened (or changed, or closed): the controls and Up next make way, Back closes it,
     * and upright, the picture moves up to be whole above it (made smaller only if it must).
     */
    override fun onPanelMoved(side: Boolean, size: Int, animate: Boolean) {
        val open = size > 0
        panelBack.isEnabled = open
        // 3.0.4: the music screen's wave rests while a panel covers it.
        updateWave()
        if (open) {
            hideUpNext()
            if (videoMode && controlsShown) showControls(false)
        }
        var shift = 0f
        var scale = 1f
        if (open && !side && videoMode) {
            val r = frame.picture
            val (move, s) = PlayerRules.fitAbove(r.top.toFloat(), r.bottom.toFloat(), (root.height - size).toFloat())
            shift = move
            scale = s
            frame.pivotX = frame.width / 2f
            frame.pivotY = r.exactCenterY()
        }
        videoShift = shift
        videoScale = scale
        frame.animate().cancel()
        if (animate) {
            frame.animate().translationY(shift).scaleX(scale).scaleY(scale).setDuration(180).setInterpolator(Motion.standard).start()
        } else {
            frame.translationY = shift
            frame.scaleX = scale
            frame.scaleY = scale
        }
        followPicture(animate)
        moveSubtitles(animate)
        if (!open && videoMode && !locked) showControls(true)
    }

    private fun morePanel() {
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
        actions += Sheets.Action(R.drawable.ic_speed, "Speed ${PlayerSettings.speedLabel(p.playbackParameters.speed)}", primary = true) { speedPanel() }
        actions += Sheets.Action(R.drawable.ic_timer, LocalPlayback.sleepLabel()?.let { "Sleep: $it" } ?: "Sleep timer", primary = true) { sleepPanel() }
        if (videoMode) {
            actions += Sheets.Action(R.drawable.ic_headphones, if (LocalPlayback.background) "Stop playing in background" else "Play in background", primary = true) { toggleBackground() }
            actions += Sheets.Action(if (p.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat, "Repeat: ${repeatLabel(p.repeatMode)}") { nextRepeat() }
        }
        // 3.0.4: repeating a part (A-B) of what can be sought in; a part set is stopped here too.
        val seekable = p.isCurrentMediaItemSeekable && !p.isCurrentMediaItemLive && p.duration != C.TIME_UNSET && p.duration > 0
        if (LocalPlayback.loopStartMs != C.TIME_UNSET) {
            actions += Sheets.Action(R.drawable.ic_repeat, "Stop repeating the part") { toggleLoop() }
        } else if (seekable) {
            actions += Sheets.Action(R.drawable.ic_repeat, "Repeat a part (A-B)") { toggleLoop() }
        }
        if (videoMode && seekable) actions += Sheets.Action(R.drawable.ic_movie, "Frame by frame") { openFrameBar() }
        // 3.0.3: only when the phone's sound can take an equalizer.
        if (SoundEffects.info() != null) actions += Sheets.Action(R.drawable.ic_tune, "Equalizer", primary = !videoMode) { equalizerPanel() }
        // 3.0.4: night sound's switch comes next, then the rest.
        val after = ArrayList<Sheets.Action>()
        if (videoMode && !e.private) after += Sheets.Action(R.drawable.ic_image, "Screenshot") { screenshot() }
        if (n > 1) after += Sheets.Action(R.drawable.ic_view_list, "Playlist") { playlistPanel() }
        // 3.0.4: other apps can't read a network folder's file through Swift.
        if (!e.network) after += Sheets.Action(R.drawable.ic_reopen, "Open with another app") { openWith() }
        if (f != null) {
            after += Sheets.Action(R.drawable.ic_share, "Share") { share(f) }
            after += Sheets.Action(R.drawable.ic_folder, "Show in Files") {
                FilesActivity.open(this, f.parentFile)
                close()
            }
        }
        after += Sheets.Action(R.drawable.ic_info, "Details") { details() }
        showPanel { ctx ->
            Sheets.column(ctx).apply {
                addView(Sheets.header(ctx, e.title, sub, lines = 2))
                // 3.0.5: as in the sheets, no line under the title: space does it.
                addView(Sheets.actionList(ctx, actions) { a -> picked { a.run() } })
                // The line under the big buttons, when no row above the switch has drawn it.
                if (actions.none { !it.primary }) addView(rowsDivider(ctx))
                addView(switchRow(ctx, R.drawable.ic_night_sound, "Night sound", LocalPlayback.nightSoundOn) { on -> setNightSound(on) })
                addView(Sheets.actionList(ctx, after) { a -> picked { a.run() } })
            }
        }
    }

    /** 3.0.4: the line between a panel's big buttons and its rows, as Sheets.actionList draws it. */
    private fun rowsDivider(ctx: Context): View = Ui.divider(ctx, 24).apply {
        (layoutParams as LinearLayout.LayoutParams).apply {
            marginEnd = dp(24)
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    /**
     * 3.0.4: a panel's row as Sheets.row makes it (an icon and its name) with a switch at the end;
     * the whole row turns it on or off, and the panel stays open to show it.
     */
    private fun switchRow(ctx: Context, icon: Int, text: String, on: Boolean, change: (Boolean) -> Unit): View {
        val sw = SlimSwitch(ctx).apply { setChecked(on, animate = false) }
        val label = Ui.text(ctx, Ui.Type.BODY, text).apply {
            minHeight = dp(Ui.ROW_ONE_LINE)
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(24), dp(10), dp(8), dp(10))
            compoundDrawablePadding = dp(20)
            setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(ctx, icon, Palette.color(ctx, R.color.text_secondary)), null, null, null)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.ripple(ctx, null, 0)
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw, LinearLayout.LayoutParams(dp(46), dp(32)).apply { marginEnd = dp(16) })
            accessibilityDelegate = SwitchRowAccessibility(sw)
            contentDescription = text
            setOnClickListener {
                val v = !sw.isChecked
                sw.setChecked(v, animate = true)
                Haptics.tick(sw)
                change(v)
            }
        }
    }

    /** 3.0.4: night sound on or off (⋮, or holding its chip); the chip follows (LocalPlayback says it changed). */
    private fun setNightSound(on: Boolean) {
        LocalPlayback.setNightSound(on)
        say(if (on) "Night sound on: loud parts quieter, quiet voices louder" else "Night sound off")
    }

    /**
     * 3.0.3: ⋮ → Equalizer: on or off, the phone's own presets (or your own levels), a slider for
     * each band (its middle, and its level in dB) and the bass boost when the phone has one. For
     * everything the player plays; any change turns it on.
     */
    private fun equalizerPanel() {
        val info = SoundEffects.info() ?: return
        showPanel { ctx ->
            val col = Sheets.column(ctx)
            col.addView(Sheets.header(ctx, "Equalizer", "For everything Swift's player plays"))
            val sw = SlimSwitch(ctx).apply { setChecked(PlayerSettings.equalizerOn, animate = false) }
            val controls = ArrayList<View>()
            var dimmed: Boolean? = null
            fun showOn(on: Boolean) {
                if (sw.isChecked != on) sw.setChecked(on, animate = true)
                // Dragging a band turns it on over and over: only a real change fades.
                if (dimmed == !on) return
                val first = dimmed == null
                dimmed = !on
                for (v in controls) if (first) v.alpha = if (on) 1f else 0.5f else Motion.anim(v, Motion.SHORT).alpha(if (on) 1f else 0.5f).start()
            }
            col.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(Ui.ROW_ONE_LINE)
                setPadding(dp(24), dp(4), dp(16), dp(4))
                background = Ui.ripple(ctx, null, 0)
                addView(Ui.text(ctx, Ui.Type.BODY, "On"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(sw, LinearLayout.LayoutParams(dp(46), dp(32)))
                accessibilityDelegate = SwitchRowAccessibility(sw)
                contentDescription = "Equalizer"
                setOnClickListener {
                    val on = !sw.isChecked
                    SoundEffects.setOn(on)
                    showOn(on)
                }
            })
            // The phone's presets, then your own levels.
            val own = info.presets.size
            val preset = PlayerSettings.equalizerPreset.takeIf { it in info.presets.indices } ?: own
            val bars = ArrayList<SeekBar>()
            val values = ArrayList<TextView>()
            fun showLevels() {
                val levels = SoundEffects.levels()
                for (b in bars.indices) {
                    val l = levels.getOrElse(b) { 0 }
                    bars[b].progress = (l - info.minLevel) / 100
                    values[b].text = EqualizerRules.level(l)
                }
            }
            var presetGroup: ChipGroup? = null
            if (info.presets.isNotEmpty()) {
                col.addView(caption(ctx, "Presets"))
                val row = Sheets.chipRow(ctx, info.presets + "Your own", preset) { i ->
                    SoundEffects.usePreset(if (i < own) i else -1)
                    showLevels()
                    showOn(true)
                }
                presetGroup = (row as? ViewGroup)?.getChildAt(0) as? ChipGroup
                controls += row
                col.addView(row)
            }
            col.addView(caption(ctx, "Bands"))
            // 3.0.4: Swift's sliders, the part set in the accent, the rest in its soft colour.
            val accent = Ui.accent(ctx)
            val soft = Ui.accentContainer(ctx)
            for (b in 0 until info.bands) {
                val value = Ui.text(ctx, Ui.Type.CAPTION, "").apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    Ui.steadyFigures(this)
                }
                val bar = SwiftSlider(ctx, SwiftSlider.Style.PANEL).apply {
                    max = ((info.maxLevel - info.minLevel) / 100).coerceAtLeast(1)
                    setColours(accent, soft)
                    contentDescription = EqualizerRules.frequency(info.centres[b])
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            val level = info.minLevel + progress * 100
                            value.text = EqualizerRules.level(level)
                            SoundEffects.setBand(b, level, save = false)
                            presetGroup?.let { g -> g.getChildAt(own)?.let { g.check(it.id) } }
                            showOn(true)
                        }

                        override fun onStartTrackingTouch(bar: SeekBar) {}

                        override fun onStopTrackingTouch(bar: SeekBar) = SoundEffects.setBand(b, info.minLevel + bar.progress * 100, save = true)
                    })
                }
                bars += bar
                values += value
                val line = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), 0, dp(24), 0)
                    minimumHeight = dp(44)
                    addView(Ui.text(ctx, Ui.Type.LABEL, EqualizerRules.frequency(info.centres[b])), LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(value, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                controls += line
                col.addView(line)
            }
            showLevels()
            if (info.bass) {
                col.addView(caption(ctx, "Bass boost"))
                val value = Ui.text(ctx, Ui.Type.CAPTION, EqualizerRules.bassLabel(PlayerSettings.bassBoost)).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    Ui.steadyFigures(this)
                }
                val bar = SwiftSlider(ctx, SwiftSlider.Style.PANEL).apply {
                    max = 20
                    progress = PlayerSettings.bassBoost / 50
                    setColours(accent, soft)
                    contentDescription = "Bass boost"
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            value.text = EqualizerRules.bassLabel(progress * 50)
                            SoundEffects.setBass(progress * 50, save = false)
                            if (progress > 0) showOn(true)
                        }

                        override fun onStartTrackingTouch(bar: SeekBar) {}

                        override fun onStopTrackingTouch(bar: SeekBar) = SoundEffects.setBass(bar.progress * 50, save = true)
                    })
                }
                val line = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), 0, dp(24), 0)
                    minimumHeight = dp(44)
                    addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(value, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                controls += line
                col.addView(line)
            }
            showOn(PlayerSettings.equalizerOn)
            col
        }
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

    private fun speedPanel() {
        val p = player ?: return
        val speeds = PlayerSettings.SPEEDS
        val now = p.playbackParameters.speed
        val selected = speeds.indices.minByOrNull { abs(speeds[it] - now) } ?: 3
        showPanel { ctx ->
            Sheets.column(ctx).apply {
                addView(Sheets.header(ctx, "Playback speed", "Voices keep their pitch"))
                addView(Sheets.chips(ctx, speeds.map { PlayerSettings.speedLabel(it) }, selected) { i -> player?.setPlaybackSpeed(speeds[i]) })
                if (gesturesEnabled) addView(Sheets.note(ctx, "Holding a finger on the video plays it at 2× until you let go."))
            }
        }
    }

    private fun sleepPanel() {
        val kind = if (videoMode) "video" else "song"
        val left = when {
            LocalPlayback.sleepAtEnd -> "Playing stops at the end of this $kind"
            LocalPlayback.sleepEndsAt > 0 -> "Playing stops in ${LocalPlayback.sleepLabel()?.removeSuffix(" left")}"
            else -> "Playing stops after a while; the sound fades out"
        }
        showPanel { ctx ->
            Sheets.column(ctx).apply {
                addView(Sheets.header(ctx, "Sleep timer", left))
                for (m in LocalPlayback.SLEEP_CHOICES) {
                    addView(choiceRow(ctx, "In $m minutes", false) {
                        panel.close()
                        LocalPlayback.setSleep(m)
                        say("Playing stops in $m minutes")
                    })
                }
                addView(choiceRow(ctx, "At the end of this $kind", false) {
                    panel.close()
                    LocalPlayback.setSleep(LocalPlayback.SLEEP_END)
                    say("Playing stops at the end of this $kind")
                })
                if (LocalPlayback.sleepLabel() != null) {
                    addView(Sheets.row(ctx, R.drawable.ic_close, "Turn the timer off") {
                        panel.close()
                        LocalPlayback.cancelSleep()
                        say("Sleep timer off")
                    })
                }
            }
        }
    }

    private fun rotationPanel() {
        showPanel { ctx ->
            Sheets.column(ctx).apply {
                addView(Sheets.header(ctx, "Turn the screen", "For every video, until you change it"))
                for (key in PlayerSettings.ROTATIONS) {
                    addView(choiceRow(ctx, PlayerSettings.rotationLabel(key), key == PlayerSettings.rotation) {
                        panel.close()
                        PlayerSettings.rotation = key
                        applyRotation()
                    })
                }
            }
        }
    }

    private fun playlistPanel() {
        val entries = LocalPlayback.entries
        if (entries.size < 2) return
        val now = LocalPlayback.index
        // The list scrolls itself (in the panel's room), showing the one playing.
        showPanel(scrolls = false) { ctx ->
            val accent = Ui.accent(ctx)
            val text = Palette.color(ctx, R.color.text_primary)
            val adapter = RowAdapter<Int>(
                bind = { h, i ->
                    val e = entries[i]
                    h.set(if (i == now) R.drawable.ic_play else if (e.video) R.drawable.ic_movie else R.drawable.ic_music_note, e.title, if (i == now) "Playing now" else null)
                    h.title.setTextColor(if (i == now) accent else text)
                    // Where the panel's header starts.
                    h.itemView.setPaddingRelative(dp(24), h.itemView.paddingTop, h.itemView.paddingEnd, h.itemView.paddingBottom)
                },
                onClick = { i ->
                    panel.close()
                    if (i != LocalPlayback.index) LocalPlayback.jumpTo(i)
                },
            )
            adapter.items = entries.indices.toList()
            val list = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                this.adapter = adapter
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val height = minOf(entries.size * dp(64), (panel.maxHeight() - dp(96)).coerceAtLeast(dp(128)))
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(now, dp(64))
            Sheets.column(ctx).apply {
                addView(Sheets.header(ctx, if (videoMode) "Videos in this folder" else "Songs in this folder", "${entries.size}, by name"))
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
            }
        }
    }

    // ------------------------------------------------------------------ subtitles

    /**
     * A new video: its subtitle file next to it if there is one, otherwise its own default ones.
     * 3.0.3: or the subtitles chosen for it last time (off, a file still there, or one of its own,
     * chosen again once its tracks are known). Their timing is LocalPlayback's, kept with the file.
     */
    private fun loadSubtitles(e: LocalPlayback.Entry) {
        subToken++
        subFile = null
        subFiles = emptyList()
        subAdded.clear()
        subReaders.clear()
        subSources.clear()
        subOff = false
        pendingSubtitles = null
        handler.removeCallbacks(subTicker)
        showCues(emptyList())
        setPlayerText(true, null)
        val f = e.file ?: return
        if (!e.video) return
        val kept = LocalPlayback.trackChoice?.takeIf { it.hasSubtitles }
        if (kept?.subtitlesOff == true) {
            subtitlesOff()
        } else if (kept?.hasOwnSubtitles == true) {
            pendingSubtitles = kept
            player?.currentTracks?.takeIf { !it.isEmpty }?.let { chooseKeptSubtitles(it) }
        }
        val keptFile = kept?.subtitleFile
        val token = subToken
        val lang = PlayerSettings.subtitleLanguage
        LocalPlayback.io.execute {
            val beside = Subtitles.besideVideo(f, lang)
            // 2.9.2: also a torrent's Subs folder; from there only an English file shows by itself
            // (3.0.1: or one in the language chosen in Settings).
            val found = beside + Subtitles.inSubsFolder(f, lang)
            // 3.0.3: the file chosen last time, if it's still there. With a choice kept nothing else
            // shows by itself, unless it was a file that has gone.
            val again = keptFile?.let { src -> readSource(src)?.let { Triple(it.first, it.second, src) } }
            val usual = kept == null || (keptFile != null && again == null)
            val firstFile = if (!usual) null else beside.firstOrNull() ?: found.firstOrNull { Subtitles.inLanguage(it, lang.ifEmpty { "en" }) }
            val first = firstFile?.let { ff -> Subtitles.read(ff)?.let { it to ff } }
            handler.post {
                if (token != subToken || isDestroyed) return@post
                subFiles = found
                if (subOff || subFile != null) return@post
                if (again != null) {
                    val (track, reader, src) = again
                    subReaders[track] = reader
                    subSources[track] = src
                    if (subFiles.none { it.name == track.label }) subAdded += track
                    useSubtitles(track)
                    L.d("Player: showing the subtitle file chosen last time")
                } else if (first != null) {
                    // A subtitle file named like the video is what its owner wants: shown rather than the video's own.
                    val (track, ff) = first
                    subReaders[track] = { enc -> Subtitles.read(ff, enc) }
                    subSources[track] = ff.path
                    useSubtitles(track)
                    L.d("Player: showing a subtitle file found next to the video")
                }
            }
        }
    }

    /** 3.0.3: a subtitle file kept as chosen (its path, or a content:// address), read again with how to read it once more; null if it's gone. Off the main thread. */
    private fun readSource(source: String): Pair<Subtitles.Track, (String) -> Subtitles.Track?>? = try {
        if (source.startsWith("content:")) {
            val uri = Uri.parse(source)
            val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "subtitles"
            val reader = { enc: String? -> contentResolver.openInputStream(uri)?.use { Subtitles.read(it, name, enc) } }
            reader(null)?.let { it to { enc: String -> reader(enc) } }
        } else {
            val file = File(source)
            if (!file.isFile) null else Subtitles.read(file)?.let { it to { enc: String -> Subtitles.read(file, enc) } }
        }
    } catch (e: Exception) {
        L.w("Player: the subtitles chosen last time can't be read", e)
        null
    }

    /** 3.0.3: the video's own subtitles chosen last time, chosen again now its tracks are known. */
    private fun chooseKeptSubtitles(tracks: Tracks) {
        val want = pendingSubtitles ?: return
        val options = ArrayList<Pair<Tracks.Group, Int>>()
        for (g in tracks.groups) if (g.type == C.TRACK_TYPE_TEXT) for (t in 0 until g.length) options += g to t
        pendingSubtitles = null
        val i = TrackChoice.match(want.subtitleLanguage, want.subtitleLabel, options.map { (g, t) -> g.getTrackFormat(t).let { it.language to it.label } })
        if (i < 0 || subOff || subFile != null) return
        val (g, t) = options[i]
        if (!g.isTrackSelected(t)) useVideoSubtitles(g, t)
    }

    /** 3.0: how each subtitle file shown can be read again in another encoding (the subtitle sheet's choice). */
    private val subReaders = HashMap<Subtitles.Track, (String) -> Subtitles.Track?>()

    /** 3.0: the encodings to read the subtitles in, when their letters come out wrong. */
    private fun encodingPanel() {
        val track = subFile ?: return
        val reader = subReaders[track] ?: return
        showPanel { ctx ->
            Sheets.column(ctx).apply {
                addView(Sheets.header(ctx, "Text encoding", "Choose another when letters show as boxes or strange signs"))
                for ((name, label) in Subtitles.ENCODINGS) {
                    addView(choiceRow(ctx, label, name.equals(track.encoding, ignoreCase = true)) {
                        panel.close()
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
                                subSources[track]?.let { subSources[again] = it }
                                val at = subAdded.indexOf(track)
                                if (at >= 0) subAdded[at] = again
                                useSubtitles(again)
                            }
                        }
                    })
                }
            }
        }
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
                    subSources[track] = f.path
                    useSubtitles(track)
                    // 3.0.3: chosen for this file next time too.
                    LocalPlayback.rememberTracks { it.withSubtitleFile(f.path) }
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
        // 3.0.3: kept as chosen for this file (a file on the phone, not incognito's or Private's), so
        // the picked file stays readable after Swift closes.
        val entry = LocalPlayback.current
        val keep = entry != null && entry.file != null && !entry.private
        if (keep) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
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
                    subSources[track] = uri.toString()
                    useSubtitles(track)
                    if (keep) LocalPlayback.rememberTracks { it.withSubtitleFile(uri.toString()) }
                    say("Showing $name")
                }
            }
        }
    }

    /**
     * 3.0.2: sound and subtitles in one place (VLC's Tracks). 3.0.3: a panel beside the video, in
     * this order: the sound tracks (when there are several), the subtitles (and "Add subtitles…"),
     * the timing of the subtitles and of the sound, then how the subtitles look. What is chosen is
     * kept for a file on the phone (not incognito's or Private's) and chosen again next time.
     */
    private fun tracksPanel() {
        val p = player ?: return
        showPanel { ctx -> tracksContent(ctx, p) }
    }

    private fun tracksContent(ctx: Context, p: ExoPlayer): View {
        val col = Sheets.column(ctx)
        val sounds = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val severalSounds = sounds.sumOf { it.length } > 1
        col.addView(Sheets.header(ctx, "Sound and subtitles", null))
        if (severalSounds) {
            col.addView(Sheets.label(ctx, "Sound"))
            var s = 0
            for (g in sounds) {
                for (t in 0 until g.length) {
                    s++
                    val f = g.getTrackFormat(t)
                    val name = trackName(f, s, "Sound") + if (!g.isTrackSupported(t, true)) " (your phone can't play it)" else ""
                    col.addView(choiceRow(ctx, name, g.isTrackSelected(t)) {
                        panel.close()
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t))
                            .build()
                        LocalPlayback.rememberTracks { it.withSound(f.language, f.label) }
                    })
                }
            }
        }
        col.addView(Sheets.label(ctx, "Subtitles", top = if (severalSounds) 16 else 12))
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val ownOn = subFile == null && !subOff && groups.any { it.isSelected }
        col.addView(choiceRow(ctx, "Off", subFile == null && !ownOn) {
            panel.close()
            subtitlesOff()
            LocalPlayback.rememberTracks { it.withSubtitlesOff() }
        })
        var n = 0
        for (g in groups) {
            for (t in 0 until g.length) {
                n++
                val f = g.getTrackFormat(t)
                col.addView(choiceRow(ctx, trackName(f, n, "Subtitles"), subFile == null && !subOff && g.isTrackSelected(t)) {
                    panel.close()
                    useVideoSubtitles(g, t)
                    LocalPlayback.rememberTracks { it.withSubtitles(f.language, f.label) }
                })
            }
        }
        for (f in subFiles) {
            col.addView(choiceRow(ctx, f.name, subFile?.label == f.name) {
                panel.close()
                readSubtitles(f)
            })
        }
        for (track in subAdded) {
            if (subFiles.any { it.name == track.label }) continue
            col.addView(choiceRow(ctx, track.label, subFile === track) {
                panel.close()
                useSubtitles(track)
                subSources[track]?.let { src -> LocalPlayback.rememberTracks { it.withSubtitleFile(src) } }
            })
        }
        col.addView(Sheets.row(ctx, R.drawable.ic_add, "Add subtitles…") {
            panel.close()
            resumeAfterPick = LocalPlayback.playing
            try {
                pickSubtitles.launch(arrayOf("*/*"))
            } catch (_: Exception) {
                resumeAfterPick = false
                say("Your phone has no app to pick files with")
            }
        })
        // 3.0.3: one timing for the video's own subtitles and subtitle files, and the sound's.
        col.addView(Sheets.label(ctx, "Timing", top = 16))
        col.addView(timingRow(ctx, "Subtitles", { LocalPlayback.subtitleDelayMs }) { ms ->
            LocalPlayback.setSubtitleDelay(ms)
            refreshSubtitles()
        })
        col.addView(timingRow(ctx, "Sound", { LocalPlayback.soundDelayMs }) { ms -> LocalPlayback.setSoundDelay(ms) })
        col.addView(Sheets.label(ctx, "Subtitle style", top = 16))
        col.addView(caption(ctx, "Size"))
        val sizes = PlayerSettings.SUBTITLE_SIZES
        val size = PlayerSettings.subtitleSize
        col.addView(Sheets.chipRow(ctx, sizes.map { "$it%" }, sizes.indices.minByOrNull { abs(sizes[it] - size) } ?: 1) { i ->
            PlayerSettings.subtitleSize = sizes[i]
            applySubtitleSize()
        })
        col.addView(caption(ctx, "Colour"))
        col.addView(Sheets.chipRow(ctx, PlayerSettings.SUBTITLE_COLOURS.map { it.first }, PlayerSettings.subtitleColour) { i ->
            PlayerSettings.subtitleColour = i
            applySubtitleSize()
        })
        col.addView(caption(ctx, "Background"))
        col.addView(Sheets.chipRow(ctx, PlayerSettings.SUBTITLE_BACKGROUNDS, PlayerSettings.subtitleBackground) { i ->
            PlayerSettings.subtitleBackground = i
            applySubtitleSize()
        })
        // 3.0: a subtitle file's letters garbled (an old file in another alphabet's encoding): read it another way.
        val shown = subFile
        if (shown != null && subReaders[shown] != null) {
            col.addView(caption(ctx, "Letters"))
            col.addView(Sheets.row(ctx, R.drawable.ic_translate, "Text encoding: ${Subtitles.encodingLabel(shown.encoding)}") { encodingPanel() })
        }
        // 3.0.3: where they sit, dragged on the video (for every video).
        if (subLift != 0f) {
            lateinit var reset: View
            reset = Sheets.row(ctx, R.drawable.ic_refresh, "Reset position") {
                subLift = 0f
                PlayerSettings.subtitleLift = 0f
                moveSubtitles()
                reset.visibility = View.GONE
            }
            col.addView(reset)
        }
        if (gesturesEnabled) col.addView(Sheets.note(ctx, "Drag the subtitles on the video to move them up or down."))
        return col
    }

    /** A small line naming the choices under it. */
    private fun caption(ctx: Context, text: String): TextView = Ui.text(ctx, Ui.Type.CAPTION, text).apply {
        setPadding(dp(24), dp(10), dp(24), dp(4))
    }

    /**
     * 3.0.3: "Subtitles", then − 0.3 s later +: a tenth of a second a tap, on and on while held (after
     * 0.4 s, every 0.12 s), up to 10 s either way; tapping the value puts it back in time.
     */
    private fun timingRow(ctx: Context, name: String, value: () -> Long, set: (Long) -> Unit): View {
        val shown = Ui.text(ctx, Ui.Type.LABEL, "").apply {
            gravity = Gravity.CENTER
            minHeight = dp(TIMING_DP)
            background = Ui.ripple(ctx, null, TIMING_DP / 2)
            Ui.steadyFigures(this)
        }
        // [fade]: a tap on the value, never the steps of a held button (a late fade would put an old value back).
        fun refresh(fade: Boolean = false) {
            val label = Delays.label(value())
            if (fade) {
                Motion.swapText(shown, label)
            } else {
                shown.animate().cancel()
                shown.setTag(R.id.motion_text, null)
                shown.alpha = 1f
                shown.text = label
            }
            shown.contentDescription = "$name: $label. Tap to put it back in time"
        }
        refresh()
        shown.setOnClickListener {
            set(0)
            refresh(fade = true)
        }
        fun pill(icon: Int, direction: Int, description: String) = ImageView(ctx).apply {
            setImageDrawable(Ui.icon(ctx, icon, Palette.color(ctx, R.color.text_primary)))
            // A 20 dp glyph in the 52 by 40 pill.
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = Ui.ripple(ctx, Ui.shape(ctx, Palette.color(ctx, R.color.surface_alt), TIMING_DP / 2), TIMING_DP / 2)
            contentDescription = description
            Ui.pressable(this, Motion.PRESS_BUTTON, morph = false)
            holdToRepeat(this) {
                set(Delays.step(value(), direction))
                refresh()
            }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), dp(4))
            addView(pill(R.drawable.ic_remove, -1, "$name earlier"), LinearLayout.LayoutParams(dp(52), dp(TIMING_DP)))
            addView(shown, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            addView(pill(R.drawable.ic_add, 1, "$name later"), LinearLayout.LayoutParams(dp(52), dp(TIMING_DP)))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(caption(ctx, name))
            addView(row)
        }
    }

    /** 3.0.3: [v] steps once a tap, and on and on while held (after 0.4 s, every 0.12 s). */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdToRepeat(v: View, step: () -> Unit) {
        var held = false
        val again = object : Runnable {
            override fun run() {
                if (!held) v.parent?.requestDisallowInterceptTouchEvent(true)
                held = true
                step()
                v.postDelayed(this, 120)
            }
        }
        // A screen reader's activation (no touch) is one step.
        v.setOnClickListener { step() }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    held = false
                    view.isPressed = true
                    view.postDelayed(again, 400)
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    view.removeCallbacks(again)
                    if (!held) step()
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.removeCallbacks(again)
                }
            }
            true
        }
    }

    private fun refreshSubtitles() {
        handler.removeCallbacks(subTicker)
        if (subFile != null) handler.post(subTicker)
    }

    /** A subtitle file's lines, checked ten times a second (with the timing applied, 3.0.3: LocalPlayback's). */
    private val subTicker = object : Runnable {
        override fun run() {
            val track = subFile ?: return
            val p = player ?: return
            // Out of sight (a video's sound in the background) nothing needs them.
            if (!LocalPlayback.screenVisible) return
            showCues(track.at((p.currentPosition - LocalPlayback.subtitleDelayMs) * 1000))
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
        subtitleText.background = if (box != 0) Ui.shape(this, box, Ui.R_XSMALL) else null
        // Without a box, a heavier shadow keeps them readable on a bright picture.
        subtitleText.setShadowLayer(Ui.dpf(this, if (box == 0) 3.5f else 2f), 0f, 0f, Color.BLACK)
    }

    /**
     * Subtitles rise above the controls while they are shown. 3.0.3: they sit where they were dragged
     * to (above the controls all the same), above an upright panel, or in the room beside one at the
     * side (so their timing can be seen while it is changed).
     */
    private fun moveSubtitles(animate: Boolean = true) {
        val lift = (if (liftDrag.isNaN()) subLift else liftDrag) * root.height
        val open = panel.isOpen
        val controlsUp = if (controlsShown && videoMode && !open) (controls.height - dp(20)).coerceAtLeast(0).toFloat() else 0f
        val panelUp = if (open && !panel.side) (panel.size - dp(16)).toFloat() else 0f
        val up = PlayerRules.subtitleUp(lift, controlsUp, panelUp)
        val beside = open && panel.side
        val dx = if (beside) -panel.size / 2f else 0f
        val widest = if (beside) (root.width - panel.size - dp(48)).coerceAtLeast(dp(120)) else Int.MAX_VALUE
        if (subtitleText.maxWidth != widest) subtitleText.maxWidth = widest
        subtitleText.animate().cancel()
        if (animate) {
            subtitleText.animate().translationY(-up).translationX(dx).setDuration(Motion.SHORT).start()
        } else {
            subtitleText.translationY = -up
            subtitleText.translationX = dx
        }
    }

    /** 3.0.3: a picture subtitle (DVD, Blu-ray) moves and shrinks with the picture for an upright panel. */
    private fun followPicture(animate: Boolean) {
        val lp = subtitlePicture.layoutParams as? FrameLayout.LayoutParams ?: return
        subtitlePicture.pivotX = frame.pivotX - lp.leftMargin
        subtitlePicture.pivotY = frame.pivotY - lp.topMargin
        subtitlePicture.animate().cancel()
        if (animate) {
            subtitlePicture.animate().translationY(videoShift).scaleX(videoScale).scaleY(videoScale).setDuration(180).setInterpolator(Motion.standard).start()
        } else {
            subtitlePicture.translationY = videoShift
            subtitlePicture.scaleX = videoScale
            subtitlePicture.scaleY = videoScale
        }
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
        followPicture(false)
        subtitlePicture.setImageBitmap(bmp)
        subtitlePicture.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ tracks and errors

    /** Sound or picture the phone can't decode (DTS sound, some HEVC): said once, with a way out. */
    private fun checkTracks(tracks: Tracks) {
        val e = LocalPlayback.current ?: return
        if (tracks.isEmpty) return
        if (e === shownEntry && e.video != videoMode) applyMode(e.video)
        // 3.0.3: the video's own subtitles chosen last time, now its tracks are known.
        if (pendingSubtitles != null && e === shownEntry) chooseKeptSubtitles(tracks)
        if (warnedTracks === e) return
        // 2.9.2: 10-bit HEVC (small "x265 10bit" films). The phone's decoders say it is beyond them
        // rather than unsupported, so ExoPlayer tries anyway and the picture stays still: said plainly.
        val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.takeIf { it.length > 0 }?.getTrackFormat(0)
        if (video != null && tenBit(video) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, false)) {
            warnedTracks = e
            L.w("Player: 10-bit HEVC (${video.codecs}) beyond the phone's decoders")
            say("This video is 10-bit HEVC, which your phone's chip can't play. Look for an x264 or 8-bit version.", action = "Open with", long = true) { openWith() }
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
        say("Your phone can't play this video's $what" + (codec?.let { " ($it)" } ?: "") + ". Another app may.", action = "Open with", long = true) { openWith() }
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
                if (e != null && e.file == null && !e.stream && !e.network) "Swift can't read this any more. Open it again from its app to play it." else "Swift isn't allowed to read this file."
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
            -> "Your phone can't play this format."
            else -> if (error.errorCode in 6000..6999) "This video is protected and can't be played here." else "Swift can't play this."
        }
        showProblem(message, openWith = e != null && !e.network, retry = network || error.errorCode in 2000..2999)
    }

    /** What went wrong, in the middle of the screen, with what can be done about it. */
    private fun showProblem(message: String, openWith: Boolean, retry: Boolean) {
        hideProblem()
        // 3.0.3: what went wrong is what matters now.
        panel.close(animate = false)
        hideUpNext()
        closeFrameBar()
        handler.removeCallbacks(showSpinner)
        spinner.visibility = View.GONE
        if (locked) setLocked(false)
        showControls(true)
        val fg = if (videoMode) Color.WHITE else Palette.color(this, R.color.text_primary)
        fun pill(text: String, main: Boolean, click: () -> Unit) = OverPicture.pill(
            this, text, Ui.Type.LABEL, if (main) Ui.onAccent(this) else fg, if (main) Ui.accent(this) else Color.TRANSPARENT, 48,
            ripple = if (videoMode) Ui.RIPPLE_ON_DARK else null, outline = if (main) null else (fg and 0x66FFFFFF),
        ).apply {
            minWidth = dp(220)
            setPadding(dp(20), dp(8), dp(20), dp(8))
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
                setPadding(0, dp(16), 0, dp(16))
            })
            val gap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) } }
            if (openWith) addView(pill("Open with another app", true) { openWith() }, gap())
            if (retry) addView(pill("Try again", !openWith) { LocalPlayback.play() }, gap())
            if (player?.hasNextMediaItem() == true) addView(pill("Next", false) { LocalPlayback.next() }, gap())
        }
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        Motion.fadeIn(panel, fromDy = 8)
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
        // 3.0.4: another app can't read a network folder's file through Swift: a copy on the phone can.
        if (e.network) {
            say("Copy it to the phone first (hold it in Network folders, then Copy to phone), then open it there", long = true)
            return
        }
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
            say("No app on your phone can open it")
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
                    say("No app on your phone can share it")
                }
            }
        }
    }

    private fun details() {
        val e = LocalPlayback.current ?: return
        val p = player ?: return
        val lines = ArrayList<Pair<String, String>>()
        e.file?.let { f ->
            lines += "Size" to UrlUtils.formatBytes(f.length())
            if (!e.private) lines += "Folder" to f.parent.orEmpty()
        }
        if (e.stream && !e.incognito) UrlUtils.host(e.uri.toString())?.let { lines += "From" to it + if (e.hls) " (stream)" else "" }
        p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { lines += "Length" to time(it) }
        p.videoFormat?.let { v ->
            val rate = v.frameRate
            val fps = when {
                rate <= 0f -> ""
                abs(rate - rate.roundToInt()) < 0.01f -> ", ${rate.roundToInt()} frames a second"
                else -> String.format(Locale.US, ", %.3f frames a second", rate)
            }
            lines += "Picture" to "${v.width} × ${v.height}" + (codecName(v)?.let { ", $it" } ?: "") + (if (tenBit(v)) " 10-bit" else "") + fps
        }
        p.audioFormat?.let { a ->
            lines += "Sound" to listOfNotNull(codecName(a), channels(a.channelCount), if (a.sampleRate > 0) "${a.sampleRate / 1000.0} kHz".replace(".0 ", " ") else null).joinToString(", ")
        }
        // 3.0.3: in the panel beside the video, as the other menus.
        showPanel { ctx ->
            Sheets.column(ctx).apply {
                addView(Sheets.header(ctx, e.title, null))
                if (lines.isEmpty()) addView(Sheets.note(ctx, "Nothing more is known about it."))
                for ((label, value) in lines) addView(PictureInfo.row(ctx, null, label, value))
            }
        }
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

    // ------------------------------------------------------------------ up next (3.0.3)

    /**
     * A video of the playlist paused at its end (LocalPlayback, only while this screen is seen):
     * the next one's name, and its picture when it comes quickly, for 5 seconds, then it plays.
     */
    override fun onUpNext() {
        if (isFinishing || !videoMode || problem != null) return
        val p = player ?: return
        if (!p.hasNextMediaItem()) return
        val next = LocalPlayback.entries.getOrNull(p.nextMediaItemIndex) ?: return
        showUpNext(next)
    }

    private fun showUpNext(next: LocalPlayback.Entry) {
        hideUpNext()
        panel.close()
        val white = Color.WHITE
        val thumb = ImageView(this).apply {
            background = Ui.shape(this@PlayerActivity, OverPicture.TINT_CHIP, Ui.R_SMALL)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(Ui.icon(this@PlayerActivity, R.drawable.ic_movie, 0xCCFFFFFF.toInt()))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val count = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            Ui.size(this, Ui.Type.CAPTION, keepFont = true)
            Ui.steadyFigures(this)
            text = upNextLabel(UP_NEXT_SECONDS)
        }
        val name = TextView(this).apply {
            text = next.title
            setTextColor(white)
            Ui.size(this, Ui.Type.BODY, keepFont = true)
            typeface = Ui.MEDIUM
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        fun pill(text: String, main: Boolean, click: () -> Unit) = OverPicture.pill(
            this, text, Ui.Type.BODY_SECONDARY, if (main) Ui.onAccent(this) else white, if (main) Ui.accent(this) else OverPicture.TINT_PILL, 40,
        ).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                if (locked) {
                    showUnlock()
                    return@setOnClickListener
                }
                click()
            }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(this@PlayerActivity, OverPicture.FILL_PANEL, Ui.R_MEDIUM)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            addView(LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(thumb, LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(LinearLayout(this@PlayerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(count)
                    addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            })
            addView(LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(pill("Cancel", false) { cancelUpNext() })
                addView(pill("Play now", true) { playUpNext() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        val width = minOf(dp(340), (root.width - dp(32)).coerceAtLeast(dp(200)))
        root.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })
        upNext = card
        upNextCount = count
        upNextLeft = UP_NEXT_SECONDS
        placeUpNext(animate = false)
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(Motion.SHORT).start()
        handler.postDelayed(upNextTick, 1000)
        // Its picture when it's quick to have (Files may have made it already); none for a private file.
        val f = next.file
        if (f != null && !next.private) {
            upNextThumb = FileThumbs.load(this, f, f.lastModified(), FileKind.VIDEO, dp(64)) { bmp ->
                if (upNext !== card) return@load
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                thumb.setImageBitmap(bmp)
            }
        }
        L.d("Player: up next")
    }

    private fun upNextLabel(seconds: Int) = "Up next in $seconds s"

    private val upNextTick = object : Runnable {
        override fun run() {
            if (upNext == null) return
            upNextLeft--
            if (upNextLeft <= 0) {
                playUpNext()
                return
            }
            upNextCount?.text = upNextLabel(upNextLeft)
            handler.postDelayed(this, 1000)
        }
    }

    private fun playUpNext() {
        hideUpNext()
        LocalPlayback.next()
    }

    /** Stays at the end, paused, with the controls to go on from there. */
    private fun cancelUpNext() {
        hideUpNext()
        showControls(true)
    }

    private fun hideUpNext() {
        handler.removeCallbacks(upNextTick)
        upNextThumb?.cancel()
        upNextThumb = null
        val card = upNext ?: return
        upNext = null
        upNextCount = null
        card.animate().cancel()
        card.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction { root.removeView(card) }.start()
    }

    /** Up next sits above the controls while they show. */
    private fun placeUpNext(animate: Boolean = true) {
        val card = upNext ?: return
        val up = if (controlsShown && videoMode) (controls.height - dp(8)).coerceAtLeast(0).toFloat() else 0f
        if (animate) card.animate().translationY(-up).setDuration(Motion.SHORT).start() else card.translationY = -up
    }

    // ------------------------------------------------------------------ hints (3.0.3)

    /** Said once: the first time a video plays whose shape is more than a tenth off the screen's. */
    private fun hintPinch() {
        if (PlayerSettings.pinchHintShown || !videoMode || !gesturesEnabled) return
        val s = player?.videoSize ?: return
        if (s.width <= 0 || s.height <= 0) return
        val video = s.width * s.pixelWidthHeightRatio / s.height
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val long = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val short = minOf(dm.widthPixels, dm.heightPixels).toFloat().coerceAtLeast(1f)
        // The screen as it turns for this video (Auto), or as it is held now.
        val screen = when {
            PlayerSettings.rotation != PlayerSettings.ROTATE_AUTO && root.height > 0 -> root.width.toFloat() / root.height
            video >= 1f -> long / short
            else -> short / long
        }
        if (!PlayerRules.shapeDiffers(video, screen)) return
        PlayerSettings.pinchHintShown = true
        // After what is said as a video starts ("Going on from…").
        handler.postDelayed({ if (!isDestroyed && videoMode) say("Pinch to fill the screen") }, 5000)
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
        // 3.0.3: the subtitles where they were dragged to, from the start.
        root.post { moveSubtitles(animate = false) }
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
        // 3.0.3: no panel left open behind; with Up next showing, a video playing on in the
        // background goes straight on to the next, as when the screen isn't seen.
        panel.close(animate = false)
        // 3.0.4: nor frame by frame (its exact seeks end with it).
        closeFrameBar()
        val wasUpNext = upNext != null
        hideUpNext()
        if (!isFinishing && !isChangingConfigurations) {
            val e = LocalPlayback.current
            if (e != null) {
                // Out of sight: a video pauses unless it plays in the background (then without its
                // picture, which saves the battery); music goes on unless Settings say otherwise.
                val goOn = if (e.video) LocalPlayback.background else PlayerSettings.backgroundAudio
                if (!goOn) LocalPlayback.pause() else if (e.video) LocalPlayback.setPictureOff(true)
                if (wasUpNext && goOn && e.video) LocalPlayback.next()
            }
        }
        LocalPlayback.saveNow()
        MusicService.sync(this)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 3.0.4: a change of the music screen's colours under way stops with it.
        coverFade.cancel()
        LocalPlayback.unwatch(this)
        player?.let {
            it.removeListener(events)
            it.clearVideoSurfaceView(surface)
            it.clearVideoFrameMetadataListener(frameWatch)
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

        /** 3.0.3: how long Up next waits before the next video plays. */
        private const val UP_NEXT_SECONDS = 5

        /** How long the last song's picture stays while the next one's is read. */
        private const val COVER_WAIT_MS = 400L

        // Heights and widths (dp) that are also half of a corner or a mask.
        private const val UNLOCK_DP = 52
        private const val CHIP_DP = 32
        private const val FRAME_BAR_DP = 44
        private const val TIMING_DP = 40

        /** The times' fixed width (dp), wider than "−1:23:45". */
        private const val TIME_DP = 64

        /** 3.0.4: the music screen's colours, in [musicShown] and the rest. */
        private const val MUSIC_BG = 0
        private const val MUSIC_ACCENT = 1
        private const val MUSIC_ON_ACCENT = 2
        private const val MUSIC_TRACK = 3

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
