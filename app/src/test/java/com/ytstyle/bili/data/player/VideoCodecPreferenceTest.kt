package com.ytstyle.bili.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec ordering is the fix for "some videos only play after switching quality":
 * the same quality tier is served as AVC, HEVC and AV1, and the old
 * highest-bandwidth pick landed on HEVC almost every time.
 */
class VideoCodecPreferenceTest {

    @Test
    fun `h264 is ranked before hevc and av1`() {
        assertEquals(0, VideoCapabilities.rank("avc1.640033"))
        assertEquals(1, VideoCapabilities.rank("hvc1.1.6.L120.90"))
        assertEquals(2, VideoCapabilities.rank("av01.0.08M.08.0.110.01.01.01.0"))
    }

    @Test
    fun `unknown and empty codecs sort last`() {
        assertEquals(3, VideoCapabilities.rank("vp09.00.10.08"))
        assertEquals(3, VideoCapabilities.rank(""))
        assertEquals(3, VideoCapabilities.rank("something-new"))
    }

    /** The picker sorts by `rank` ascending, so AVC must win a mixed pool. */
    @Test
    fun `avc first matches an ascending sort of a mixed pool`() {
        val pool = listOf(
            "hvc1.1.6.L120.90",
            "av01.0.08M.08",
            "avc1.640033",
        ).sortedBy { VideoCapabilities.rank(it) }
        assertTrue(pool.first().startsWith("avc1"))
    }
}
