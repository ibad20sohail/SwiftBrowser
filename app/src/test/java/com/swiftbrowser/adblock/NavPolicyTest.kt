package com.swiftbrowser.adblock

import com.swiftbrowser.adblock.NavPolicy.Click
import com.swiftbrowser.adblock.NavPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0: the pop-up and redirect rules (NavPolicy) on plain addresses. */
class NavPolicyTest {
    private val now = 1_000_000L
    private val page = "https://pirate.example/movie/42"

    @Test
    fun signInPagesOnly() {
        for (u in listOf(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=x", "https://appleid.apple.com/auth/authorize",
            "https://login.live.com/oauth20_authorize.srf", "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            "https://www.facebook.com/login.php", "https://www.facebook.com/v19.0/dialog/oauth?app_id=1",
            "https://m.facebook.com/dialog/oauth", "https://github.com/login/oauth/authorize?client_id=1",
            "https://x.com/i/oauth2/authorize?x=1", "https://api.twitter.com/oauth/authenticate",
            "https://login.yahoo.com/", "https://www.amazon.com/ap/signin?x=1", "https://www.paypal.com/signin",
            "https://www.paypal.com/checkoutnow?token=1", "https://accounts.spotify.com/authorize",
            "https://auth.example.org/x", "https://sso.company.example/", "https://example.org/oauth2/v1/authorize?x",
            "https://discord.com/oauth2/authorize?client_id=1",
        )) {
            assertTrue(u, NavPolicy.isSignIn(u))
        }
        for (u in listOf(
            "https://www.amazon.com/dp/B000", "https://www.reddit.com/r/movies", "https://www.linkedin.com/feed",
            "https://news.yahoo.com/", "https://www.youtube.com/watch?v=1", "https://www.facebook.com/somepage",
            "https://github.com/someone/repo", "https://www.paypal.com/donate", "https://x.com/someone",
            "https://pirate.example/out?url=https://accounts.google.com", "javascript:alert(1)",
        )) {
            assertFalse(u, NavPolicy.isSignIn(u))
        }
    }

    private fun popup(target: String, click: Click?, level: AdLevel = AdLevel.HIGH, waited: Boolean = true) =
        NavPolicy.popup(level, target, page, gesture = true, click = click, now = now, waited = waited)

    @Test
    fun popUpsOfBigSitesNeedTheirLink() {
        // A tap anywhere on a pirate page opening an Amazon page as a pop-under (2.9 let it through).
        assertEquals(Decision.BLOCK, popup("https://www.amazon.com/dp/B000?tag=ads", Click("", now - 100)))
        assertEquals(Decision.ALLOW, popup("https://accounts.google.com/signin", Click("", now - 100)))
        assertEquals(Decision.ALLOW, popup("https://www.amazon.com/dp/B000", Click("https://www.amazon.com/dp/B000", now - 100)))
    }

    @Test
    fun sameSitePopUpsOnlyFromTheirLink() {
        // The site's own "/out?url=" redirector on a tap anywhere, or on a link to another page.
        assertEquals(Decision.BLOCK, popup("https://pirate.example/out?url=https://ads.example", Click("", now - 100)))
        assertEquals(Decision.BLOCK, popup("https://pirate.example/out?url=https://ads.example", Click("https://pirate.example/movie/43", now - 100)))
        // The tapped link itself, with what the site's script added.
        assertEquals(Decision.ALLOW, popup("https://pirate.example/movie/43?ref=list", Click("https://pirate.example/movie/43", now - 100)))
        // An old tap doesn't count.
        assertEquals(Decision.BLOCK, popup("https://pirate.example/movie/43?ref=list", Click("https://pirate.example/movie/43", now - 10_000)))
        // Balanced lets any tapped pop-up through, as before.
        assertEquals(Decision.ALLOW, popup("https://pirate.example/out?url=x", Click("", now - 100), level = AdLevel.BALANCED))
        // No tap at all: always blocked.
        assertEquals(Decision.BLOCK, NavPolicy.popup(AdLevel.BALANCED, "https://a.example", page, false, null, now, true))
    }

    @Test
    fun clickReportMayStillBeOnItsWay() {
        assertEquals(Decision.WAIT, popup("https://ads.example/x", null, waited = false))
        assertEquals(Decision.BLOCK, popup("https://ads.example/x", null, waited = true))
    }

    private fun redirect(
        target: String, userNavAt: Long, pageLoading: Boolean, gesture: Boolean = false, click: Click? = null, trigger: String = page,
    ) = NavPolicy.navigation(AdLevel.HIGH, target, trigger, gesture, false, false, click, now, userNavAt, pageLoading, true)

    @Test
    fun redirectsJustAfterTypingOnlyWhileThePageLoads() {
        // Typed (or reloaded) a moment ago and the page is still loading: its own redirect is fine.
        assertEquals(Decision.ALLOW, redirect("https://other.example/", userNavAt = now - 1000, pageLoading = true))
        // The page has loaded, then sends you to an ad within the 3 seconds: blocked (2.9 let it).
        assertEquals(Decision.BLOCK, redirect("https://ads.example/", userNavAt = now - 1000, pageLoading = false))
        // Long after typing: the usual rules.
        assertEquals(Decision.BLOCK, redirect("https://ads.example/", userNavAt = now - 60_000, pageLoading = true))
    }

    @Test
    fun otherRedirectRules() {
        // Typed addresses and the server's own redirects.
        assertEquals(Decision.ALLOW, NavPolicy.navigation(AdLevel.HIGH, "https://ads.example/", page, false, true, false, null, now, 0, false, true))
        assertEquals(Decision.ALLOW, NavPolicy.navigation(AdLevel.HIGH, "https://ads.example/", page, false, false, true, null, now, 0, false, true))
        // The same site, and from search results.
        assertEquals(Decision.ALLOW, redirect("https://www.pirate.example/next", userNavAt = 0, pageLoading = false))
        assertEquals(Decision.ALLOW, redirect("https://any.example/", 0, false, trigger = "https://www.google.com/search?q=x"))
        // The link that was tapped.
        assertEquals(Decision.ALLOW, redirect("https://other.example/a", 0, false, gesture = true, click = Click("https://other.example/a", now - 50)))
        // A sign-in page after a tap; not without one.
        assertEquals(Decision.ALLOW, redirect("https://accounts.google.com/signin", 0, false, gesture = true, click = Click("", now - 50)))
        assertEquals(Decision.BLOCK, redirect("https://accounts.google.com/signin", 0, false, gesture = false))
        // Balanced doesn't stop redirects.
        assertEquals(
            Decision.ALLOW,
            NavPolicy.navigation(AdLevel.BALANCED, "https://ads.example/", page, false, false, false, null, now, 0, false, true),
        )
    }
}
