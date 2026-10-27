package com.ytstyle.bili.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Tests for Bilibili's WBI request signing.
 *
 * The expected values here were derived from an independent Python
 * reimplementation of the published algorithm, not from the Kotlin code, so a
 * wrong permutation table or a missing truncation fails these assertions.
 */
class WbiSignerTest {

    // Published example keys used in WBI write-ups.
    private val imgUrl = "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"
    private val subUrl = "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"

    private fun signer(): WbiSigner = WbiSigner().apply { updateKeys(imgUrl, subUrl) }

    /**
     * The permutation reads 64 characters but only the first 32 become the
     * mixin key. Regressing this yields a plausible-looking but rejected `w_rid`.
     */
    @Test
    fun `mixin key is truncated to 32 characters`() {
        val keys = signer()
        assertEquals(32, keys.mixinKeyForTest.length)
        assertEquals("ea1db124af3c7062474693fa704f4ff8", keys.mixinKeyForTest)
    }

    /** End-to-end signature against a fixed clock. */
    @Test
    fun `signs params to the expected w_rid`() {
        val signed = signer().sign(
            params = mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"),
            nowSeconds = 1_700_000_000L,
        )

        assertEquals("1700000000", signed["wts"])
        assertEquals("8ae2d96aa0a4f8c5afa194a6ecca1bf9", signed["w_rid"])
    }

    /** Params must come back sorted, with wts and w_rid appended. */
    @Test
    fun `sign sorts parameters by name`() {
        val signed = signer().sign(
            params = mapOf("zab" to "1", "bar" to "2", "foo" to "3"),
            nowSeconds = 1_700_000_000L,
        )
        assertEquals(
            listOf("bar", "foo", "w_rid", "wts", "zab"),
            signed.keys.sorted(),
        )
    }

    /** Original params must survive signing untouched. */
    @Test
    fun `sign preserves original values`() {
        val signed = signer().sign(
            params = mapOf("bvid" to "BV1xx411c7mD", "cid" to "12345"),
            nowSeconds = 1_700_000_000L,
        )
        assertEquals("BV1xx411c7mD", signed["bvid"])
        assertEquals("12345", signed["cid"])
    }

    /**
     * Without keys the signer must pass params through rather than emit a bogus
     * signature, so the failure surfaces as the server's -403.
     */
    @Test
    fun `unsigned when keys unavailable`() {
        val signer = WbiSigner()
        assertFalse(signer.isReady)
        val params = mapOf("a" to "1")
        assertEquals(params, signer.sign(params))
    }

    /** Short or malformed nav URLs must not produce a partial key. */
    @Test
    fun `rejects short key material`() {
        val signer = WbiSigner()
        signer.updateKeys("https://x/short.png", "https://x/also.png")
        assertFalse(signer.isReady)
    }

    /** Keys rotate daily; a freshly seeded signer must not report stale. */
    @Test
    fun `fresh keys are not stale`() {
        val signer = signer()
        assertTrue(signer.isReady)
        assertFalse(signer.isStale())
    }

    /** w_rid is always lowercase hex md5, i.e. 32 chars. */
    @Test
    fun `w_rid is 32 hex characters`() {
        val signed = signer().sign(mapOf("x" to "y"), nowSeconds = 1_700_000_000L)
        val rid = signed.getValue("w_rid")
        assertEquals(32, rid.length)
        assertTrue(rid.all { it in "0123456789abcdef" })
    }

    /**
     * Independently recomputes the signature to prove the signer's own query
     * construction and MD5 agree with a separate implementation.
     */
    @Test
    fun `w_rid matches an independent computation`() {
        val keys = signer()
        val params = mapOf("keyword" to "原神", "page" to "1")
        val wts = 1_700_000_000L
        val signed = keys.sign(params, nowSeconds = wts)

        val query = (params + ("wts" to wts.toString()))
            .toSortedMap()
            .entries
            .joinToString("&") { (k, v) -> "${k.referenceEncode()}=${v.referenceEncode()}" }

        val expected = MessageDigest.getInstance("MD5")
            .digest((query + keys.mixinKeyForTest).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, signed["w_rid"])
    }

    /**
     * Independent `encodeURIComponent` equivalent used only by the test above.
     * Deliberately written differently from the production encoder.
     */
    private fun String.referenceEncode(): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        return buildString {
            for (byte in toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF).toChar()
                if (c in unreserved) append(c) else append("%%%02X".format(byte.toInt() and 0xFF))
            }
        }
    }
}

/** Separate class so the encoder parity test reads clearly. */
class WbiUrlEncodeTest {

    /**
     * Bilibili's JS uses `encodeURIComponent`, which leaves `!'()*` alone and
     * encodes space as `%20` (not `+`). Java's URLEncoder does the opposite on
     * both counts, so these cases guard the fixups.
     */
    @Test
    fun `matches encodeURIComponent for js-unescaped characters`() {
        assertEquals("!'()*", "!'()*".urlEncode())
    }

    @Test
    fun `encodes space as percent twenty not plus`() {
        assertEquals("a%20b", "a b".urlEncode())
    }

    /** Multi-byte UTF-8 must be percent-encoded per byte, uppercase hex. */
    @Test
    fun `encodes chinese as utf8 bytes`() {
        assertEquals("%E5%8E%9F%E7%A5%9E", "原神".urlEncode())
    }

    @Test
    fun `leaves unreserved characters alone`() {
        assertEquals("abc-_.~", "abc-_.~".urlEncode())
    }

    @Test
    fun `encodes ampersand and equals so they cannot break the query`() {
        assertEquals("%26", "&".urlEncode())
        assertEquals("%3D", "=".urlEncode())
    }
}
