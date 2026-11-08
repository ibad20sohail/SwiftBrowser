package com.swiftbrowser.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NewsPageTest {
    private val decode: (String) -> ByteArray? = { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    private fun packed(url: String) = Base64.getEncoder().encodeToString("[null,null,\"$url\"]".toByteArray())

    /** A story as Google News' page writes it (cut down to what Swift reads). */
    private fun block(
        title: String,
        picture: String? = "CC8iABC_-1",
        link: String? = "https://www.ft.com/content/one?a\\u003d1",
        source: String = "Financial Times",
    ) = buildString {
        append("<a class=\"VDXfz\" jsname=\"hXwDdf\" ")
        if (link != null) append("jslog=\"95014; 5:${packed(link)}; track:click,vis\" ")
        append("href=\"./read/CBMi1?hl=en-PK&amp;gl=PK\" aria-hidden=\"true\"></a>")
        if (picture != null) append("<figure><img srcset=\"x\" src=\"/api/attachments/$picture=-w350-h175-p-df\"/></figure>")
        append("<img src=\"https://encrypted-tbn2.gstatic.com/faviconV2?url=https://www.ft.com&amp;client=NEWS_360\"/>")
        append("<a class=\"wEwyrc\" data-n-tid=\"9\">$source</a>")
        append("<h4><a href=\"./read/CBMi1?hl=en-PK&amp;gl=PK\" data-n-tid=\"29\" aria-label=\"x\">$title</a></h4>")
        append("<time class=\"WW6dff\" datetime=\"2026-11-08T12:16:02Z\">3 h</time>")
    }

    private fun stories(page: String, max: Int = 60) = NewsPage.stories(page.toByteArray(), max, decode)

    @Test
    fun aStoryHasItsHeadlineSiteTimePictureAndRealAddress() {
        val s = stories("<html>" + block("Saudi &amp; friends&#39; talks") + "</html>").single()
        assertEquals("Saudi & friends' talks", s.title)
        assertEquals("Financial Times", s.source)
        assertEquals("https://www.ft.com", s.sourceUrl)
        assertEquals("https://www.ft.com/content/one?a=1", s.url)
        assertEquals("https://news.google.com/api/attachments/CC8iABC_-1=-w640-h360-p-df-rw", s.image)
        assertEquals(1794140162000L, s.time)
    }

    @Test
    fun withoutItsPackedAddressAStoryOpensThroughGoogle() {
        assertEquals("https://news.google.com/read/CBMi1?hl=en-PK&gl=PK", stories(block("One", link = null)).single().url)
    }

    @Test
    fun storiesWithoutAPictureAreLeftOut() {
        val list = stories(block("One") + block("No picture", picture = null) + block("Two"))
        assertEquals(listOf("One", "Two"), list.map { it.title })
    }

    @Test
    fun theSameStoryTwiceIsOneAndTheNumberIsLimited() {
        assertEquals(1, stories(block("One") + block("One")).size)
        assertEquals(2, stories(block("One") + block("Two") + block("Three"), max = 2).size)
    }

    @Test
    fun aPageWithoutStoriesGivesNothing() {
        assertTrue(stories("<html><body>Before you continue to Google</body></html>").isEmpty())
        assertTrue(stories("").isEmpty())
    }

    @Test
    fun anUnreadableAddressDoesNotLoseTheStory() {
        val page = block("One").replace(Regex("5:[A-Za-z0-9+/=]+;"), "5:%%%;")
        assertEquals("https://news.google.com/read/CBMi1?hl=en-PK&gl=PK", stories(page).single().url)
    }

    @Test
    fun entitiesBecomeLettersAndNonsenseStaysAsItIs() {
        assertEquals("a & b < c ’ ' \"x\"", NewsPage.unescape("a &amp; b &lt; c &#x2019; &#39; &quot;x&quot;"))
        assertEquals("&#99999999; &nope;", NewsPage.unescape("&#99999999; &nope;"))
    }
}
