package com.ytstyle.bili.data.session

import com.ytstyle.bili.data.net.CookieCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for cookie-header normalisation.
 *
 * This matters because Bilibili authenticates purely by cookie, and a parsing
 * mistake degrades silently to "not logged in" rather than raising an error.
 */
class CookieUtilsTest {

    @Test
    fun `already canonical input is unchanged`() {
        assertEquals(
            "SESSDATA=abc; bili_jct=def",
            CookieUtils.normalize("SESSDATA=abc; bili_jct=def"),
        )
    }

    /** Devtools copies often include newlines instead of semicolons. */
    @Test
    fun `splits on newlines and carriage returns`() {
        assertEquals("a=1; b=2; c=3", CookieUtils.normalize("a=1\nb=2\r\nc=3"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("SESSDATA=abc; bili_jct=def", CookieUtils.normalize("  SESSDATA = abc ;   bili_jct=def  "))
    }

    @Test
    fun `drops empty segments from trailing semicolons`() {
        assertEquals("a=1; b=2", CookieUtils.normalize("a=1;;b=2;"))
    }

    /** A browser resolves a repeated cookie to the last one set. */
    @Test
    fun `later duplicate wins`() {
        assertEquals("SESSDATA=new", CookieUtils.normalize("SESSDATA=old; SESSDATA=new"))
    }

    /** Entries that are flags rather than pairs must be ignored, not crash. */
    @Test
    fun `ignores entries without an equals sign`() {
        assertEquals("a=1", CookieUtils.normalize("Secure; HttpOnly; a=1"))
    }

    @Test
    fun `ignores empty names`() {
        assertEquals("a=1", CookieUtils.normalize("=orphan; a=1"))
    }

    @Test
    fun `preserves empty values`() {
        assertEquals("a=", CookieUtils.normalize("a="))
    }

    /**
     * SESSDATA is base64-ish and can contain `=` padding, `%` escapes and `*`.
     * Decoding or escaping any of it would break authentication.
     */
    @Test
    fun `preserves special characters in values`() {
        val raw = "SESSDATA=abc%2Fdef*ghi==; bili_jct=xyz"
        assertEquals(raw, CookieUtils.normalize(raw))
    }

    @Test
    fun `extracts a value by key`() {
        val cookie = CookieUtils.normalize("SESSDATA=abc; bili_jct=def; DedeUserID=42")
        assertEquals("abc", CookieUtils.value(cookie, "SESSDATA"))
        assertEquals("def", CookieUtils.value(cookie, "bili_jct"))
        assertEquals("42", CookieUtils.value(cookie, "DedeUserID"))
    }

    @Test
    fun `returns empty string for a missing key`() {
        val cookie = CookieUtils.normalize("a=1")
        assertEquals("", CookieUtils.value(cookie, "SESSDATA"))
    }

    /**
     * Keys must match exactly: "DedeUserID" must not be satisfied by
     * "DedeUserID__ckMd5", which is a separate cookie sent alongside it.
     */
    @Test
    fun `does not match a key by prefix`() {
        val cookie = CookieUtils.normalize("DedeUserID__ckMd5=hash")
        assertEquals("", CookieUtils.value(cookie, "DedeUserID"))
    }

    @Test
    fun `detects a usable session`() {
        assertTrue(CookieUtils.hasSession(CookieUtils.normalize("SESSDATA=abc; bili_jct=d")))
        assertFalse(CookieUtils.hasSession(CookieUtils.normalize("bili_jct=d")))
        assertFalse(CookieUtils.hasSession(CookieUtils.normalize("SESSDATA=; bili_jct=d")))
        assertFalse(CookieUtils.hasSession(""))
    }
}

/** Tests the Set-Cookie scraping used by the QR login flow. */
class CookieCaptureTest {

    @Test
    fun `captures the relevant session cookies`() {
        val capture = CookieCapture()
        capture.absorb(
            listOf(
                "SESSDATA=abc; Path=/; Domain=.bilibili.com; HttpOnly",
                "bili_jct=def; Path=/",
                "DedeUserID=42; Path=/",
            ),
        )

        assertTrue(capture.hasSession())
        val header = capture.drain()!!
        val parsed = CookieUtils.normalize(header)
        assertEquals("abc", CookieUtils.value(parsed, "SESSDATA"))
        assertEquals("def", CookieUtils.value(parsed, "bili_jct"))
        assertEquals("42", CookieUtils.value(parsed, "DedeUserID"))
    }

    /** Tracking cookies on the same response must not be persisted. */
    @Test
    fun `ignores irrelevant cookies`() {
        val capture = CookieCapture()
        capture.absorb(listOf("buvid3=tracking; Path=/", "b_nut=123; Path=/"))

        assertFalse(capture.hasSession())
        assertNull(capture.drain())
    }

    @Test
    fun `drain clears captured state so it is used once`() {
        val capture = CookieCapture()
        capture.absorb(listOf("SESSDATA=abc; Path=/"))

        assertTrue(capture.hasSession())
        capture.drain()
        assertFalse(capture.hasSession())
        assertNull(capture.drain())
    }

    /** Cookie attributes must not leak into the value. */
    @Test
    fun `strips attributes`() {
        val capture = CookieCapture()
        capture.absorb(listOf("SESSDATA=abc; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT"))

        val value = CookieUtils.value(CookieUtils.normalize(capture.drain()!!), "SESSDATA")
        assertEquals("abc", value)
    }

    /** Values may themselves contain '=' padding. */
    @Test
    fun `preserves equals signs inside a value`() {
        val capture = CookieCapture()
        capture.absorb(listOf("SESSDATA=abc==; Path=/"))

        val value = CookieUtils.value(CookieUtils.normalize(capture.drain()!!), "SESSDATA")
        assertEquals("abc==", value)
    }

    @Test
    fun `hasSession is false before anything is captured`() {
        assertFalse(CookieCapture().hasSession())
    }
}
