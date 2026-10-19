package com.swiftbrowser.adblock

import com.swiftbrowser.core.UrlUtils
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest

/**
 * The browser's own defence against pop-unders and redirect hijacks, which filter lists alone
 * can't stop: pirate sites open ads from a click handler on the whole page.
 *
 * A link the user actually tapped is reported by the helper extension ([Click]); a navigation to
 * another site is trusted only if it matches that link.
 */
object NavPolicy {
    data class Click(val href: String, val time: Long)

    enum class Decision { ALLOW, BLOCK, WAIT }

    /** Sign-in and payment providers that legitimately open pop-ups and cross-site pages. */
    private val AUTH_SITES = setOf(
        "google.com", "youtube.com", "gmail.com", "apple.com", "facebook.com", "microsoftonline.com", "live.com",
        "microsoft.com", "twitter.com", "x.com", "github.com", "paypal.com", "yahoo.com", "linkedin.com",
        "discord.com", "reddit.com", "amazon.com",
    )

    /** Sites whose outgoing links/redirects are always the user's own choice (search results, etc.). */
    private val TRUSTED_NAVIGATORS = setOf(
        "google.com", "bing.com", "duckduckgo.com", "youtube.com", "facebook.com", "x.com", "twitter.com", "t.co",
        "reddit.com", "wikipedia.org", "yahoo.com", "linkedin.com", "instagram.com", "whatsapp.com", "telegram.org",
        "github.com", "gmail.com",
    )

    private const val CLICK_WINDOW_MS = 4000L
    private const val PENDING_WINDOW_MS = 800L

    private fun linkMatch(click: Click?, target: String, now: Long): Boolean {
        if (click == null || now - click.time > CLICK_WINDOW_MS || click.href.isBlank()) return false
        return UrlUtils.stripFragment(click.href) == UrlUtils.stripFragment(target)
    }

    /** No click report yet, but one may still be on its way from the page. */
    private fun clickMayBePending(click: Click?, now: Long): Boolean =
        click == null || now - click.time > PENDING_WINDOW_MS

    /** A page wants to open a new window or tab. */
    fun popup(level: AdLevel, req: LoadRequest, click: Click?, now: Long, waited: Boolean): Decision {
        val target = req.uri
        val gesture = req.hasUserGesture
        return when (level) {
            AdLevel.OFF -> Decision.ALLOW
            AdLevel.BALANCED -> if (gesture) Decision.ALLOW else Decision.BLOCK
            AdLevel.HIGH, AdLevel.EXTREME -> when {
                !gesture -> Decision.BLOCK
                linkMatch(click, target, now) -> Decision.ALLOW
                UrlUtils.site(target) in AUTH_SITES -> Decision.ALLOW
                level == AdLevel.HIGH && UrlUtils.isHttp(target) && UrlUtils.sameSite(target, req.triggerUri) ->
                    Decision.ALLOW
                !waited && clickMayBePending(click, now) -> Decision.WAIT
                else -> Decision.BLOCK
            }
        }
    }

    /** A page wants to navigate the current tab (top level). */
    fun navigation(
        level: AdLevel, req: LoadRequest, click: Click?, now: Long, userNavAt: Long, waited: Boolean,
    ): Decision {
        if (req.isDirectNavigation || req.isRedirect) return Decision.ALLOW
        if (now - userNavAt < 3000) return Decision.ALLOW // back/forward/reload/typed URL
        val target = req.uri
        val trigger = req.triggerUri
        if (!UrlUtils.isHttp(target) || trigger == null || !UrlUtils.isHttp(trigger)) return Decision.ALLOW
        if (UrlUtils.sameSite(target, trigger)) return Decision.ALLOW
        return when (level) {
            AdLevel.OFF, AdLevel.BALANCED -> Decision.ALLOW
            AdLevel.HIGH, AdLevel.EXTREME -> when {
                UrlUtils.site(trigger) in TRUSTED_NAVIGATORS -> Decision.ALLOW
                linkMatch(click, target, now) -> Decision.ALLOW
                req.hasUserGesture && UrlUtils.site(target) in AUTH_SITES -> Decision.ALLOW
                req.hasUserGesture && !waited && clickMayBePending(click, now) -> Decision.WAIT
                else -> Decision.BLOCK
            }
        }
    }
}
