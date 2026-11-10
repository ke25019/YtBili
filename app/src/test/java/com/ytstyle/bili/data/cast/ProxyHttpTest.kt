package com.ytstyle.bili.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the local media proxy's HTTP text.
 *
 * The socket loop is not covered — it needs a real connection — but everything
 * that decides what is forwarded and what is answered is, and that is where the
 * mistakes would be silent: a dropped `Range` header makes seeking fail on the
 * television without any error anywhere.
 */
class ProxyHttpTest {

    @Test
    fun `a renderer request is parsed with its range header`() {
        val raw = "GET /media?u=https%3A%2F%2Fup.example%2Fv.mp4 HTTP/1.1\r\n" +
            "Host: 192.168.1.5:41234\r\n" +
            "Range: bytes=1024-\r\n" +
            "User-Agent: DLNADOC/1.50\r\n" +
            "Connection: close\r\n\r\n"

        val request = ProxyHttp.parseRequest(raw)

        assertEquals("GET", request?.method)
        assertEquals("/media?u=https%3A%2F%2Fup.example%2Fv.mp4", request?.target)
        // Header names are lower-cased so lookups are case-insensitive.
        assertEquals("bytes=1024-", request?.headers?.get("range"))
        assertEquals("192.168.1.5:41234", request?.headers?.get("host"))
    }

    @Test
    fun `an LF-only request and a HEAD method still parse`() {
        val request = ProxyHttp.parseRequest("HEAD /media?u=http%3A%2F%2Fup%2Fa.mp4 HTTP/1.0\n\n")
        assertEquals("HEAD", request?.method)
        assertEquals("/media?u=http%3A%2F%2Fup%2Fa.mp4", request?.target)
    }

    @Test
    fun `unusable request blocks return null`() {
        listOf("", "GET\r\n\r\n", "\r\n\r\n", "GARBAGE").forEach { raw ->
            assertNull("expected null for '$raw'", ProxyHttp.parseRequest(raw))
        }
    }

    @Test
    fun `the upstream url is decoded out of the query`() {
        assertEquals(
            "https://up.example/path/v.mp4?sig=abc",
            ProxyHttp.upstreamUrl("/media?u=https%3A%2F%2Fup.example%2Fpath%2Fv.mp4%3Fsig%3Dabc"),
        )
    }

    @Test
    fun `anything that is not an http url is refused`() {
        listOf(
            "/media",
            "/media?x=1",
            "/media?u=",
            // A path traversal attempt, not a URL.
            "/media?u=%2Fetc%2Fpasswd",
            "/media?u=file%3A%2F%2F%2Fetc%2Fpasswd",
        ).forEach { target ->
            assertNull("expected null for '$target'", ProxyHttp.upstreamUrl(target))
        }
    }

    @Test
    fun `a partial response advertises ranges and the content range`() {
        val head = ProxyHttp.responseHeaders(
            status = 206,
            contentType = "video/mp4",
            contentLength = 2048,
            contentRange = "bytes 1024-3071/999999",
        )

        assertTrue(head.startsWith("HTTP/1.1 206 Partial Content\r\n"))
        assertTrue(head.contains("Content-Type: video/mp4\r\n"))
        assertTrue(head.contains("Accept-Ranges: bytes\r\n"))
        assertTrue(head.contains("Content-Range: bytes 1024-3071/999999\r\n"))
        assertTrue(head.contains("Content-Length: 2048\r\n"))
        assertTrue(head.endsWith("Connection: close\r\n\r\n"))
    }

    @Test
    fun `a full response still advertises range support`() {
        val head = ProxyHttp.responseHeaders(
            status = 200,
            contentType = "video/mp4",
            contentLength = 500,
            contentRange = null,
        )

        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(head.contains("Accept-Ranges: bytes\r\n"))
        assertFalse(head.contains("Content-Range"))
    }

    @Test
    fun `an unknown length is simply left out`() {
        val head = ProxyHttp.responseHeaders(
            status = 200,
            contentType = "video/mp4",
            contentLength = -1,
            contentRange = null,
        )
        assertFalse(head.contains("Content-Length"))
    }

    @Test
    fun `the advertised url points at this phone not at the loopback`() {
        val proxy = LocalMediaProxy()
        // `port` is 0 until start() succeeds, which needs a socket; the URL
        // shape is what matters here.
        val url = proxy.urlFor("https://up.example/v.mp4?sig=1", "192.168.1.5")

        assertTrue(url.startsWith("http://192.168.1.5:0/media?u="))
        assertTrue(url.contains("https%3A%2F%2Fup.example%2Fv.mp4%3Fsig%3D1"))
        // The signature's own `?`/`&` must not survive as query separators.
        assertFalse(url.substringAfter("/media?").contains("&sig"))
    }
}
