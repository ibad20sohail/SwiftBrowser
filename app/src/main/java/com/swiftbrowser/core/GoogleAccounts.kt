package com.swiftbrowser.core

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.swiftbrowser.engine.Net
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Google account signed in to each profile (3.0): its name, address and picture, as Google's own
 * pages show them in that profile's tabs (the helper extension reads them there), for the profiles
 * list and the profile buttons. Never from incognito. Kept apart from the settings, so backups leave
 * it out like the sign-ins themselves; forgotten when the profile signs out of Google or its cookies
 * are cleared.
 */
object GoogleAccounts {
    class Account(val name: String, val email: String, val photo: String) {
        /** The name Google shows, or the address for an account without one. */
        val label: String get() = name.ifBlank { email }
    }

    private const val PICTURE_SIZE = 128
    private const val MAX_BYTES = 512 * 1024

    private val googleHost = Regex("^(.+\\.)?google\\.(com|co\\.[a-z]{2}|com\\.[a-z]{2}|[a-z]{2})$")
    private val pictureHost = Regex("^[a-z0-9-]+\\.googleusercontent\\.com$")
    private val email = Regex("^[^\\s@()<>]+@[^\\s@()<>]+\\.[^\\s@()<>]+$")

    private lateinit var sp: SharedPreferences
    private lateinit var dir: File
    private val exec = Background.single("accounts")
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Pictures by profile, once read from disk (missing ones aren't in it). */
    private val pictures = ConcurrentHashMap<Long, Bitmap>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("google_accounts", Context.MODE_PRIVATE)
        dir = File(ctx.filesDir, "accounts")
        // A few small files: read once, so the profile buttons have them from the start.
        exec.execute {
            dir.listFiles { f -> f.name.endsWith(".png") }?.forEach { f ->
                val id = f.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                BitmapFactory.decodeFile(f.path)?.let { pictures[id] = it }
            }
            if (pictures.isNotEmpty()) main.post { changed() }
        }
    }

    /** Called on the main thread when an account, its name or its picture changes. */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun get(profileId: Long): Account? = try {
        sp.getString("$profileId", null)?.let { JSONObject(it) }?.let {
            Account(it.optString("name"), it.optString("email"), it.optString("photo"))
        }
    } catch (_: Exception) {
        null
    }

    /** The account's picture as a circle, or null when there's no account or no picture yet. */
    fun picture(ctx: Context, profileId: Long): Drawable? {
        if (get(profileId) == null) return null
        val bmp = pictures[profileId] ?: return null
        return RoundedBitmapDrawableFactory.create(ctx.resources, bmp).apply {
            isCircular = true
            setAntiAlias(true)
        }
    }

    /**
     * A Google page in a tab of [profileId] said who is signed in ("google-account" from the helper
     * extension): [msg] has the name, address and picture, or "out" when it offers to sign in.
     */
    fun onSeen(profileId: Long, msg: JSONObject) {
        val page = msg.optString("page")
        if (!UrlUtils.isHttp(page) || !isGoogleHost(UrlUtils.host(page))) return
        if (msg.optBoolean("out")) {
            if (get(profileId) != null) {
                L.d("Profile $profileId: signed out of Google")
                forget(profileId)
            }
            return
        }
        val address = msg.optString("email").trim()
        if (address.length > 200 || !email.matches(address)) return
        val name = msg.optString("name").trim().take(100)
        val photo = biggerPicture(msg.optString("photo").trim()).takeIf { isPictureUrl(it) } ?: ""
        val old = get(profileId)
        if (old != null && old.email == address && old.name == name && old.photo == photo && (photo.isEmpty() || pictures.containsKey(profileId))) return
        sp.edit().putString("$profileId", JSONObject().put("name", name).put("email", address).put("photo", photo).toString()).apply()
        if (old?.email != address) L.d("Profile $profileId: signed in to Google")
        if (photo.isEmpty()) {
            dropPicture(profileId)
        } else if (old?.photo != photo || !pictures.containsKey(profileId)) {
            fetchPicture(profileId, photo)
        }
        changed()
    }

    /** The profile signed out of Google, had its cookies cleared or was deleted. */
    fun forget(profileId: Long) {
        if (!sp.contains("$profileId") && !pictures.containsKey(profileId)) return
        sp.edit().remove("$profileId").apply()
        dropPicture(profileId)
        main.post { changed() }
    }

    /** Google's cookies went in every profile at once (a site's data cleared for all of them). */
    fun forgetAll() {
        for (key in sp.all.keys) key.toLongOrNull()?.let { forget(it) }
    }

    /** Google Search, Gmail, Google Account and the rest: google.com and Google's country addresses. */
    fun isGoogleHost(host: String?): Boolean = host != null && googleHost.matches(host.lowercase(Locale.ROOT))

    /**
     * Google's account pictures only (lh3.googleusercontent.com and the like), never other sites. The
     * host is read with Java's own URI parser, which also works in the tests on the PC.
     */
    fun isPictureUrl(url: String): Boolean {
        if (!url.startsWith("https://") || url.length >= 2000) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return pictureHost.matches(host.lowercase(Locale.ROOT))
    }

    /** Google's bar asks for a 32 px picture ("=s32-c"); the same picture at [PICTURE_SIZE] px. */
    fun biggerPicture(url: String): String = url.replace(Regex("=s\\d+(?=-|$)"), "=s$PICTURE_SIZE")

    private fun file(profileId: Long) = File(dir, "$profileId.png")

    private fun dropPicture(profileId: Long) {
        pictures.remove(profileId)
        exec.execute { file(profileId).delete() }
    }

    private fun fetchPicture(profileId: Long, url: String) {
        exec.execute {
            val bytes = Net.get(url, MAX_BYTES, "image/*")?.bytes
            val decoded = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (decoded == null) {
                L.w("Profile $profileId: couldn't load the Google account's picture")
                return@execute
            }
            val bmp = if (decoded.width > PICTURE_SIZE || decoded.height > PICTURE_SIZE) {
                Bitmap.createScaledBitmap(decoded, PICTURE_SIZE, PICTURE_SIZE, true)
            } else {
                decoded
            }
            main.post {
                // Signed out (or another picture came) while it loaded.
                if (get(profileId)?.photo != url) return@post
                pictures[profileId] = bmp
                exec.execute {
                    try {
                        dir.mkdirs()
                        FileOutputStream(file(profileId)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } catch (e: Exception) {
                        L.w("Couldn't keep the Google account's picture", e)
                    }
                }
                changed()
            }
        }
    }

    private fun changed() {
        for (l in listeners) l()
    }
}
