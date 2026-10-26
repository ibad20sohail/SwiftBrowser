package com.swiftbrowser.ui

/** The library's scanner screen, in full screen when Swift is (2.2). */
class QrScanActivity : com.journeyapps.barcodescanner.CaptureActivity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.apply(this)
    }
}

/** What a scanned QR code holds, beyond plain text and web addresses (2.2). */
object QrCodes {
    class Wifi(val name: String, val password: String)

    /** A Wi-Fi code: "WIFI:T:WPA;S:Home;P:secret;;", where \ escapes ; , : and \ itself. */
    fun wifi(text: String): Wifi? {
        if (!text.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = HashMap<String, String>()
        val part = StringBuilder()
        var key: String? = null
        var i = 5
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    part.append(text[i + 1])
                    i++
                }
                c == ':' && key == null -> {
                    key = part.toString().uppercase()
                    part.setLength(0)
                }
                c == ';' -> {
                    key?.let { fields[it] = part.toString() }
                    key = null
                    part.setLength(0)
                }
                else -> part.append(c)
            }
            i++
        }
        key?.let { fields[it] = part.toString() }
        val name = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
        return Wifi(name, fields["P"].orEmpty())
    }
}
