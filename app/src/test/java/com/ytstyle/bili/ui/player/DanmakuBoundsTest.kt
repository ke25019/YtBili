package com.ytstyle.bili.ui.player

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the rectangle danmaku are drawn inside.
 *
 * Bilibili's own client keeps comments on the picture. Drawing them across the
 * whole container put them on the black letterbox bars, which is the bug this
 * covers: the container and the video are frequently different shapes, both in
 * the 16:9 strip and in fullscreen on a 20:9 phone.
 */
class DanmakuBoundsTest {

    private val epsilon = 0.01f

    /** A 16:9 video in a 16:9 container fills it exactly. */
    @Test
    fun `matching aspect fills the container`() {
        val rect = fittedVideoRect(Size(1600f, 900f), 16f / 9f)
        assertEquals(0f, rect.left, epsilon)
        assertEquals(0f, rect.top, epsilon)
        assertEquals(1600f, rect.width, epsilon)
        assertEquals(900f, rect.height, epsilon)
    }

    /**
     * A 20:9 phone in landscape is *wider* than 16:9, so the video is
     * pillarboxed: full height, with bars left and right that comments must not
     * spill onto.
     */
    @Test
    fun `pillarboxes a container wider than the video`() {
        val rect = fittedVideoRect(Size(2400f, 1080f), 16f / 9f)
        assertEquals("height fills", 0f, rect.top, epsilon)
        assertEquals(1080f, rect.height, epsilon)
        assertEquals("width is 1080 * 16/9", 1920f, rect.width, epsilon)
        assertEquals("centred horizontally", 240f, rect.left, epsilon)
    }

    /**
     * A portrait container is *narrower* than 16:9, so the video is
     * letterboxed: full width, bars above and below.
     */
    @Test
    fun `letterboxes a container narrower than the video`() {
        val rect = fittedVideoRect(Size(1080f, 1920f), 16f / 9f)
        assertEquals("width fills", 1080f, rect.width, epsilon)
        assertEquals("height is 1080 / (16/9)", 607.5f, rect.height, epsilon)
        // Centred vertically: (1920 - 607.5) / 2
        assertEquals(656.25f, rect.top, epsilon)
    }

    /** Before the first frame the aspect is unknown, so the whole box is used. */
    @Test
    fun `falls back to the whole area without an aspect`() {
        val rect = fittedVideoRect(Size(1000f, 500f), null)
        assertEquals(1000f, rect.width, epsilon)
        assertEquals(500f, rect.height, epsilon)
    }

    @Test
    fun `ignores a nonsensical aspect`() {
        val rect = fittedVideoRect(Size(1000f, 500f), 0f)
        assertEquals(1000f, rect.width, epsilon)
        val negative = fittedVideoRect(Size(1000f, 500f), -2f)
        assertEquals(1000f, negative.width, epsilon)
    }

    /** A degenerate container must not produce a negative-size rectangle. */
    @Test
    fun `handles zero size container`() {
        val rect = fittedVideoRect(Size(0f, 0f), 16f / 9f)
        assertTrue("width must not be negative", rect.width >= 0f)
        assertTrue("height must not be negative", rect.height >= 0f)
    }

    /**
     * The rectangle must always sit inside the container, which is what stops
     * comments appearing on the letterbox bars.
     */
    @Test
    fun `rectangle always fits inside the container`() {
        val containers = listOf(Size(1600f, 900f), Size(2400f, 1080f), Size(1080f, 1920f), Size(800f, 800f))
        val aspects = listOf(16f / 9f, 4f / 3f, 1f, 9f / 16f, 2.39f)
        containers.forEach { size ->
            aspects.forEach { aspect ->
                val rect = fittedVideoRect(size, aspect)
                assertTrue("left >= 0 for $size @ $aspect", rect.left >= -epsilon)
                assertTrue("top >= 0 for $size @ $aspect", rect.top >= -epsilon)
                assertTrue("right inside for $size @ $aspect", rect.right <= size.width + epsilon)
                assertTrue("bottom inside for $size @ $aspect", rect.bottom <= size.height + epsilon)
            }
        }
    }
}
