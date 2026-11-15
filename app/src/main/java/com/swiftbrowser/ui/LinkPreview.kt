package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.swiftbrowser.R
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import com.swiftbrowser.tabs.TabRules
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * 3.0.11: a held link's page, looked at without making a tab ("Preview"): in a sheet like every
 * other, over the page it came from. The page has the tab's profile (its sign-ins) and is incognito
 * when the tab is; ad blocking reaches it as it does every page. It may follow its own links, but
 * opens no windows or apps, asks nothing, and isn't kept in history. "Open in new tab" takes its
 * page (where it has got to) to a tab; the sheet's own page closes with the sheet.
 */
object LinkPreview {
    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun show(a: Activity, from: Tab, url: String) {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        // Its head, as a held link's sheet has it: the site's icon, then the page's title and site.
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(a, 24), dp(a, 4), dp(a, 24), dp(a, 12))
        }
        val icon = ImageView(a).apply {
            background = Ui.shape(a, Palette.color(a, R.color.surface_alt), Ui.R_SMALL)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val bmp = com.swiftbrowser.core.Favicons.cached(url)
            if (bmp != null) {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val p = dp(a, 8)
                setPadding(p, p, p, p)
            } else {
                setImageDrawable(Ui.icon(a, R.drawable.ic_globe, Palette.color(a, R.color.text_secondary)))
                scaleType = ImageView.ScaleType.CENTER
            }
        }
        head.addView(icon, LinearLayout.LayoutParams(dp(a, Ui.ICON_BOX), dp(a, Ui.ICON_BOX)).apply { marginEnd = dp(a, 14) })
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val title = Ui.text(a, Ui.Type.SUBTITLE, "Preview", lines = 1)
        val site = Ui.text(a, Ui.Type.CAPTION, UrlUtils.siteName(url), lines = 1).apply { setPadding(0, dp(a, 3), 0, 0) }
        texts.addView(title)
        texts.addView(site)
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)

        // The page's loading, as the address bar shows it.
        val progress = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Ui.accent(a))
            isIndeterminate = false
        }
        col.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 3)))

        // The page: most of the screen, leaving room for the head and the button (also on a turned phone).
        val screen = a.resources.displayMetrics.heightPixels
        val pageHeight = minOf((screen * 0.62f).toInt(), screen - dp(a, 190)).coerceAtLeast(dp(a, 160))
        // Drawn into the sheet itself (a texture), not on a surface of its own behind the window: it
        // slides with the sheet, keeps to its shape and isn't shaded with the page behind.
        val gecko = SwiftGeckoView(a).apply { setViewBackend(org.mozilla.geckoview.GeckoView.BACKEND_TEXTURE_VIEW) }
        val pageBox = PageBox(a).apply { addView(gecko, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        col.addView(pageBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pageHeight))

        var shownUrl = url
        lateinit var dialog: BottomSheetDialog
        col.addView(
            Sheets.wideButton(a, "Open in new tab") {
                dialog.dismiss()
                TabManager.newTab(shownUrl, parent = from)
            }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(a, 16) },
        )

        val settings = GeckoSessionSettings.Builder().apply {
            if (!from.incognito) contextId(Profiles.contextId(from.profileId))
        }
            .usePrivateMode(from.incognito)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(true)
            .build()
        settings.setUserAgentOverride(com.swiftbrowser.engine.UserAgent.overrideFor(false))
        val session = GeckoSession(settings)
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url != null && UrlUtils.isHttp(url)) {
                    shownUrl = url
                    site.text = UrlUtils.siteName(url)
                }
            }

            // Its own links, in the sheet; nothing that would leave it (another app, a page of the phone's).
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val uri = request.uri
                return if (UrlUtils.isHttp(uri) || uri == "about:blank" || uri.startsWith("blob:") || uri.startsWith("data:")) GeckoResult.allow() else GeckoResult.deny()
            }

            // No windows: pop-ups and pop-unders stay shut.
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                progress.visibility = View.VISIBLE
                progress.progress = 50
            }

            override fun onProgressChange(session: GeckoSession, value: Int) {
                Motion.progress(progress, value * 10)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                progress.visibility = View.INVISIBLE
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, value: String?) {
                value?.trim()?.takeIf { it.isNotEmpty() }?.let { title.text = it }
            }

            override fun onCrash(session: GeckoSession) = gone()

            override fun onKill(session: GeckoSession) = gone()

            private fun gone() {
                L.w("Link preview: its page process ended")
                if (dialog.isShowing) {
                    dialog.dismiss()
                    Messages.show(a, "The preview stopped working. Open the link in a tab instead.")
                }
            }
        }
        session.open(Engine.runtime)
        // Swift's page colour until the page has drawn (not the engine's own, grey in the dark look).
        gecko.coverUntilFirstPaint(Palette.color(a, if (from.incognito) R.color.incognito_bg else R.color.page_bg))
        gecko.setSession(session)
        session.setActive(true)
        // As a page opened from the address bar: after the ad blocker is ready, if it's still starting.
        AdBlock.whenReady(TabRules.USER_WAIT_MS) { if (session.isOpen) session.loadUri(url) }

        dialog = Sheets.sheet(a, col, scrolls = false)
        pageBox.sheet = dialog
        dialog.setOnDismissListener {
            if (session.isOpen) {
                gecko.releaseSession()
                session.close()
            }
        }
        dialog.showImmersive()
    }

    /**
     * The page's box: a drag on the page scrolls the page; only the sheet's handle and head pull the
     * sheet down (a sheet that took page drags closed as the page was scrolled back up).
     */
    @SuppressLint("ViewConstructor")
    private class PageBox(a: Activity) : FrameLayout(a) {
        var sheet: BottomSheetDialog? = null

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> sheet?.behavior?.isDraggable = false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sheet?.behavior?.isDraggable = true
            }
            return super.dispatchTouchEvent(ev)
        }
    }
}
