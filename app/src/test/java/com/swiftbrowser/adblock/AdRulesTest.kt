package com.swiftbrowser.adblock

import com.swiftbrowser.core.IncognitoChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdRulesTest {
    private fun lines(g: AdLevel, overrides: Map<String, AdLevel>) = AdRules.dynamicRules(g, overrides).lines()

    @Test
    fun uboOwnRequestsAreNeverBlocked() {
        for (g in AdLevel.entries) assertTrue(lines(g, emptyMap()).contains("behind-the-scene * * noop"))
    }

    @Test
    fun extremeBlocksThirdPartyScriptsExceptOnSignInSitesAndSitesSetLower() {
        val rules = lines(AdLevel.EXTREME, mapOf("film.site" to AdLevel.HIGH, "strict.site" to AdLevel.EXTREME))
        assertTrue(rules.contains("* * 3p-script block"))
        assertTrue(rules.contains("* * 3p-frame block"))
        assertTrue(rules.contains("google.com * 3p-script noop"))
        assertTrue(rules.contains("film.site * 3p-script noop"))
        assertTrue(rules.contains("film.site * 3p-frame noop"))
        assertFalse(rules.any { it.startsWith("strict.site ") })
    }

    @Test
    fun oneSiteAtExtremeBlocksOnlyThere() {
        val rules = lines(AdLevel.HIGH, mapOf("strict.site" to AdLevel.EXTREME, "calm.site" to AdLevel.BALANCED))
        assertFalse(rules.contains("* * 3p-script block"))
        assertTrue(rules.contains("strict.site * 3p-script block"))
        assertTrue(rules.contains("strict.site * 3p-frame block"))
        assertFalse(rules.any { it.startsWith("calm.site ") })
    }

    @Test
    fun sitesSetToOffAreTrusted() {
        assertEquals(listOf("free.site"), AdRules.trusted(AdLevel.HIGH, mapOf("free.site" to AdLevel.OFF, "strict.site" to AdLevel.EXTREME)))
        assertEquals(emptyList<String>(), AdRules.trusted(AdLevel.BALANCED, emptyMap()))
    }

    @Test
    fun withBlockingOffEverythingIsTrustedButTheSitesTurnedOn() {
        assertEquals(listOf("/^/"), AdRules.trusted(AdLevel.OFF, mapOf("free.site" to AdLevel.OFF)))
        val directive = AdRules.trusted(AdLevel.OFF, mapOf("news.com" to AdLevel.HIGH)).single()
        // The JS RegExp between its slashes reads the same as a Java one here.
        val trusted = Regex(directive.removePrefix("/").removeSuffix("/"))
        assertFalse(trusted.containsMatchIn("https://news.com/today"))
        assertFalse(trusted.containsMatchIn("https://www.news.com:8080/today"))
        assertTrue(trusted.containsMatchIn("https://other.org/"))
        assertTrue(trusted.containsMatchIn("https://othernews.com/"))
    }

    @Test
    fun levelsChosenFromIncognitoAreUsedButNotKept() {
        val saved = mapOf("strict.site" to AdLevel.EXTREME)
        val incognito = IncognitoChoices<AdLevel>()
        incognito.set("free.site", AdLevel.OFF)
        incognito.set("strict.site", null)
        val inForce = incognito.applyTo(saved)
        // In use: free.site unfiltered, strict.site back to the level for all sites.
        assertEquals(listOf("free.site"), AdRules.trusted(AdLevel.HIGH, inForce))
        assertFalse(AdRules.dynamicRules(AdLevel.HIGH, inForce).contains("strict.site"))
        // Kept (what uBO may write down): only the saved levels.
        assertEquals(emptyList<String>(), AdRules.trusted(AdLevel.HIGH, saved))
        assertTrue(AdRules.dynamicRules(AdLevel.HIGH, saved).contains("strict.site * 3p-script block"))
    }
}
