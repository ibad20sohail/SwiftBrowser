package com.swiftbrowser.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Hold-and-drag arranging (2.2: the ⋮ menu's buttons and the address bar's): a picture of the held
 * button follows the finger above everything in [host], while the buttons under it make room. The
 * button itself stays in its place (invisible) until it is let go.
 */
class DragGhost(private val host: FrameLayout) {
    private var ghost: ImageView? = null
    private var offsetX = 0f
    private var offsetY = 0f
    private val hostAt = IntArray(2)

    /** Picks up a picture of [source], held where the finger is ([rawX], [rawY] on the screen). */
    fun start(source: View, rawX: Float, rawY: Float) {
        cancel()
        if (source.width <= 0 || source.height <= 0) return
        val picture = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        source.draw(Canvas(picture))
        val at = IntArray(2)
        source.getLocationOnScreen(at)
        host.getLocationOnScreen(hostAt)
        offsetX = rawX - at[0]
        offsetY = rawY - at[1]
        val g = ImageView(host.context).apply {
            setImageBitmap(picture)
            elevation = 24 * resources.displayMetrics.density
        }
        host.addView(g, FrameLayout.LayoutParams(source.width, source.height))
        ghost = g
        move(rawX, rawY)
        g.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
    }

    fun move(rawX: Float, rawY: Float) {
        val g = ghost ?: return
        g.translationX = rawX - offsetX - hostAt[0]
        g.translationY = rawY - offsetY - hostAt[1]
    }

    /** Lets go: the picture settles onto [target] (or fades when there is none), then [done]. */
    fun drop(target: View?, done: () -> Unit) {
        val g = ghost ?: return done()
        ghost = null
        g.animate().cancel()
        if (target != null && target.isAttachedToWindow && target.width > 0) {
            val at = IntArray(2)
            target.getLocationOnScreen(at)
            g.animate()
                .translationX(at[0] - hostAt[0] + (target.width - g.width) / 2f)
                .translationY(at[1] - hostAt[1] + (target.height - g.height) / 2f)
                .scaleX(1f).scaleY(1f)
                .setDuration(150).setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    host.removeView(g)
                    done()
                }.start()
        } else {
            g.animate().alpha(0f).setDuration(120).withEndAction {
                host.removeView(g)
                done()
            }.start()
        }
    }

    fun cancel() {
        ghost?.let {
            it.animate().cancel()
            host.removeView(it)
        }
        ghost = null
    }
}
