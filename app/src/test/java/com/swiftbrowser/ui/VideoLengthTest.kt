package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3.0.11: a video's length on its picture in Files (VideoLength). */
class VideoLengthTest {
    @Test
    fun minutesAndSecondsThenHours() {
        assertEquals("0:01", VideoLength.text(1_000))
        assertEquals("3:05", VideoLength.text(185_400))
        assertEquals("59:59", VideoLength.text(3_599_999))
        assertEquals("1:00:00", VideoLength.text(3_600_000))
        assertEquals("1:42:10", VideoLength.text(6_130_000))
    }

    @Test
    fun nothingUnderASecondOrNotKnown() {
        assertNull(VideoLength.text(0))
        assertNull(VideoLength.text(999))
        assertNull(VideoLength.text(-5))
    }
}
