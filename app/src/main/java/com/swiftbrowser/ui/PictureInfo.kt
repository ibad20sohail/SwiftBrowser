package com.swiftbrowser.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.button.MaterialButton
import com.swiftbrowser.R
import com.swiftbrowser.core.UrlUtils
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A picture's details (2.9), as a sheet from the viewer's Info button: its name, folder, size and
 * resolution, when it was taken, and what the camera wrote into it (EXIF): the camera, its settings
 * and where it was, with a way to see that place on a map.
 */
object PictureInfo {
    class Details(
        val name: String,
        val folder: String?,
        val size: Long,
        val width: Int,
        val height: Int,
        /** When it was taken (the camera's clock), or when the file was saved; 0: unknown. */
        val date: Long,
        val taken: Boolean,
        val camera: String?,
        val settings: String?,
        val latitude: Double?,
        val longitude: Double?,
    )

    /** Reads [src]'s details. Blocking (off the main thread). */
    fun read(ctx: Context, src: Pictures.Source, name: String, size: Long, info: Pictures.Info?): Details {
        val exif = if (info?.mime == "image/jpeg") Pictures.exif(ctx, src) else null
        val takenAt = exif?.let { taken(it) } ?: 0L
        val fileTime = src.file?.lastModified() ?: 0L
        val make = exif?.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
        val model = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
        // A maker and its model read as one name; a model that already names its maker isn't repeated.
        val camera = when {
            model == null -> make
            make == null || model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        }
        // 3.0: AndroidX's reader gives the place as two doubles (or nothing).
        val latLong = exif?.latLong
        val located = latLong != null && latLong.size >= 2 && !(latLong[0] == 0.0 && latLong[1] == 0.0)
        return Details(
            name = name,
            folder = src.file?.parent,
            size = if (size >= 0) size else src.file?.length() ?: -1L,
            width = info?.width ?: 0,
            height = info?.height ?: 0,
            date = if (takenAt > 0) takenAt else fileTime,
            taken = takenAt > 0,
            camera = camera,
            settings = exif?.let { settings(it) },
            latitude = if (located) latLong?.get(0) else null,
            longitude = if (located) latLong?.get(1) else null,
        )
    }

    /** The camera's own date ("2024:05:01 14:22:10", its local time), when it wrote one. */
    private fun taken(exif: ExifInterface): Long? {
        val text = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        return try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(text.trim())?.time?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    /** "f/2.0 · 1/120 s · ISO 100 · 3.5 mm": what the camera was set to, as far as it says. */
    private fun settings(exif: ExifInterface): String? {
        val parts = ArrayList<String>()
        exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { parts += String.format(Locale.US, "f/%.1f", it) }
        exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { t ->
            parts += if (t < 1.0) "1/${(1.0 / t).roundToInt()} s" else String.format(Locale.US, "%.1f s", t).replace(".0 s", " s")
        }
        // 2.9: the tag's old name: Android 9 renamed it (and still reads it by this name).
        exif.getAttributeInt("ISOSpeedRatings", 0).takeIf { it > 0 }?.let { parts += "ISO $it" }
        exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let {
            parts += String.format(Locale.US, "%.1f mm", it).replace(".0 mm", " mm")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The sheet with [d]. */
    fun show(a: Activity, d: Details) {
        val col = Sheets.column(a)
        col.addView(Sheets.header(a, d.name))
        d.folder?.let { col.addView(row(a, R.drawable.ic_folder, "Folder", it, selectable = true)) }
        val sizes = listOfNotNull(
            d.size.takeIf { it >= 0 }?.let { UrlUtils.formatBytes(it) },
            if (d.width > 0) "${d.width} × ${d.height}" else null,
            if (d.width > 0) String.format(Locale.US, "%.1f MP", d.width.toLong() * d.height / 1_000_000.0) else null,
        )
        if (sizes.isNotEmpty()) col.addView(row(a, R.drawable.ic_image, "Size and resolution", sizes.joinToString(" · ")))
        if (d.date > 0) {
            val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY
            col.addView(row(a, R.drawable.ic_history, if (d.taken) "Taken" else "Saved (the camera's date isn't in the picture)", DateUtils.formatDateTime(a, d.date, flags)))
        }
        d.camera?.let { col.addView(row(a, R.drawable.ic_pic_camera, "Camera", it)) }
        d.settings?.let { col.addView(row(a, R.drawable.ic_tune, "Camera settings", it)) }
        val lat = d.latitude
        val lon = d.longitude
        if (lat != null && lon != null) {
            val where = String.format(Locale.US, "%.5f, %.5f", lat, lon)
            col.addView(row(a, R.drawable.ic_pic_place, "Location", where, selectable = true))
            col.addView(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Open in maps"
                setOnClickListener { openMap(a, lat, lon) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(a, 68)
                topMargin = Ui.dp(a, 2)
            })
        }
        Sheets.sheet(a, col).showImmersive()
    }

    /** A detail: an icon, what it is (small) and its value. */
    private fun row(a: Activity, icon: Int, label: String, value: String, selectable: Boolean = false): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ui.dp(a, 56)
        setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), Ui.dp(a, 8))
        addView(ImageView(a).apply {
            setImageDrawable(Ui.icon(a, icon, Ui.textSecondary(a)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)).apply { marginEnd = Ui.dp(a, 20) })
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(a, Ui.Type.CAPTION, label))
            addView(Ui.text(a, Ui.Type.BODY, value).apply {
                setPadding(0, Ui.dp(a, 2), 0, 0)
                // A folder's path or a place can be copied (a long press).
                setTextIsSelectable(selectable)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** The place in the phone's maps app (geo: links are understood by Google Maps, OsmAnd, Maps.me…). */
    private fun openMap(a: Activity, lat: Double, lon: Double) {
        val point = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point")))
        } catch (_: Exception) {
            Messages.show(a, "No maps app on your phone can show the place")
        }
    }
}
