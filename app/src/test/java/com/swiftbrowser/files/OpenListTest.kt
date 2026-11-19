package com.swiftbrowser.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OpenListTest {
    private fun items(n: Int) = (0 until n).map { OpenList.Item("/p/$it.jpg", "$it.jpg", it.toLong(), 10L) }

    @Test
    fun handedOverByToken() {
        val list = items(3)
        val token = OpenList.put("Pictures", list)
        val got = OpenList.get(token)!!
        assertEquals("Pictures", got.title)
        assertSame(list, got.items)
        OpenList.drop(token)
        assertNull(OpenList.get(token))
        assertNull(OpenList.get(null))
    }

    @Test
    fun onlyTheLastFewAreKept() {
        val first = OpenList.put("a", items(1))
        repeat(4) { OpenList.put("b$it", items(1)) }
        assertNull(OpenList.get(first))
    }

    @Test
    fun aroundTheTappedFile() {
        val (before, after) = OpenList.around(items(10), "/p/4.jpg", 2)!!
        assertEquals(listOf("/p/2.jpg", "/p/3.jpg"), before.map { it.path })
        assertEquals(listOf("/p/5.jpg", "/p/6.jpg"), after.map { it.path })
    }

    @Test
    fun aroundAtTheEnds() {
        val (before, after) = OpenList.around(items(3), "/p/0.jpg", 500)!!
        assertEquals(0, before.size)
        assertEquals(2, after.size)
        val (b2, a2) = OpenList.around(items(3), "/p/2.jpg", 500)!!
        assertEquals(2, b2.size)
        assertEquals(0, a2.size)
    }

    @Test
    fun notInTheList() {
        assertNull(OpenList.around(items(3), "/elsewhere.jpg", 5))
    }
}
