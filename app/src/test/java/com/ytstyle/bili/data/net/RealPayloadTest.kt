package com.ytstyle.bili.data.net

import com.ytstyle.bili.ui.components.toThumbUrl
import com.ytstyle.bili.ui.search.stripHighlight
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deserialises **real captured Bilibili responses** through the production
 * models and [Json] configuration.
 *
 * The fixtures under `src/test/resources/fixtures` were fetched live from
 * `api.bilibili.com`. Reasoning about whether a model matches a payload is
 * unreliable — especially with `ignoreUnknownKeys`, which hides drift — so
 * these assertions pin the contract against actual bytes.
 *
 * Regenerate the fixtures with `H:\bilibili\diag_capture.py` when Bilibili
 * changes a response shape.
 */
class RealPayloadTest {

    /** The exact configuration used at runtime, not a test-local copy. */
    private val json: Json = NetworkModule.provideJson()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture: $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `recommend feed parses`() {
        val response = json.decodeFromString<BiliResponse<RecommendFeed>>(fixture("feed_rcmd.json"))

        assertEquals(0, response.code)
        assertTrue("feed returned no items", response.isSuccess)

        val items = response.data!!.item
        assertEquals(20, items.size)

        // Every item must carry the fields the home card renders.
        items.forEach { item ->
            assertTrue("blank bvid", item.bvid.isNotEmpty())
            assertTrue("blank title", item.title.isNotEmpty())
            assertTrue("blank pic", item.pic.isNotEmpty())
            assertNotNull("missing owner", item.owner)
            assertNotNull("missing stat", item.stat)
        }

        // The recommendation endpoint only returns archive items today; if
        // live/ad rows ever appear they have a different shape and the card
        // would need to branch on `goto`.
        assertEquals(setOf("av"), items.map { it.goto }.toSet())
    }

    /**
     * Thumbnails are served over plain HTTP by this endpoint. The app forbids
     * cleartext traffic, so the URL must be upgraded before it reaches Coil or
     * the cards render with no image.
     */
    @Test
    fun `feed thumbnails are http and must be upgraded`() {
        val items = json
            .decodeFromString<BiliResponse<RecommendFeed>>(fixture("feed_rcmd.json"))
            .data!!.item

        val insecure = items.filter { it.pic.startsWith("http://") }
        assertTrue("expected at least one http:// thumbnail in the fixture", insecure.isNotEmpty())

        insecure.forEach { item ->
            assertTrue(
                "toThumbUrl must upgrade to https",
                item.pic.toThumbUrl().startsWith("https://"),
            )
        }
    }

    @Test
    fun `view detail parses and exposes a cid`() {
        val response = json.decodeFromString<BiliResponse<ViewDetail>>(fixture("view_detail.json"))

        assertEquals(0, response.code)
        val detail = response.data!!
        assertTrue(detail.bvid.isNotEmpty())
        assertTrue(detail.title.isNotEmpty())
        assertTrue("aid must be present for comments", detail.aid > 0)
        assertTrue("at least one page is required to resolve a stream", detail.pages.isNotEmpty())
        assertTrue("page cid must be non-zero", detail.pages.first().cid > 0)
    }

    /**
     * The player builds its media source from these fields, so each one is
     * asserted individually rather than trusting the shape.
     */
    @Test
    fun `playurl parses into usable dash tracks`() {
        val response = json.decodeFromString<BiliResponse<PlayUrlData>>(fixture("playurl_anon.json"))

        assertEquals(0, response.code)
        val data = response.data!!
        val dash = requireNotNull(data.dash) { "dash payload missing" }
        assertTrue("no video tracks", dash.video.isNotEmpty())
        assertTrue("no audio tracks", dash.audio.isNotEmpty())

        // Anonymous sessions still get 1080P; assert the tier list parses.
        assertTrue("accept_quality empty", data.accept_quality.isNotEmpty())
        assertTrue("1080P (80) should be offered", data.accept_quality.contains(80))

        dash.video.forEach { track ->
            assertTrue("video track has no baseUrl", track.baseUrl.isNotEmpty())
            assertTrue("video track has no bandwidth", track.bandwidth > 0)
        }
        dash.audio.forEach { track ->
            assertTrue("audio track has no baseUrl", track.baseUrl.isNotEmpty())
        }

        // The highest-bandwidth pick is what the player actually plays.
        val best = dash.video.maxByOrNull { it.bandwidth }!!
        assertTrue("best video track must have a real URL", best.baseUrl.startsWith("http"))
        assertTrue("best video track must declare dimensions", best.width > 0 && best.height > 0)
    }

    /**
     * PGC (番剧/影视) search results.
     *
     * These carry a `season_id` and open in the bangumi player rather than the
     * archive player, so the fields the card renders are asserted explicitly.
     * The fixture was captured with an anime keyword: the same request with a
     * game keyword legitimately returns an empty list, which is what led an
     * earlier build to wrongly conclude this partition was unavailable.
     */
    @Test
    fun `pgc search parses`() {
        val response = json.decodeFromString<BiliResponse<MediaSearchData>>(
            fixture("search_media_bangumi.json"),
        )

        assertEquals(0, response.code)
        val items = response.data!!.result
        assertTrue("no PGC results in fixture", items.isNotEmpty())

        items.forEach { item ->
            assertTrue("missing title", item.title.isNotEmpty())
            assertTrue("missing cover", item.cover.isNotEmpty())
            assertTrue("missing season id", item.seasonId > 0)
            assertTrue("missing goto url", item.gotoUrl.startsWith("http"))
        }

        // Title markup must be strippable, as the card strips it.
        val first = items.first()
        assertTrue("fixture is stale: no <em> markup in title", first.title.contains("<em"))
        assertTrue(
            "highlight markup must be removable",
            !first.title.stripHighlight().contains("<"),
        )
    }

    /**
     * Bilibili returns both camelCase and snake_case keys for DASH tracks
     * (`baseUrl` and `base_url`). The model reads camelCase; this guards the
     * assumption that the camelCase key is actually present.
     */
    @Test
    fun `dash tracks expose camelcase keys`() {
        val raw = fixture("playurl_anon.json")
        assertTrue("fixture is stale: no baseUrl key", raw.contains("\"baseUrl\""))
        assertTrue("fixture is stale: no base_url key", raw.contains("\"base_url\""))
    }
}
