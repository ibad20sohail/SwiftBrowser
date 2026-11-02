package com.swiftbrowser.adblock

import com.swiftbrowser.core.UrlUtils
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest

/**
 * The browser's own defence against pop-unders and redirect hijacks, which filter lists alone
 * can't stop: pirate sites open ads from a click handler on the whole page.
 *
 * A link the user actually tapped is reported by the helper extension ([Click]); a navigation to
 * another site is trusted only if it matches that link.
 *
 * 3.0: the decisions work on the addresses' text alone (no Android parts), so they are tested on
 * the PC (NavPolicyTest).
 */
object NavPolicy {
    data class Click(val href: String, val time: Long)

    enum class Decision { ALLOW, BLOCK, WAIT }

    /**
     * 3.0: sign-in and payment pages that legitimately open pop-ups and come from other sites:
     * their sign-in addresses only, no longer whole sites (a tap on a page could open any
     * amazon.com, yahoo.com or reddit.com page as a pop-under).
     */
    private val SIGN_IN_HOSTS = setOf(
        "accounts.google.com", "appleid.apple.com", "login.live.com", "login.microsoftonline.com", "login.yahoo.com",
    )

    /** Hosts that only do signing in, on any site: accounts.example.com, login., auth., sso., oauth. */
    private val SIGN_IN_PREFIXES = listOf("accounts.", "login.", "auth.", "sso.", "oauth.")

    /** ".../oauth.../authorize", the address apps send you to for "Sign in with ...". */
    private val OAUTH_PATH = Regex("/oauth[^/]*/(?:[^?#]*/)?authorize", RegexOption.IGNORE_CASE)

    /** Facebook's "Continue with Facebook" (with or without its version: /v19.0/dialog/oauth). */
    private val FACEBOOK_OAUTH = Regex("^/(v[\\d.]+/)?dialog/oauth")

    /** Sites whose outgoing links/redirects are always the user's own choice (search results, etc.). */
    private val TRUSTED_NAVIGATORS = setOf(
        "google.com", "bing.com", "duckduckgo.com", "youtube.com", "facebook.com", "x.com", "twitter.com", "t.co",
        "reddit.com", "wikipedia.org", "yahoo.com", "linkedin.com", "instagram.com", "whatsapp.com", "telegram.org",
        "github.com", "gmail.com",
    )

    private const val CLICK_WINDOW_MS = 4000L
    private const val PENDING_WINDOW_MS = 800L

    /** How long after typing an address (or Back, reload) the page may still send you on by itself. */
    private const val USER_NAV_MS = 3000L

    private fun site(url: String?): String? = UrlUtils.plainHost(url)?.let { UrlUtils.siteOfHost(it) }

    /** 3.0: [url] is a sign-in or payment page (see [SIGN_IN_HOSTS]). */
    fun isSignIn(url: String): Boolean {
        if (!UrlUtils.isHttp(url)) return false
        val host = UrlUtils.plainHost(url) ?: return false
        val path = UrlUtils.plainPath(url).lowercase()
        if (host in SIGN_IN_HOSTS || SIGN_IN_PREFIXES.any { host.startsWith(it) }) return true
        if (OAUTH_PATH.containsMatchIn(path)) return true
        return when (UrlUtils.siteOfHost(host)) {
            "facebook.com" -> path.startsWith("/login") || FACEBOOK_OAUTH.containsMatchIn(path)
            "github.com" -> path.startsWith("/login")
            "x.com", "twitter.com" -> "/oauth" in path
            "amazon.com" -> path.startsWith("/ap/")
            "paypal.com" -> path.startsWith("/signin") || path.startsWith("/checkout") || path.startsWith("/webapps/hermes") ||
                path.startsWith("/connect")
            else -> false
        }
    }

    private fun linkMatch(click: Click?, target: String, now: Long): Boolean {
        if (click == null || now - click.time > CLICK_WINDOW_MS || click.href.isBlank()) return false
        return UrlUtils.stripFragment(click.href) == UrlUtils.stripFragment(target)
    }

    /**
     * 3.0: the tap was on a link to the same page as [target], whatever was added after "?" (a
     * site's own script often adds its tracking): "/movie/42" opening "/movie/42?ref=list". Not a
     * tap anywhere on the page, nor a link to another page ("/out?url=…" redirectors).
     */
    private fun samePageLink(click: Click?, target: String, now: Long): Boolean {
        if (click == null || now - click.time > CLICK_WINDOW_MS || click.href.isBlank()) return false
        fun page(url: String) = url.substringBefore('#').substringBefore('?').trimEnd('/')
        return page(click.href) == page(target)
    }

    /** No click report yet, but one may still be on its way from the page. */
    private fun clickMayBePending(click: Click?, now: Long): Boolean =
        click == null || now - click.time > PENDING_WINDOW_MS

    /** A page wants to open a new window or tab. */
    fun popup(level: AdLevel, req: LoadRequest, click: Click?, now: Long, waited: Boolean): Decision =
        popup(level, req.uri, req.triggerUri, req.hasUserGesture, click, now, waited)

    fun popup(
        level: AdLevel, target: String, trigger: String?, gesture: Boolean, click: Click?, now: Long, waited: Boolean,
    ): Decision = when (level) {
        AdLevel.OFF -> Decision.ALLOW
        AdLevel.BALANCED -> if (gesture) Decision.ALLOW else Decision.BLOCK
        AdLevel.HIGH, AdLevel.EXTREME -> when {
            !gesture -> Decision.BLOCK
            linkMatch(click, target, now) -> Decision.ALLOW
            isSignIn(target) -> Decision.ALLOW
            // 3.0: the site's own pop-up only when a link to that page was tapped.
            level == AdLevel.HIGH && UrlUtils.isHttp(target) && site(target) != null && site(target) == site(trigger) &&
                samePageLink(click, target, now) -> Decision.ALLOW
            !waited && clickMayBePending(click, now) -> Decision.WAIT
            else -> Decision.BLOCK
        }
    }

    /**
     * A page wants to navigate the current tab (top level). [pageLoading]: the tab's page is still
     * loading (3.0: the first page after the user's own navigation, see [navigation] below).
     */
    fun navigation(
        level: AdLevel, req: LoadRequest, click: Click?, now: Long, userNavAt: Long, pageLoading: Boolean, waited: Boolean,
    ): Decision = navigation(
        level, req.uri, req.triggerUri, req.hasUserGesture, req.isDirectNavigation, req.isRedirect,
        click, now, userNavAt, pageLoading, waited,
    )

    fun navigation(
        level: AdLevel, target: String, trigger: String?, gesture: Boolean, direct: Boolean, redirect: Boolean,
        click: Click?, now: Long, userNavAt: Long, pageLoading: Boolean, waited: Boolean,
    ): Decision {
        if (direct || redirect) return Decision.ALLOW
        // Back/forward/reload/typed URL: the page that opens may still send you on while it loads.
        // 3.0: only until it has loaded; pirate pages redirect to an ad as soon as they have.
        if (now - userNavAt < USER_NAV_MS && pageLoading) return Decision.ALLOW
        if (!UrlUtils.isHttp(target) || trigger == null || !UrlUtils.isHttp(trigger)) return Decision.ALLOW
        val triggerSite = site(trigger)
        if (triggerSite != null && triggerSite == site(target)) return Decision.ALLOW
        return when (level) {
            AdLevel.OFF, AdLevel.BALANCED -> Decision.ALLOW
            AdLevel.HIGH, AdLevel.EXTREME -> when {
                triggerSite in TRUSTED_NAVIGATORS -> Decision.ALLOW
                linkMatch(click, target, now) -> Decision.ALLOW
                gesture && isSignIn(target) -> Decision.ALLOW
                gesture && !waited && clickMayBePending(click, now) -> Decision.WAIT
                else -> Decision.BLOCK
            }
        }
    }
}
