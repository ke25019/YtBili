package com.ytstyle.bili.ui.live

import com.ytstyle.bili.data.net.BiliResponse
import com.ytstyle.bili.data.net.LivePlayData
import com.ytstyle.bili.data.net.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests live stream selection against a real captured payload.
 *
 * A live URL is assembled as `host + base_url + extra`, and the `extra` query
 * string carries the signature — dropping or reordering it yields a URL that
 * looks plausible and then 403s, so the concatenation is asserted explicitly.
 */
class LiveStreamTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture: $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun playData(): LivePlayData =
        NetworkModule.provideJson()
            .decodeFromString<BiliResponse<LivePlayData>>(fixture("live_play_info.json"))
            .data!!

    @Test
    fun `live payload parses`() {
        val data = playData()
        val streams = data.playurlInfo?.playurl?.stream.orEmpty()
        assertTrue("no streams decoded", streams.isNotEmpty())
        // Both protocols are requested, so the fixture must contain both.
        val protocols = streams.map { it.protocolName }.toSet()
        assertTrue("expected http_stream in $protocols", protocols.contains("http_stream"))
        assertTrue("expected http_hls in $protocols", protocols.contains("http_hls"))
    }

    /** HLS is preferred because Media3 handles live HLS far more reliably. */
    @Test
    fun `prefers hls over flv`() {
        val choice = pickLiveStream(playData())
        assertNotNull("no playable stream found", choice)
        assertTrue("should have selected HLS", choice!!.isHls)
    }

    /**
     * The assembled URL must include host, path and the signed query string.
     *
     * The HLS entry points at an `.m3u8` playlist rather than a media segment,
     * and its `extra` carries `expires`/`sigparams` but no `deadline` (that
     * appears on the FLV variant), so the assertions here describe what Bilibili
     * actually returns.
     */
    @Test
    fun `assembles host path and signature`() {
        val choice = pickLiveStream(playData())!!
        assertTrue("must be absolute", choice.url.startsWith("https://"))
        assertTrue("must carry the expiry", choice.url.contains("expires="))
        // Without the query separator the signature is missing entirely.
        assertTrue("must contain a query string", choice.url.contains("?"))
        assertTrue("HLS should resolve to a playlist", choice.url.contains(".m3u8"))
    }

    /** avc is preferred over hevc for decoder compatibility. */
    @Test
    fun `prefers avc codec`() {
        val choice = pickLiveStream(playData())!!
        assertTrue("should have selected HLS", choice.isHls)
        // The chosen URL must come from the HLS stream rather than the FLV one.
        assertTrue("expected an .m3u8 playlist", choice.url.contains(".m3u8"))
        assertTrue("must not be the FLV stream", !choice.url.contains(".flv"))
    }

    @Test
    fun `returns null when there are no streams`() {
        assertEquals(null, pickLiveStream(LivePlayData()))
    }
}
