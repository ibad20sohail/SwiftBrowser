package com.swiftbrowser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.swiftbrowser.R
import com.swiftbrowser.files.SpecialFolders
import kotlin.math.roundToInt

/**
 * 3.0.4: the standard folders' icons in Files (DCIM, Download, Pictures… see SpecialFolders): the
 * folder icon with a small mark of what the folder holds inside it (a camera, a download arrow…).
 * Made while Swift runs from the two icons as they are, so they follow the app's icon style
 * whatever it is: on a filled folder the mark is cut out of it (drawn in the colour behind the
 * icon), on an outlined one it is drawn in the folder's colour.
 */
object FolderIcons {
    /** The mark's icon for each standard folder. */
    fun markIcon(mark: SpecialFolders.Mark): Int = when (mark) {
        SpecialFolders.Mark.CAMERA -> R.drawable.ic_pic_camera
        SpecialFolders.Mark.DOWNLOAD -> R.drawable.ic_download
        SpecialFolders.Mark.PICTURES -> R.drawable.ic_image
        SpecialFolders.Mark.MOVIES -> R.drawable.ic_movie
        SpecialFolders.Mark.MUSIC -> R.drawable.ic_music_note
        SpecialFolders.Mark.DOCUMENTS -> R.drawable.ic_documents
        SpecialFolders.Mark.SCREENSHOTS -> R.drawable.ic_screenshot
    }

    /**
     * The folder icon in [color] with [mark] inside it, on a background of [behind] (the colour
     * the icon sits on); null if the icons can't be had. A new drawable each time: a row keeps the
     * ones it made (FilesActivity).
     */
    fun make(ctx: Context, mark: SpecialFolders.Mark, color: Int, behind: Int): Drawable? {
        val folder = Ui.icon(ctx, R.drawable.ic_folder, color) ?: return null
        val glyph = Ui.icon(ctx, markIcon(mark), if (folderFilled(ctx)) behind else color) ?: return folder
        return FolderWithMark(folder, glyph)
    }

    /** Whether the folder icon is a filled shape, worked out once (null until then). */
    private var filled: Boolean? = null

    /** The folder icon drawn small: is the middle of its body drawn (filled) or empty (an outline)? */
    private fun folderFilled(ctx: Context): Boolean {
        filled?.let { return it }
        val yes = try {
            val d = Ui.icon(ctx, R.drawable.ic_folder, 0xFF000000.toInt())
            val size = 48
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d?.setBounds(0, 0, size, size)
            d?.draw(Canvas(b))
            // The body's middle, below the tab (12, 14 of the icon's 24).
            val alpha = (b.getPixel(size / 2, size * 14 / 24) ushr 24)
            b.recycle()
            alpha > 128
        } catch (e: Exception) {
            false
        }
        filled = yes
        return yes
    }

    /** How big the mark is (its icon's box, of the folder's width), and where its middle is (of the height). */
    private const val MARK_SIZE = 9f / 24f
    private const val MARK_MIDDLE = 13.2f / 24f

    /** The folder, and the mark in its body. */
    private class FolderWithMark(private val folder: Drawable, private val mark: Drawable) : Drawable() {
        override fun onBoundsChange(bounds: Rect) {
            folder.bounds = bounds
            val size = (bounds.width() * MARK_SIZE).roundToInt()
            val left = bounds.left + (bounds.width() - size) / 2
            val top = (bounds.top + bounds.height() * MARK_MIDDLE - size / 2f).roundToInt()
            mark.setBounds(left, top, left + size, top + size)
        }

        override fun draw(canvas: Canvas) {
            folder.draw(canvas)
            mark.draw(canvas)
        }

        override fun getIntrinsicWidth() = folder.intrinsicWidth

        override fun getIntrinsicHeight() = folder.intrinsicHeight

        override fun setAlpha(alpha: Int) {
            folder.alpha = alpha
            mark.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            folder.colorFilter = colorFilter
            mark.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
