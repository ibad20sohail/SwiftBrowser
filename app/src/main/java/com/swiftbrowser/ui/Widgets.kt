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
            duration = 150
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
        val r = (5 + 2 * position) * density
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
     * moment after it has gone, as long as [wanted] says so.
     */
    fun keepHidden(a: Activity, wanted: () -> Boolean = { true }) {
        val decor = a.window.decorView
        val check = object : Runnable {
            override fun run() {
                if (!SwiftApp.prefs.fullScreen || a.isFinishing || !a.hasWindowFocus() || !wanted()) return
                if (ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    decor.postDelayed(this, 2500)
                    return
                }
                apply(a)
            }
        }
        @Suppress("DEPRECATION")
        decor.setOnSystemUiVisibilityChangeListener { visibility ->
            if (SwiftApp.prefs.fullScreen && visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                decor.removeCallbacks(check)
                decor.postDelayed(check, 2500)
            }
        }
    }

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
object Haptics {
    fun hold(v: View) = buzz(v, android.view.HapticFeedbackConstants.LONG_PRESS)

    fun tick(v: View) = buzz(v, android.view.HapticFeedbackConstants.VIRTUAL_KEY)

    private fun buzz(v: View, kind: Int) {
        if (!SwiftApp.prefs.haptics) return
        @Suppress("DEPRECATION")
        v.performHapticFeedback(kind, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }
}
