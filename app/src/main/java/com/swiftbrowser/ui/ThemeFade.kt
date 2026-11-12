package com.swiftbrowser.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewTreeObserver
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import java.lang.ref.WeakReference

/**
 * 3.0.10: light and dark (and new colours) change with a cross-fade. Android makes every screen again
 * for the change and shows no transition of its own; Looks covered the new screen with a flat colour
 * that faded, so the old one went in one jump. Now a picture of the screen on show is taken first
 * (its web page too, through the engine), and it fades away over the screen made again in the new
 * colours. The browser keeps it until its page, loaded again in the new colours, has been drawn.
 *
 * One picture of the screen (about 3.7 MB on a 720p phone) for a moment; none without animations.
 */
object ThemeFade {
    private const val PAGE_WAIT_MS = 400L
    private const val CLAIM_MS = 3000L
    private const val FADE_MS = 320L

    private val handler = Handler(Looper.getMainLooper())
    private val token = Any()
    private val dropToken = Any()

    /** The screen on show (SwiftApp follows it as screens come and go). */
    var shown: WeakReference<Activity>? = null

    private var picture: Bitmap? = null
    private var pictureOf: String? = null
    private var takenAt = 0L
    private var taking = false

    /**
     * Takes the picture of the screen on show, then does [change] (which makes the screens again). At
     * once, without a picture, when there is no screen, animations are off or the memory isn't there.
     */
    fun takeThen(change: () -> Unit) {
        if (taking) return
        val a = shown?.get()
        if (a == null || a.isFinishing || a is PlayerActivity || !Motion.enabled(a)) {
            change()
            return
        }
        val decor = a.window.decorView
        val w = decor.width
        val h = decor.height
        val bmp = if (w > 0 && h > 0) {
            try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
            } catch (e: Throwable) {
                L.w("Theme fade: no picture (${e.javaClass.simpleName})")
                null
            }
        } else {
            null
        }
        if (bmp == null) {
            change()
            return
        }
        val pageBg = Palette.color(a, R.color.page_bg)
        val name = a.javaClass.name
        taking = true
        var done = false
        fun finish(page: Bitmap?, at: Rect?) {
            if (done) return
            done = true
            taking = false
            handler.removeCallbacksAndMessages(token)
            val c = Canvas(bmp)
            // The page goes where the screen's own picture has its hole (the engine draws it apart).
            val behind = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) }
            if (page != null && at != null) {
                c.drawBitmap(page, null, at, behind)
                page.recycle()
            }
            // What is still missing (the page, if the engine was slow) in the old background.
            c.drawColor(pageBg, PorterDuff.Mode.DST_OVER)
            drop()
            picture = bmp
            pictureOf = name
            takenAt = SystemClock.uptimeMillis()
            // Not claimed (the screen wasn't made again): it doesn't stay in memory.
            handler.removeCallbacksAndMessages(dropToken)
            handler.postAtTime({ drop() }, dropToken, takenAt + CLAIM_MS)
            change()
        }
        val page = (a as? MainActivity)?.pageForPicture()
        if (page == null) {
            finish(null, null)
            return
        }
        handler.postAtTime({ finish(null, null) }, token, SystemClock.uptimeMillis() + PAGE_WAIT_MS)
        try {
            page.first.capturePixels().accept({ b -> finish(b, page.second) }, { _ -> finish(null, null) })
        } catch (_: Exception) {
            finish(null, null)
        }
    }

    /** The picture for [a], a screen just made again in the new colours (null: none for this one). */
    fun claim(a: Activity): Bitmap? {
        val p = picture ?: return null
        if (SystemClock.uptimeMillis() - takenAt > CLAIM_MS) {
            drop()
            return null
        }
        if (pictureOf != a.javaClass.name) return null
        picture = null
        pictureOf = null
        return p
    }

    private fun drop() {
        picture?.recycle()
        picture = null
        pictureOf = null
    }

    /** [picture] over [a]'s new screen, fading away from its first frame (the browser: once its page is drawn). */
    fun fadeOver(a: Activity, picture: Bitmap) {
        val decor = a.window.decorView
        val cover = BitmapDrawable(a.resources, picture).apply { setBounds(0, 0, picture.width, picture.height) }
        decor.overlay.add(cover)
        var started = false
        val fade = fade@{
            if (started) return@fade
            started = true
            ValueAnimator.ofInt(255, 0).apply {
                duration = FADE_MS
                interpolator = Motion.standard
                addUpdateListener { cover.alpha = it.animatedValue as Int }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        decor.overlay.remove(cover)
                        picture.recycle()
                    }
                })
                start()
            }
        }
        if ((a as? MainActivity)?.afterPageRedrawn(fade) == true) return
        val observer = decor.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                (if (observer.isAlive) observer else decor.viewTreeObserver).removeOnPreDrawListener(this)
                fade()
                return true
            }
        })
    }
}
