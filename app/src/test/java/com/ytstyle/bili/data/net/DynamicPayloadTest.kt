package com.ytstyle.bili.data.net

import com.ytstyle.bili.ui.components.toThumbUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the 动态 parser over a **real captured** `feed/space` response.
 *
 * The fixture is nine real items: eight from `host_mid=2` (碧诗) plus one
 * `MAJOR_TYPE_DRAW` item taken from `host_mid=946974` (影视飓风), because the
 * first capture happened to contain no image dynamic and the grid path would
 * otherwise be untested. Every item is byte-for-byte what the API returned; only
 * the surrounding envelope was trimmed to nine items.
 *
 * `feed/space` is public, so this could be captured without a session.
 * `feed/all` — the endpoint the screen actually pages through — needs a login
 * cookie that this machine does not have, so its *envelope* (`data.items`,
 * `data.offset`, `data.has_more`) is asserted from documentation rather than
 * from a live capture.
 */
class DynamicPayloadTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture: $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun payload(text: String) = json.parseToJsonElement(text)

    @Test
    fun `real capture parses into entries`() {
        val page = parseDynamicPage(payload(fixture("dynamic_space.json")))

        assertEquals(9, page.entries.size)
        assertTrue("offset must be carried for paging", page.offset.isNotEmpty())
        assertTrue("has_more must be read", page.hasMore)

        // Every card draws the author line, so nothing may be blank there.
        page.entries.forEach { entry ->
            assertTrue("blank id", entry.id.isNotEmpty())
            assertTrue("blank author", entry.authorName.isNotEmpty())
            assertTrue("blank pub time", entry.pubTimeLabel.isNotEmpty())
        }
    }

    @Test
    fun `article dynamic exposes its title and stats`() {
        val first = parseDynamicPage(payload(fixture("dynamic_space.json"))).entries.first()

        assertEquals(DynamicKind.ARTICLE, first.kind)
        assertTrue("blank article title", first.articleTitle.isNotEmpty())
        assertTrue("blank article cover", first.articleCover.isNotEmpty())
        assertEquals(2692L, first.likeCount)
    }

    @Test
    fun `video dynamic exposes bvid, duration and the preformatted play count`() {
        val video = parseDynamicPage(payload(fixture("dynamic_space.json")))
            .entries
            .first { it.kind == DynamicKind.VIDEO }

        assertEquals("BV1ujNV6qEXg", video.videoBvid)
        assertTrue("blank video title", video.videoTitle.isNotEmpty())
        assertEquals("00:22", video.videoDurationLabel)
        // The wire value is a formatted string, not a number; reformatting it
        // would turn "78.7万" into "787000".
        assertEquals("78.7万", video.videoViewText)
        assertEquals(90002L, video.likeCount)
        assertEquals(9435L, video.commentCount)
        assertEquals(212L, video.forwardCount)
    }

    @Test
    fun `forward dynamic resolves the quoted original`() {
        val forward = parseDynamicPage(payload(fixture("dynamic_space.json")))
            .entries
            .first { it.kind == DynamicKind.FORWARD }

        assertTrue("forward text missing", forward.text.isNotEmpty())
        assertTrue("original author missing", forward.forwardAuthorName.isNotEmpty())
        // This capture's originals are all videos, so the embedded archive must
        // have been picked up for the quote box.
        assertTrue("original bvid missing", forward.forwardVideoBvid.isNotEmpty())
        assertTrue("original title missing", forward.forwardVideoTitle.isNotEmpty())
    }

    @Test
    fun `draw dynamic exposes its images and they need the https upgrade`() {
        val images = parseDynamicPage(payload(fixture("dynamic_space.json")))
            .entries
            .first { it.kind == DynamicKind.IMAGES }

        assertEquals(1, images.imageUrls.size)
        val url = images.imageUrls.first()
        assertTrue("expected a plain-http image in the capture", url.startsWith("http://"))
        assertTrue(
            "Bilibili's CDN serves these over https too, so the scheme is upgraded",
            url.toThumbUrl().startsWith("https://"),
        )
    }

    /**
     * `BiliResponse.data` is what the view model actually hands the parser, so
     * the parser must cope with the **bare** `data` object as well as the full
     * envelope. Only testing the envelope is how the feed shipped rendering
     * "还没有动态" while every request logged HTTP 200.
     */
    @Test
    fun `the bare data object parses exactly like the full envelope`() {
        val envelope = payload(fixture("dynamic_space.json"))
        val data = (envelope as JsonObject)["data"] ?: error("fixture has no data")

        val fromEnvelope = parseDynamicPage(envelope)
        val fromData = parseDynamicPage(data)

        assertEquals(9, fromData.entries.size)
        assertEquals(fromEnvelope.entries.size, fromData.entries.size)
        assertEquals(fromEnvelope.entries.map { it.id }, fromData.entries.map { it.id })
        assertEquals(fromEnvelope.offset, fromData.offset)
        assertEquals(fromEnvelope.hasMore, fromData.hasMore)
    }

    @Test
    fun `followings parse from either shape`() {
        val envelope = payload("""{"code":0,"data":{"list":[{"mid":7,"uname":"甲"}]}}""")
        val data = payload("""{"list":[{"mid":7,"uname":"甲"}]}""")

        assertEquals(1, parseFollowings(envelope).size)
        assertEquals(1, parseFollowings(data).size)
        assertEquals("甲", parseFollowings(data).first().name)
    }

    @Test
    fun `a payload without data yields an empty page instead of throwing`() {
        listOf(
            "null",
            "{}",
            """{"code":-101,"message":"账号未登录"}""",
            """{"code":0,"data":null}""",
            """{"code":0,"data":{"items":null,"has_more":false}}""",
            """{"code":0,"data":{"items":[],"has_more":false}}""",
        ).forEach { text ->
            val page = parseDynamicPage(payload(text))
            assertTrue("expected no entries for $text", page.entries.isEmpty())
        }
    }

    @Test
    fun `an item without any renderable field is dropped`() {
        // No id, no author, no text, no major: nothing a card could show.
        val page = parseDynamicPage(
            payload("""{"code":0,"data":{"items":[{"type":"DYNAMIC_TYPE_AV"}],"has_more":false}}"""),
        )
        assertTrue(page.entries.isEmpty())

        // The same item with a body text must survive.
        val withText = parseDynamicPage(
            payload(
                """{"code":0,"data":{"items":[{"id_str":"1","type":"DYNAMIC_TYPE_WORD",
                   "modules":{"module_author":{"name":"某人"},"module_dynamic":{"desc":{"text":"你好"}}}}],
                   "has_more":false}}""",
            ),
        )
        assertEquals(1, withText.entries.size)
        assertEquals(DynamicKind.TEXT, withText.entries.first().kind)
        assertEquals("你好", withText.entries.first().text)
    }

    @Test
    fun `followings tolerates an empty payload and reads both name fields`() {
        assertTrue(parseFollowings(payload("""{"data":{}}""")).isEmpty())
        assertTrue(parseFollowings(null).isEmpty())

        val users = parseFollowings(
            payload(
                """{"code":0,"data":{"list":[
                   {"mid":1,"uname":"甲","face":"http://a/x.jpg"},
                   {"mid":2,"name":"乙","face":"http://a/y.jpg"},
                   {"mid":0,"uname":""}
                ]}}""",
            ),
        )
        assertEquals(2, users.size)
        assertEquals("甲", users[0].name)
        assertEquals("乙", users[1].name)
        assertEquals(1L, users[0].mid)
    }
}
