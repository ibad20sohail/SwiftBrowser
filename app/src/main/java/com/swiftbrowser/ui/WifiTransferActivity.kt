package com.swiftbrowser.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.files.FilesSettings
import com.swiftbrowser.files.WifiServer
import java.util.Locale

/**
 * Wi-Fi transfer (2.9), from the Files start page or its ⋮: the phone's storage on a computer on
 * the same Wi-Fi, through a small web server (WifiServer) that runs only while this screen is open.
 * The screen stays on meanwhile; it shows the address and the code to type, whether a computer is
 * connected and what is going to and fro. Leaving the screen stops it. Without Wi-Fi (or the
 * phone's own hotspot) it says what to do, and starts by itself once the phone is connected.
 */
class WifiTransferActivity : ListActivity() {
    private var server: WifiServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var statusText: TextView? = null
    private var activityText: TextView? = null
    private var codeText: TextView? = null
    private var shownCode = ""

    private fun dp(v: Int) = Ui.dp(this, v)

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { begin() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "Wi-Fi transfer"
        list.visibility = View.GONE
        // The phone mustn't sleep while the computer is using it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Turned sideways: the same server, address and code.
        server = kept
        wifiLock = keptLock
        kept = null
        keptLock = null
    }

    override fun onStart() {
        super.onStart()
        watchNetwork(true)
        begin()
    }

    override fun onStop() {
        // Only while the screen is open (turned sideways, it goes on).
        if (!isChangingConfigurations) {
            watchNetwork(false)
            end()
        }
        super.onStop()
    }

    override fun onDestroy() {
        list.removeCallbacks(tick)
        watchNetwork(false)
        if (isChangingConfigurations && server != null) {
            kept = server
            keptLock = wifiLock
            server = null
            wifiLock = null
        } else {
            end()
        }
        super.onDestroy()
    }

    private fun hasAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT > 28 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }

    /** Starts the server when everything is ready, or shows what is missing. */
    private fun begin() {
        if (server?.running == true) {
            showRunning()
            return
        }
        if (!hasAccess()) {
            showProblem(
                R.drawable.ic_folder, "Allow access to your files",
                "Wi-Fi transfer shows your phone's folders on your computer, and saves what you send from it. Swift needs access to your files for that.",
                "Allow access",
            ) { requestAccess() }
            return
        }
        val address = WifiServer.wifiAddress(this)
        if (address == null) {
            showProblem(
                R.drawable.ic_wifi, "Connect to Wi-Fi",
                "Wi-Fi transfer works when your phone and your computer are on the same Wi-Fi. Connect the phone to Wi-Fi (or turn on its hotspot and connect the computer to it). It starts by itself once the phone is connected.",
                "Wi-Fi settings",
            ) { runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } }
            return
        }
        // The page for the computer, in the phone's colours (worked out here: the server's threads don't touch the screen).
        val accent = css(Ui.accent(this))
        val onAccent = css(Ui.onAccent(this))
        val assets = applicationContext.assets
        val s = WifiServer(applicationContext, address) { name ->
            val text = synchronized(pages) {
                pages.getOrPut(name) { runCatching { assets.open("transfer/$name.html").bufferedReader().use { it.readText() } }.getOrDefault("") }
            }
            text.replace("{{ACCENT}}", accent).replace("{{ON_ACCENT}}", onAccent)
        }
        if (!s.start(FilesSettings.wifiPort)) {
            showProblem(R.drawable.ic_error, "Swift couldn't start the transfer", "Something on the phone is in the way. Try again in a moment.", "Try again") { begin() }
            return
        }
        server = s
        holdWifi(true)
        showRunning()
    }

    private fun end() {
        list.removeCallbacks(tick)
        server?.stop()
        server = null
        holdWifi(false)
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** Full Wi-Fi speed while sending (Android may slow Wi-Fi down to save the battery otherwise). */
    @Suppress("DEPRECATION")
    private fun holdWifi(on: Boolean) {
        try {
            if (on) {
                if (wifiLock == null) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "swift:wifi-transfer").apply { setReferenceCounted(false) }
                }
                wifiLock?.acquire()
            } else {
                wifiLock?.takeIf { it.isHeld }?.release()
            }
        } catch (e: Exception) {
            L.w("Wi-Fi transfer: no Wi-Fi lock (${e.javaClass.simpleName})")
        }
    }

    /** Wi-Fi coming or going: the server starts once there is Wi-Fi, and stops (or moves) when it goes. */
    private fun watchNetwork(on: Boolean) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (!on) {
            networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            networkCallback = null
            return
        }
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = recheck()
            override fun onLost(network: Network) = recheck()
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
            networkCallback = cb
        } catch (e: Exception) {
            L.w("Wi-Fi transfer: can't watch the network (${e.javaClass.simpleName})")
        }
    }

    private fun recheck() {
        list.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val now = WifiServer.wifiAddress(this)
            val s = server
            if (s != null && s.address != now) end()
            if (server == null) begin()
        }, 600)
    }

    private fun css(c: Int) = String.format(Locale.ROOT, "#%06X", c and 0xFFFFFF)

    // ------------------------------------------------------------------ the screen

    private fun showProblem(icon: Int, title: String, text: String, button: String, onClick: () -> Unit) {
        list.removeCallbacks(tick)
        toolbar.subtitle = null
        empty.removeAllViews()
        val state = Ui.emptyState(this, icon, title, text)
        state.addView(MaterialButton(this).apply {
            this.text = button
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        Palette.paint(state)
        empty.addView(ScrollView(this).apply { addView(state) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        empty.visibility = View.VISIBLE
        statusText = null
        activityText = null
        codeText = null
    }

    private fun spaced(code: String) = code.take(3) + " " + code.drop(3)

    private fun showRunning() {
        val s = server ?: return
        toolbar.subtitle = "Stops when you leave this screen"
        empty.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = Ui.shape(this@WifiTransferActivity, Palette.color(this@WifiTransferActivity, R.color.card_bg), Ui.R_LARGE, Palette.color(this@WifiTransferActivity, R.color.outline))
        }
        card.addView(Ui.iconBadge(this, R.drawable.ic_files_transfer, 56))
        card.addView(centered(Ui.text(this, Ui.Type.BODY_SECONDARY, "On your computer, open"), 16))
        card.addView(centered(Ui.text(this, Ui.Type.HEADLINE, s.url, Ui.accent(this)).apply {
            setTextIsSelectable(true)
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, 4))
        card.addView(centered(Ui.text(this, Ui.Type.BODY_SECONDARY, "and enter this code"), 16))
        val code = Ui.text(this, Ui.Type.HEADLINE, spaced(s.code)).apply {
            textSize = 34f
            letterSpacing = 0.12f
            contentDescription = s.code.toCharArray().joinToString(" ")
        }
        card.addView(centered(code, 4))
        codeText = code
        shownCode = s.code
        col.addView(card)

        val status = Ui.text(this, Ui.Type.SUBTITLE, "").apply { setPadding(dp(8), dp(20), dp(8), 0) }
        val activity = Ui.text(this, Ui.Type.BODY_SECONDARY, "").apply { setPadding(dp(8), dp(4), dp(8), 0) }
        col.addView(status)
        col.addView(activity)
        statusText = status
        activityText = activity

        col.addView(Sheets.label(this, "Good to know", 16).apply { setPadding(dp(8), dp(20), dp(8), dp(6)) })
        for (line in listOf(
            "Your phone and computer must be on the same Wi-Fi.",
            "This screen stays on while it is open. The transfer stops when you leave it.",
            "The computer sees your phone's storage and SD card. Swift's own files and your Private files aren't shared.",
            "Anyone with the address and the code can reach your files while this screen is open: only give them to people you trust.",
        )) {
            col.addView(Ui.text(this, Ui.Type.BODY_SECONDARY, "•  $line").apply { setPadding(dp(8), dp(4), dp(8), dp(4)) })
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "New code"
            setOnClickListener {
                server?.renewCode()
                updateStatus()
                Messages.show(this@WifiTransferActivity, "A new code: the computer needs to enter it again")
            }
        })
        buttons.addView(MaterialButton(this).apply {
            text = "Stop"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        col.addView(buttons)
        Palette.paint(col)
        empty.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty.visibility = View.VISIBLE
        updateStatus()
        list.removeCallbacks(tick)
        list.postDelayed(tick, 500)
    }

    private fun centered(v: TextView, top: Int): TextView = v.apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(top), 0, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (server?.running != true) return
            updateStatus()
            list.postDelayed(this, 500)
        }
    }

    private fun updateStatus() {
        val s = server ?: return
        // Ten wrong codes in a row make a new one.
        if (s.code != shownCode) {
            shownCode = s.code
            codeText?.text = spaced(s.code)
            codeText?.contentDescription = s.code.toCharArray().joinToString(" ")
        }
        val sending = s.sending.get()
        val receiving = s.receiving.get()
        statusText?.text = when {
            receiving > 0 -> "Receiving from your computer…"
            sending > 0 -> "Sending to your computer…"
            s.signedIn -> "Your computer is connected"
            else -> "Waiting for your computer…"
        }
        val parts = ArrayList<String>(3)
        val received = s.bytesReceived.get()
        val sent = s.bytesSent.get()
        val files = s.filesReceived.get()
        if (files > 0) parts += if (files == 1) "1 file received" else "$files files received"
        if (received > 0) parts += UrlUtils.formatBytes(received) + " in"
        if (sent > 0) parts += UrlUtils.formatBytes(sent) + " out"
        activityText?.text = if (parts.isEmpty()) "Nothing sent yet." else parts.joinToString(" · ")
    }

    companion object {
        /** The pages for the computer, read once from Swift's assets. */
        private val pages = HashMap<String, String>()

        /** The server while the screen is made again (turned sideways). */
        private var kept: WifiServer? = null
        private var keptLock: WifiManager.WifiLock? = null

        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, WifiTransferActivity::class.java))
        }
    }
}
