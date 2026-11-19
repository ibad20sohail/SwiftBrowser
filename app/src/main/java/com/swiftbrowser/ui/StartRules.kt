package com.swiftbrowser.ui

/**
 * 3.0.13: whether Swift's start screen (the logo) shows as it starts. Only when it opens on the home
 * page: opening on a site shows the browser at once, its page waiting for the ad blocker under Swift's
 * own colour (it used to show the start screen and then a loader for as long as that took, 2-12 s and
 * once over a minute in a problem report). Plain code, tested on the PC (StartRulesTest).
 */
object StartRules {
    /**
     * [link]: another app hands Swift a web address or a search (it opens as a page); [newTab]: a
     * shortcut, the widget or a tile asks for a new tab or a search (the home page); [tabIsSite]: the
     * tab Swift opens on shows a site (null: there is none, and a new one shows the home page).
     */
    fun startScreen(link: Boolean, newTab: Boolean, tabIsSite: Boolean?): Boolean = when {
        link -> false
        newTab -> true
        else -> tabIsSite != true
    }
}
