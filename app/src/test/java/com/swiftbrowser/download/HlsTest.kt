package com.swiftbrowser.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsTest {
    private val base = "https://cdn.example.com/show/master.m3u8"

    private val withSound = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720,AUDIO="aud"
        video/720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=640000,RESOLUTION=640x360,AUDIO="aud"
        video/360.m3u8
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Urdu",LANGUAGE="ur",AUTOSELECT=YES,URI="audio/ur.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English (described)",LANGUAGE="en",CHARACTERISTICS="public.accessibility.describes-video",URI="audio/en-ad.m3u8"
    """.trimIndent()

    @Test
    fun qualitiesWithSeparateSoundGetTheirSoundTrack() {
        val variants = Hls.parseMaster(withSound, base, "en")
        assertEquals(2, variants.size)
        assertEquals("https://cdn.example.com/show/video/720.m3u8", variants[0].url)
        // The EXT-X-MEDIA lines came after the qualities: matched all the same, the default one.
        assertEquals("https://cdn.example.com/show/audio/en.m3u8", variants[0].audio)
        assertEquals("https://cdn.example.com/show/audio/en.m3u8", variants[1].audio)
    }

    @Test
    fun thePhonesLanguageWinsOverTheDefault() {
        val variants = Hls.parseMaster(withSound, base, "ur")
        assertEquals("https://cdn.example.com/show/audio/ur.m3u8", variants[0].audio)
    }

    @Test
    fun soundInsideTheVideoNeedsNoSeparateTrack() {
        val muxed = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Main",DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480,AUDIO="a"
            480.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360
            360.m3u8
        """.trimIndent()
        val variants = Hls.parseMaster(muxed, base, "en")
        assertEquals(2, variants.size)
        assertNull(variants[0].audio)
        assertNull(variants[1].audio)
    }

    @Test
    fun anAudioDescriptionIsTakenOnlyWhenAlone() {
        val r = listOf(
            Hls.Rendition("g", "AD", "en", "https://x/ad.m3u8", default = true, autoselect = true, describes = true),
            Hls.Rendition("g", "Main", "en", "https://x/main.m3u8", default = false, autoselect = false, describes = false),
        )
        assertEquals("https://x/main.m3u8", Hls.soundOf(r, "en"))
        assertEquals("https://x/ad.m3u8", Hls.soundOf(r.take(1), "en"))
    }

    @Test
    fun packedAudioLosesItsId3Tags() {
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x02, 0x1F, 0xFC.toByte(), 1, 2, 3)
        // An ID3v2.4 tag holding 10 bytes (its size "syncsafe"), then the audio frames.
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 10) + ByteArray(10) { 7 }
        assertArrayEquals(adts, Hls.stripId3(tag + adts))
        assertArrayEquals(adts, Hls.stripId3(adts))
    }

    @Test
    fun soundFilesGetTheEndingOfWhatTheyAre() {
        val ts = ByteArray(400).also {
            it[0] = 0x47
            it[188] = 0x47
        }
        assertEquals("ts", Hls.soundExtension(ts, fragmented = false))
        assertEquals("aac", Hls.soundExtension(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50), fragmented = false))
        assertEquals("mp3", Hls.soundExtension(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte()), fragmented = false))
        assertEquals("m4a", Hls.soundExtension(ByteArray(8), fragmented = true))
    }
}
