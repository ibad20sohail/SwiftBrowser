package com.swiftbrowser.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import android.widget.Switch
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp

/**
 * A small on/off switch in Swift's own colours (the Material one is large and looks like another
 * app). The row it sits in handles taps; this only draws, and slides when changed.
 */
class SlimSwitch(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val trackOff = Palette.color(context, R.color.surface_alt)
    private val outline = Palette.color(context, R.color.outline)
    private val thumbOff = Palette.color(context, R.color.text_secondary)
    private val trackOn = Palette.color(context, Looks.accent.primary)
    private val thumbOn = Palette.color(context, Looks.accent.onPrimary)
    private var position = 0f
    private var animator: ValueAnimator? = null

    var isChecked = false
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setChecked(on: Boolean, animate: Boolean) {
        // 3.0.3: already sliding there (a row refreshes itself right after a tap): the slide goes on.
        // Before, the refresh cancelled it and the thumb jumped, so Settings' switches never slid.
        if (on == isChecked && animator != null) return
        if (on == isChecked && animator == null) {
            position = if (on) 1f else 0f
            invalidate()
            return
        }
        isChecked = on
        animator?.cancel()
        animator = null
        val target = if (on) 1f else 0f
        if (!animate) {
            position = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = Motion.SHORT
            interpolator = Motion.standard
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = 34 * density
        val h = 20 * density
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        rect.set(left, top, left + w, top + h)
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.blendARGB(trackOff, trackOn, position)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        if (position < 1f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = ColorUtils.setAlphaComponent(outline, ((1f - position) * 255).toInt())
            rect.inset(0.75f * density, 0.75f * density)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.blendARGB(thumbOff, thumbOn, position)
        // The thumb: 10 dp off, 14 dp on.
        val r = (5f + 2f * position) * density
        val cx = left + h / 2 + (w - h) * position
        canvas.drawCircle(cx, top + h / 2, r, paint)
    }
}

/** Describes a settings row holding a [SlimSwitch] as a switch to screen readers. */
class SwitchRowAccessibility(private val switch: SlimSwitch) : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = Switch::class.java.name
        info.isCheckable = true
        info.isChecked = switch.isChecked
    }
}

/** Full screen (Settings → Look & screen): the phone's bars are hidden on every Swift screen. */
object SystemBars {
    /**
     * The keyboard (and on Android 7 some dialogs) brings the bars back for good; they go again a
     * moment after it has gone, as long as [wanted] says so. 3.0.1: about a second after (it was 2.5,
     * long enough to look like full screen had been lost); never while the keyboard is up.
     */
    fun keepHidden(a: Activity, wanted: () -> Boolean = { true }) {
        val decor = a.window.decorView
        // 3.0.3: hidden at once, as the screen is made (every screen calls this in onCreate). Before,
        // only once its window had focus, after Android had shown it with the bars, so they showed
        // for a moment as Settings or Files opened.
        if (SwiftApp.prefs.fullScreen && wanted()) apply(a)
        val check = object : Runnable {
            override fun run() {
                if (!SwiftApp.prefs.fullScreen || a.isFinishing || !a.hasWindowFocus() || !wanted()) return
                if (ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    decor.postDelayed(this, RECHECK_MS)
                    return
                }
                apply(a)
            }
        }
        @Suppress("DEPRECATION")
        decor.setOnSystemUiVisibilityChangeListener { visibility ->
            if (SwiftApp.prefs.fullScreen && visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                decor.removeCallbacks(check)
                decor.postDelayed(check, RECHECK_MS)
            }
        }
    }

    /** How soon the bars go again once nothing needs them (and how often that is checked while typing). */
    private const val RECHECK_MS = 900L

    fun apply(a: Activity) {
        val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
        if (SwiftApp.prefs.fullScreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Pop-ups that keep full screen. On Android 7 every new window (a dialog, a menu, a bottom sheet)
 * brings the status and navigation bars back while it is open, unless it is shown without focus,
 * given the screen's full-screen flags, and only then focused.
 */
object Immersive {
    @Suppress("DEPRECATION")
    fun show(d: android.app.Dialog) {
        val window = d.window
        val activity = activityOf(d.context)
        val flags = activity?.window?.decorView?.systemUiVisibility ?: 0
        // A dialog with a text field takes its keyboard with it (otherwise it stays up over the page).
        window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}

            override fun onViewDetachedFromWindow(v: View) {
                if (!hasTextField(v)) return
                // The screen behind takes the keyboard over once it has the focus back; close it then.
                val decor = activity?.window?.decorView ?: return
                val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                decor.viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (!hasFocus) return
                        decor.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                        decor.post { imm.hideSoftInputFromWindow(decor.windowToken, 0) }
                    }
                })
            }
        })
        if (window == null || flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
            d.show()
            paintColors(d)
            return
        }
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        d.show()
        // A sheet reaches down to the screen's edge, where the hidden navigation bar would be.
        val sheet = d is com.google.android.material.bottomsheet.BottomSheetDialog
        if (sheet) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        val layout = if (sheet) View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN else 0
        window.decorView.systemUiVisibility = flags or layout or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        paintColors(d)
    }

    /** The chosen colours (2.3): a sheet's background, a dialog's buttons and list ticks. */
    private fun paintColors(d: android.app.Dialog) {
        if (d is com.google.android.material.bottomsheet.BottomSheetDialog) Palette.paintSheet(d)
        Palette.paintDialog(d)
    }

    private fun hasTextField(v: View): Boolean = v is android.widget.EditText ||
        (v is android.view.ViewGroup && (0 until v.childCount).any { hasTextField(v.getChildAt(it)) })

    private fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

fun android.app.Dialog.showImmersive() = Immersive.show(this)

fun com.google.android.material.dialog.MaterialAlertDialogBuilder.showImmersive(): androidx.appcompat.app.AlertDialog =
    setBackground(Palette.dialogBackground(context)).create().also { Immersive.show(it) }

/** Fills its box with the picture's top part (a page's top), cut to width, instead of its middle. */
class TopCropImageView @JvmOverloads constructor(
    context: Context, attrs: android.util.AttributeSet? = null,
) : android.widget.ImageView(context, attrs) {
    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        fit()
        return changed
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        val w = width - paddingLeft - paddingRight
        val dw = d.intrinsicWidth
        if (w <= 0 || dw <= 0) return
        val h = height - paddingTop - paddingBottom
        val scale = maxOf(w.toFloat() / dw, if (d.intrinsicHeight > 0) h.toFloat() / d.intrinsicHeight else 0f)
        imageMatrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - dw * scale) / 2f, 0f)
        }
    }
}

/** The light vibration (Settings → Look & screen → Light vibration), whatever the phone's touch-vibration setting. */
/**
 * 3.0.3: a thin ring around a toolbar button (the menu's) while downloads run: filled as far as they
 * have come, or a short arc that moves on at each update when their size isn't known. Drawn as a
 * foreground, so the button stays as it is; nothing is drawn once [fraction] is null.
 */
class ProgressRing(ctx: Context) : android.graphics.drawable.Drawable() {
    private val stroke = 2.5f * ctx.resources.displayMetrics.density
    private val inset = 5f * ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val box = RectF()

    /** 0..1 as far as known, -1 for "running, size unknown", null for nothing running. */
    var fraction: Float? = null
        set(v) {
            if (v == -1f) spin = (spin + 40f) % 360f
            if (field == v && v != -1f) return
            val old = field
            field = v
            slide?.cancel()
            slide = null
            // From one known amount to another it slides, instead of jumping at each update.
            if (v != null && v >= 0f && old != null && old >= 0f) {
                slide = ValueAnimator.ofFloat(drawn, v).apply {
                    duration = Motion.SHORT
                    interpolator = Motion.standard
                    addUpdateListener {
                        drawn = it.animatedValue as Float
                        invalidateSelf()
                    }
                    start()
                }
            } else {
                drawn = v ?: 0f
                invalidateSelf()
            }
        }
    private var spin = 0f
    private var drawn = 0f
    private var slide: ValueAnimator? = null

    fun setColour(colour: Int) {
        paint.color = colour
        track.color = ColorUtils.setAlphaComponent(colour, 56)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val f = fraction ?: return
        val b = bounds
        val side = minOf(b.width(), b.height()) - 2 * inset
        if (side <= 0) return
        box.set(b.exactCenterX() - side / 2, b.exactCenterY() - side / 2, b.exactCenterX() + side / 2, b.exactCenterY() + side / 2)
        canvas.drawOval(box, track)
        if (f < 0f) canvas.drawArc(box, spin - 90f, 70f, false, paint)
        else canvas.drawArc(box, -90f, 360f * drawn.coerceIn(0.02f, 1f), false, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

object Haptics {
    fun hold(v: View) = buzz(v, android.view.HapticFeedbackConstants.LONG_PRESS)

    fun tick(v: View) = buzz(v, android.view.HapticFeedbackConstants.VIRTUAL_KEY)

    private fun buzz(v: View, kind: Int) {
        if (!SwiftApp.prefs.haptics) return
        @Suppress("DEPRECATION")
        v.performHapticFeedback(kind, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }
}
