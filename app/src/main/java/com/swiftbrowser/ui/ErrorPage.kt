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

    private const val ICON_OFFLINE = "M23.64 7c-.45-.34-4.93-4-11.64-4-1.5 0-2.89.19-4.15.48L18.18 13.8 23.64 7zm-6.6 8.22L3.27 1.44 2 2.72l2.05 2.06C1.91 5.76.59 6.82.36 7l11.63 14.49.01.01.01-.01 3.9-4.86 3.32 3.32 1.27-1.27-3.46-3.46z"
    private const val ICON_SECURITY = "M12 2 4 5v6.09c0 5.05 3.41 9.76 8 10.91 4.59-1.15 8-5.86 8-10.91V5l-8-3zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"
    private const val ICON_OTHER = "M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z"

    fun create(uri: String?, error: WebRequestError): String {
        val paused = com.swiftbrowser.engine.Engine.networkBlocked && error.category == WebRequestError.ERROR_CATEGORY_PROXY
        val (title, message) = if (paused) {
            "Waiting for the VPN" to "Swift holds pages back until the VPN is connected, so sites never see your own connection. Turn the VPN off to browse without it."
        } else {
            describe(error)
        }
        val kind = when {
            error.category == WebRequestError.ERROR_CATEGORY_SECURITY || error.category == WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> Kind.SECURITY
            error.code in OFFLINE_CODES -> Kind.OFFLINE
            else -> Kind.OTHER
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
        val (light, dark) = accents()
        // The page, text and chips in the app's own colours (2.6): it sits under Swift's toolbar.
        val pl = com.swiftbrowser.media.Helper.pageColors(false)
        val pd = com.swiftbrowser.media.Helper.pageColors(true)
        fun c(o: org.json.JSONObject, k: String, d: String) = o.optString(k, d).ifBlank { d }
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${TextUtils.htmlEncode(title)}</title>
            <style>
            :root{color-scheme:light dark;--a:${light.first};--on:${light.second};--c:${light.third};--bg:${c(pl, "bg", "#fff")};--fg:${c(pl, "text", "#1a1c21")};--sub:${c(pl, "muted", "#5c6370")};--chip:${c(pl, "surface", "#eff2f7")}}
            @media (prefers-color-scheme:dark){:root{--a:${dark.first};--on:${dark.second};--c:${dark.third};--bg:${c(pd, "bg", "#15171c")};--fg:${c(pd, "text", "#e6e8ec")};--sub:${c(pd, "muted", "#a3a9b4")};--chip:${c(pd, "surface", "#2a2d35")}}}
            html,body{background:var(--bg);color:var(--fg)}
            body{font-family:Roboto,sans-serif;margin:0;padding:12vh 28px 40px;max-width:520px;margin:0 auto;box-sizing:border-box}
            .icon{width:72px;height:72px;border-radius:50%;background:var(--c);display:flex;align-items:center;justify-content:center;margin-bottom:24px}
            .icon svg{width:36px;height:36px;fill:var(--a)}
            h1{font-size:22px;line-height:1.3;margin:0 0 10px;font-weight:500}
            p{font-size:15px;line-height:1.55;color:var(--sub);margin:0 0 12px}
            .site{display:inline-block;max-width:100%;box-sizing:border-box;padding:6px 12px;border-radius:14px;background:var(--chip);font-size:13px;color:var(--sub);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin:4px 0 8px}
            .wait{font-size:14px}
            .buttons{margin-top:24px;display:flex;flex-wrap:wrap;gap:12px}
            button{padding:12px 24px;font-size:15px;font-weight:500;border:0;border-radius:24px;background:var(--a);color:var(--on);font-family:inherit}
            button.secondary{background:transparent;color:var(--sub);border:1px solid var(--sub)}
            </style></head><body>
            <div class="icon"><svg viewBox="0 0 24 24"><path d="$icon"/></svg></div>
            <h1>${TextUtils.htmlEncode(title)}</h1>
            <p>${TextUtils.htmlEncode(message)}</p>
            <div class="site" title="$full">$site</div>
            $waiting
            <div class="buttons"><button onclick="location.reload()">Try again</button>$extra</div>
            </body></html>
        """.trimIndent()
        return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }

    private val OFFLINE_CODES = setOf(
        WebRequestError.ERROR_UNKNOWN_HOST, WebRequestError.ERROR_OFFLINE, WebRequestError.ERROR_NET_RESET,
        WebRequestError.ERROR_NET_INTERRUPT, WebRequestError.ERROR_NET_TIMEOUT, WebRequestError.ERROR_CONNECTION_REFUSED,
    )

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

    private fun describe(e: WebRequestError): Pair<String, String> = when {
        // Offline, the engine can't look up any site's name: say what really happened (2.6).
        e.code == WebRequestError.ERROR_UNKNOWN_HOST && !com.swiftbrowser.engine.DataSaver.online ->
            "You're offline" to "Swift isn't connected to the internet. Check your Wi-Fi or mobile data."
        else -> describeCode(e)
    }

    private fun describeCode(e: WebRequestError): Pair<String, String> = when (e.code) {
        WebRequestError.ERROR_UNKNOWN_HOST ->
            "Can't find this site" to "Check the address for typing mistakes, or check that you're connected to the internet."
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
            "Can't open this kind of link" to "Neither Swift nor another app on this phone can open it."
        WebRequestError.ERROR_HTTPS_ONLY ->
            "This site has no secure version" to "Its pages can only be sent unprotected. To open it anyway, turn off \"Use secure connections\" in Settings → Privacy & backup."
        WebRequestError.ERROR_MALFORMED_URI ->
            "That address doesn't look right" to "Check it for typing mistakes."
        else -> "Couldn't load this page" to "Something went wrong (error ${e.code}). Try again."
    }
}
