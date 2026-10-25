package com.swiftbrowser.ui

import android.text.TextUtils
import android.util.Base64
import org.mozilla.geckoview.WebRequestError

/** Builds the page shown when a site can't be loaded (GeckoView shows nothing by default). */
object ErrorPage {
    fun create(uri: String?, error: WebRequestError): String {
        val (title, message) = describe(error)
        val certError = error.category == WebRequestError.ERROR_CATEGORY_SECURITY
        val url = TextUtils.htmlEncode(uri ?: "")
        val extra = if (certError) {
            "<button class=\"secondary\" onclick=\"document.addCertException(true).then(function(){location.reload()})\">" +
                "Continue anyway (unsafe)</button>"
        } else {
            ""
        }
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${TextUtils.htmlEncode(title)}</title>
            <style>
            :root{color-scheme:light dark}
            body{font-family:sans-serif;margin:0;padding:56px 24px;background:Canvas;color:CanvasText}
            h1{font-size:22px;margin:0 0 12px;font-weight:600}
            p{font-size:15px;line-height:1.5;opacity:.85;margin:0 0 12px}
            .url{word-break:break-all;font-size:13px;opacity:.6}
            button{margin:20px 8px 0 0;padding:12px 22px;font-size:15px;border:0;border-radius:22px;background:#1a73e8;color:#fff}
            button.secondary{background:transparent;color:inherit;border:1px solid currentColor}
            </style></head><body>
            <h1>${TextUtils.htmlEncode(title)}</h1>
            <p>${TextUtils.htmlEncode(message)}</p>
            <p class="url">$url</p>
            <button onclick="location.reload()">Try again</button>$extra
            </body></html>
        """.trimIndent()
        return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }

    private fun describe(e: WebRequestError): Pair<String, String> = when (e.code) {
        WebRequestError.ERROR_UNKNOWN_HOST ->
            "Site not found" to "Check the address, or your internet connection."
        WebRequestError.ERROR_OFFLINE, WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT ->
            "No connection" to "The connection was interrupted. Check your internet and try again."
        WebRequestError.ERROR_NET_TIMEOUT ->
            "The site took too long to respond" to "It may be busy or down. Try again in a moment."
        WebRequestError.ERROR_CONNECTION_REFUSED ->
            "Couldn't connect" to "The site refused the connection."
        WebRequestError.ERROR_REDIRECT_LOOP ->
            "This page isn't redirecting properly" to "Clearing this site's cookies may help."
        WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_BAD_HSTS_CERT ->
            "Connection not secure" to "The site's security certificate isn't valid. Someone could be trying to intercept your data."
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI, WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI,
        WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI ->
            "Dangerous site blocked" to "This site is known to install harmful software."
        WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI ->
            "Deceptive site blocked" to "This site is known to steal passwords or personal information."
        WebRequestError.ERROR_CONTENT_CRASHED ->
            "This page crashed" to "Try loading it again."
        WebRequestError.ERROR_UNKNOWN_PROTOCOL ->
            "Can't open this address" to "The browser doesn't know how to open this type of link."
        WebRequestError.ERROR_HTTPS_ONLY ->
            "No secure version of this site" to "It only works over an insecure connection. Turn off \"Secure connections first\" in Settings → Privacy to open it."
        WebRequestError.ERROR_MALFORMED_URI ->
            "Invalid address" to "Check the address for typing mistakes."
        else -> "Problem loading page" to "Something went wrong (error ${e.code})."
    }
}
