package com.swiftbrowser.ui

import android.content.Context
import android.content.res.Configuration
import android.text.TextUtils
import android.util.Base64
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.UrlUtils
import org.mozilla.geckoview.WebRequestError

/**
 * The page shown when a site can't be loaded (GeckoView shows nothing by default), in the app's
 * look (2.0): an icon for the kind of problem, what happened in plain words, the site, and Try
 * again in the accent colour. Pages that failed for lack of connection load again by themselves
 * when it comes back (TabManager), and say so.
 */
object ErrorPage {
    private enum class Kind { OFFLINE, SECURITY, OTHER }

    // Material Symbols Rounded "wifi_off", "gpp_maybe" and "error", in their 960 viewBox, as the app's icons.
    private const val ICON_OFFLINE = "M762-84 414-434q-31 7-59.5 19T301-386q-21 14-46.5 14.5T212-389q-18-18-16.5-43.5T217-473q23-17 48.5-31t52.5-26l-90-90q-26 14-50.5 29.5T130-557q-20 16-45.5 16T42-559q-18-18-17-43t21-41q22-18 45-34.5t49-30.5l-56-56q-11-11-11-28t11-28q11-11 28-11t28 11l679 679q12 12 12 28.5T819-84q-12 11-28.5 11.5T762-84Zm-282-36q-42 0-71-29.5T380-220q0-42 29-71t71-29q42 0 71 29t29 71q0 41-29 70.5T480-120Zm273-275q-16 16-37.5 15.5T678-396l-10-10-10-10-96-96q-13-13-5-27t28-9q45 11 85.5 31t75.5 47q18 14 20.5 36.5T753-395Zm165-164q-17 18-42 18.5T831-556q-72-59-161.5-91.5T480-680q-21 0-40.5 1.5T400-674q-25 4-45-10.5T331-724q-4-25 11-45t40-24q24-4 48.5-5.5T480-800q125 0 235.5 41.5T914-644q20 17 21 42t-17 43Z"
    private const val ICON_SECURITY = "M480-320q17 0 28.5-11.5T520-360q0-17-11.5-28.5T480-400q-17 0-28.5 11.5T440-360q0 17 11.5 28.5T480-320Zm0-160q17 0 28.5-11.5T520-520v-120q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v120q0 17 11.5 28.5T480-480Zm0 396q-7 0-13-1t-12-3q-135-45-215-166.5T160-516v-189q0-25 14.5-45t37.5-29l240-90q14-5 28-5t28 5l240 90q23 9 37.5 29t14.5 45v189q0 140-80 261.5T505-88q-6 2-12 3t-13 1Zm0-80q104-33 172-132t68-220v-189l-240-90-240 90v189q0 121 68 220t172 132Zm0-316Z"
    private const val ICON_OTHER = "M480-280q17 0 28.5-11.5T520-320q0-17-11.5-28.5T480-360q-17 0-28.5 11.5T440-320q0 17 11.5 28.5T480-280Zm0-160q17 0 28.5-11.5T520-480v-160q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640v160q0 17 11.5 28.5T480-440Zm0 360q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z"

    /**
     * 3.0: whether a page that failed with [code] waits for the connection and loads again by itself
     * (TabDelegates). Only when it failed for lack of one: while the phone is online, a site whose
     * name isn't found (a typing mistake), that doesn't answer or turns the connection away won't
     * come by waiting, and isn't loaded again every time its tab is opened.
     */
    fun waitsForConnection(code: Int, online: Boolean): Boolean = when (code) {
        WebRequestError.ERROR_OFFLINE, WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT -> true
        WebRequestError.ERROR_UNKNOWN_HOST, WebRequestError.ERROR_NET_TIMEOUT, WebRequestError.ERROR_CONNECTION_REFUSED -> !online
        else -> false
    }

    fun create(uri: String?, error: WebRequestError): String {
        val paused = com.swiftbrowser.engine.Engine.networkBlocked && error.category == WebRequestError.ERROR_CATEGORY_PROXY
        val online = com.swiftbrowser.engine.DataSaver.online
        val (title, message) = if (paused) {
            "Waiting for the VPN" to "Swift holds pages back until the VPN is connected, so sites never see your own connection. Turn the VPN off to browse without it."
        } else {
            describe(error, online)
        }
        val kind = when {
            error.category == WebRequestError.ERROR_CATEGORY_SECURITY || error.category == WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> Kind.SECURITY
            waitsForConnection(error.code, online) -> Kind.OFFLINE
            else -> Kind.OTHER
        }
        // 3.0: a name that isn't found while online is most likely mistyped: a search for it finds the site.
        val lookFor = if (!paused && online && error.code == WebRequestError.ERROR_UNKNOWN_HOST) {
            UrlUtils.host(uri)?.removePrefix("www.")?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val certError = error.category == WebRequestError.ERROR_CATEGORY_SECURITY
        val site = TextUtils.htmlEncode(UrlUtils.siteName(uri).ifEmpty { uri ?: "" })
        val full = TextUtils.htmlEncode(uri ?: "")
        val icon = when (kind) {
            Kind.OFFLINE -> ICON_OFFLINE
            Kind.SECURITY -> ICON_SECURITY
            Kind.OTHER -> ICON_OTHER
        }
        val waiting = when {
            paused -> "<p class=\"wait\">This page loads by itself when the VPN is connected.</p>"
            kind == Kind.OFFLINE -> "<p class=\"wait\">This page loads by itself when you're back online.</p>"
            else -> ""
        }
        val extra = if (certError) {
            "<button class=\"secondary\" onclick=\"document.addCertException(true).then(function(){location.reload()})\">" +
                "Open anyway (not safe)</button>"
        } else {
            ""
        }
        val buttons = if (lookFor != null) {
            "<a class=\"button\" href=\"${TextUtils.htmlEncode(UrlUtils.searchUrl(lookFor))}\">Search for it</a>" +
                "<button class=\"secondary\" onclick=\"location.reload()\">Try again</button>"
        } else {
            "<button onclick=\"location.reload()\">Try again</button>$extra"
        }
        val (light, dark) = accents()
        // The page, text and chips in the app's own colours (2.6): it sits under Swift's toolbar.
        val pl = com.swiftbrowser.media.Helper.pageColors(false)
        val pd = com.swiftbrowser.media.Helper.pageColors(true)
        fun c(o: org.json.JSONObject, k: String, d: String) = o.optString(k, d).ifBlank { d }
        // 3.0.5: Swift's own font (when it is on), carried inside the page: see fontFaces().
        val faces = fontFaces()
        val fonts = if (faces.isEmpty()) "Roboto,sans-serif" else "'Swift Sans',Roboto,sans-serif"
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${TextUtils.htmlEncode(title)}</title>
            <style>
            /*faces*/
            :root{color-scheme:light dark;--a:${light.first};--on:${light.second};--c:${light.third};--bg:${c(pl, "bg", "#fff")};--fg:${c(pl, "text", "#1a1c21")};--sub:${c(pl, "muted", "#5c6370")};--chip:${c(pl, "surface", "#eff2f7")}}
            @media (prefers-color-scheme:dark){:root{--a:${dark.first};--on:${dark.second};--c:${dark.third};--bg:${c(pd, "bg", "#15171c")};--fg:${c(pd, "text", "#e6e8ec")};--sub:${c(pd, "muted", "#a3a9b4")};--chip:${c(pd, "surface", "#2a2d35")}}}
            html,body{background:var(--bg);color:var(--fg)}
            body{font-family:$fonts;margin:0;padding:12vh 28px 40px;max-width:520px;margin:0 auto;box-sizing:border-box}
            .icon{width:72px;height:72px;border-radius:50%;background:var(--c);display:flex;align-items:center;justify-content:center;margin-bottom:24px}
            .icon svg{width:36px;height:36px;fill:var(--a)}
            h1{font-size:23px;line-height:1.3;margin:0 0 10px;font-weight:500}
            p{font-size:15px;line-height:1.55;color:var(--sub);margin:0 0 12px}
            .site{display:inline-block;max-width:100%;box-sizing:border-box;padding:6px 12px;border-radius:12px;background:var(--chip);font-size:13px;color:var(--sub);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin:4px 0 8px}
            .wait{font-size:14px}
            .buttons{margin-top:24px;display:flex;flex-wrap:wrap;gap:12px}
            button,a.button{padding:12px 24px;font-size:15px;font-weight:500;border:0;border-radius:24px;background:var(--a);color:var(--on);font-family:inherit;text-decoration:none;display:inline-block;line-height:normal;transition:transform 100ms}
            button:active,a.button:active{transform:scale(.97)}
            button.secondary{background:transparent;color:var(--sub);border:1px solid var(--sub)}
            </style></head><body>
            <div class="icon"><svg viewBox="0 -960 960 960"><path d="$icon"/></svg></div>
            <h1>${TextUtils.htmlEncode(title)}</h1>
            <p>${TextUtils.htmlEncode(message)}</p>
            <div class="site" title="$full">$site</div>
            $waiting
            <div class="buttons">$buttons</div>
            </body></html>
        """.trimIndent().replaceFirst("/*faces*/", faces)
        return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 3.0.5: Swift's font on its error pages, as CSS for inside the page, or "" if it couldn't be read.
     * These pages are data: addresses, which can't load the helper's font files (reader view and the
     * document viewer can, being the helper's own): each carries its letters as data: fonts. So only
     * a small cut made for the page is used (assets/error_page: ASCII and a little punctuation, in
     * the two weights it shows, about 11 KB each), read when a page is made.
     */
    private fun fontFaces(): String {
        val app: Context = SwiftApp.instance ?: return ""
        if (!AppFont.active) return ""
        return try {
            fontFace(app, 400, "regular") + fontFace(app, 500, "medium")
        } catch (e: java.io.IOException) {
            // The page still shows, in the phone's font.
            com.swiftbrowser.core.L.w("Couldn't read the error page's font", e)
            ""
        }
    }

    private fun fontFace(app: Context, weight: Int, name: String): String {
        val data = Base64.encodeToString(app.assets.open("error_page/$name.woff2").use { it.readBytes() }, Base64.NO_WRAP)
        return "@font-face{font-family:'Swift Sans';font-weight:$weight;src:url(data:font/woff2;base64,$data) format('woff2')}\n"
    }

    /** The accent (strong, on it, container) as CSS colours, for light and for dark pages. */
    private fun accents(): Pair<Triple<String, String, String>, Triple<String, String, String>> {
        val app: Context = SwiftApp.instance ?: return Triple("#1b66e4", "#fff", "#dce7ff") to Triple("#8ab4ff", "#0a1f44", "#233a63")
        fun of(night: Boolean): Triple<String, String, String> {
            val config = Configuration(app.resources.configuration)
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val c = app.createConfigurationContext(config)
            val a = Looks.accent
            fun hex(res: Int) = String.format("#%06x", Palette.color(c, res) and 0xFFFFFF)
            return Triple(hex(a.primary), hex(a.onPrimary), hex(a.container))
        }
        return of(false) to of(true)
    }

    private fun describe(e: WebRequestError, online: Boolean): Pair<String, String> = when {
        // Offline, the engine can't look up any site's name: say what really happened (2.6).
        e.code == WebRequestError.ERROR_UNKNOWN_HOST && !online ->
            "You're offline" to "Swift isn't connected to the internet. Check your Wi-Fi or mobile data."
        // 3.0: online, a name that isn't found is a typing mistake (or a site that's gone).
        e.code == WebRequestError.ERROR_UNKNOWN_HOST ->
            "Check the address" to "Swift can't find a site with this name. It may be mistyped: a search can find the right one."
        else -> describeCode(e)
    }

    private fun describeCode(e: WebRequestError): Pair<String, String> = when (e.code) {
        WebRequestError.ERROR_OFFLINE, WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT ->
            "You're offline" to "The connection dropped. Check your Wi-Fi or mobile data."
        WebRequestError.ERROR_NET_TIMEOUT ->
            "This site is taking too long" to "It may be busy or your connection may be slow. Try again in a moment."
        WebRequestError.ERROR_CONNECTION_REFUSED ->
            "This site won't connect" to "It may be down right now. Try again later."
        WebRequestError.ERROR_REDIRECT_LOOP ->
            "This page keeps sending you around in a loop" to "Clearing this site's cookies may fix it: tap the icon next to the address, then Clear cookies and data of this site."
        WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_BAD_HSTS_CERT ->
            "This connection isn't private" to "The site's security certificate isn't valid, so someone could see or change what you send. It's safest to go back."
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI, WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI,
        WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI ->
            "Dangerous site blocked" to "This site is known to install harmful apps or software."
        WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI ->
            "Fake site blocked" to "This site is known to trick people into giving away passwords or personal details."
        WebRequestError.ERROR_CONTENT_CRASHED ->
            "This page stopped working" to "Try loading it again."
        WebRequestError.ERROR_UNKNOWN_PROTOCOL ->
            "Can't open this kind of link" to "Neither Swift nor another app on your phone can open it."
        WebRequestError.ERROR_HTTPS_ONLY ->
            "This site has no secure version" to "Its pages can only be sent unprotected. To open it anyway, turn off \"Use secure connections\" in Settings → Privacy & security."
        WebRequestError.ERROR_MALFORMED_URI ->
            "That address doesn't look right" to "Check it for typing mistakes."
        else -> "Couldn't load this page" to "Something went wrong (error ${e.code}). Try again."
    }
}
