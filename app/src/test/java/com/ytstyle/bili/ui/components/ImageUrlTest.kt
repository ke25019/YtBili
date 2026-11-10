package com.ytstyle.bili.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the two image-URL normalisers.
 *
 * These exist because a wrong answer here is invisible: the image loader gets a
 * reference it cannot resolve, fails quietly, and the screen shows a blank
 * rectangle with no error and no log line. The space header did exactly that —
 * `top_photo` arrives as `bfs/space/….png`, and the old `else -> this` handed it
 * straight through.
 */
class ImageUrlTest {

    private companion object {
        /** The captured value from `x/space/wbi/acc/info`. */
        const val BARE_PATH = "bfs/space/cb1c3ef50e22b6096fde67febe863494caefebad.png"
        const val CDN = "https://i0.hdslb.com/"
    }

    // ---- toSecureUrl: keeps the native aspect, so no size suffix ----

    @Test
    fun `bare path gets the cdn host`() {
        assertEquals(CDN + BARE_PATH, BARE_PATH.toSecureUrl())
    }

    @Test
    fun `bare path with a leading slash does not double the separator`() {
        assertEquals(CDN + "bfs/face/x.png", "/bfs/face/x.png".toSecureUrl())
    }

    @Test
    fun `plain http is upgraded to https`() {
        assertEquals(
            "https://i2.hdslb.com/bfs/face/a.png",
            "http://i2.hdslb.com/bfs/face/a.png".toSecureUrl(),
        )
    }

    @Test
    fun `protocol relative url gets https`() {
        assertEquals("https://i0.hdslb.com/x.png", "//i0.hdslb.com/x.png".toSecureUrl())
    }

    @Test
    fun `an already https url is left alone`() {
        val url = "https://i0.hdslb.com/bfs/garb/item/1.png"
        assertEquals(url, url.toSecureUrl())
    }

    /** Blank must stay blank: Coil treats "" as "no image", not as a URL. */
    @Test
    fun `blank stays blank`() {
        assertEquals("", "".toSecureUrl())
        assertEquals("   ", "   ".toSecureUrl())
    }

    // ---- toThumbUrl: same normalisation, plus a requested size ----

    @Test
    fun `thumb url is upgraded and sized`() {
        assertEquals(
            "https://i2.hdslb.com/bfs/archive/a.jpg@480w_270h_1c.webp",
            "http://i2.hdslb.com/bfs/archive/a.jpg".toThumbUrl(),
        )
    }

    /** A URL that already carries an `@` has been sized; do not add a second. */
    @Test
    fun `thumb url keeps an existing size directive`() {
        val url = "https://i0.hdslb.com/a.jpg@320w_200h_1c.webp"
        assertEquals(url, url.toThumbUrl())
    }

    @Test
    fun `thumb url resolves a bare path before sizing it`() {
        assertEquals(CDN + BARE_PATH + "@480w_270h_1c.webp", BARE_PATH.toThumbUrl())
    }

    @Test
    fun `thumb url of blank stays blank`() {
        assertEquals("", "".toThumbUrl())
    }
}
