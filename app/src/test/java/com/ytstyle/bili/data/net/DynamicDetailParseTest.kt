package com.ytstyle.bili.data.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `polymer/web-dynamic/v1/detail` → [DynamicEntry].
 *
 * The detail page opens from the card it already has, so this parser is the
 * fallback that fills in a truncated body. It walks the same `item` object the
 * feed uses, which is why the cases here are about *shapes* (envelope vs bare
 * `data`, `draw` vs `opus` pictures), not about the page's layout.
 */
class DynamicDetailParseTest {

    private fun item(text: String = "正文", withDraw: Boolean = true, withOpus: Boolean = false): String {
        val major = when {
            withOpus -> """
                "major": {
                  "type": "MAJOR_TYPE_OPUS",
                  "opus": {
                    "summary": {"text": "$text"},
                    "pics": [{"url": "https://i0.hdslb.com/opus/1.jpg"}]
                  }
                }
            """.trimIndent()

            withDraw -> """
                "major": {
                  "type": "MAJOR_TYPE_DRAW",
                  "draw": {"items": [
                    {"src": "https://i0.hdslb.com/draw/1.jpg"},
                    {"src": "https://i0.hdslb.com/draw/2.jpg"}
                  ]}
                }
            """.trimIndent()

            else -> """"major": {"type": "MAJOR_TYPE_NONE", "desc": {"text": "$text"}}"""
        }

        return """
            {
              "id_str": "111222333444555",
              "type": "DYNAMIC_TYPE_DRAW",
              "modules": {
                "module_author": {
                  "name": "测试UP",
                  "face": "https://i0.hdslb.com/face.jpg",
                  "mid": 42,
                  "pub_time": "3天前"
                },
                "module_dynamic": {
                  "desc": {"text": "$text", "rich_text_nodes": []},
                  $major
                },
                "module_stat": {
                  "like": {"count": 12},
                  "comment": {"count": 3},
                  "forward": {"count": 1}
                }
              }
            }
        """.trimIndent()
    }

    private fun envelope(itemJson: String) = Json.parseToJsonElement(
        """{"code":0,"message":"OK","ttl":1,"data":{"item":$itemJson}}""",
    )

    @Test
    fun `reads a detail envelope`() {
        val entry = parseDynamicDetail(envelope(item()))
        assertEquals("111222333444555", entry?.id)
        assertEquals("测试UP", entry?.authorName)
        assertEquals("3天前", entry?.pubTimeLabel)
        assertEquals(12L, entry?.likeCount)
        assertEquals(3L, entry?.commentCount)
        assertEquals(1L, entry?.forwardCount)
    }

    @Test
    fun `accepts a bare data object`() {
        // The repository hands over `BiliResponse.data` in some call paths; both
        // depths must parse or the page silently shows nothing.
        val bare = Json.parseToJsonElement("""{"item":${item()}}""")
        assertEquals("111222333444555", parseDynamicDetail(bare)?.id)
    }

    @Test
    fun `draw pictures become images`() {
        val entry = parseDynamicDetail(envelope(item(withDraw = true)))
        assertEquals(2, entry?.imageUrls?.size)
        assertEquals(DynamicKind.IMAGES, entry?.kind)
    }

    @Test
    fun `opus pictures become images`() {
        val entry = parseDynamicDetail(envelope(item(withOpus = true)))
        assertEquals(listOf("https://i0.hdslb.com/opus/1.jpg"), entry?.imageUrls)
    }

    @Test
    fun `text survives`() {
        val entry = parseDynamicDetail(envelope(item(text = "第一行\n第二行")))
        assertTrue(entry?.text?.contains("\n") == true)
    }

    @Test
    fun `a payload without item is null rather than a crash`() {
        assertNull(parseDynamicDetail(Json.parseToJsonElement("""{"code":-404,"message":"啥都木有"}""")))
        assertNull(parseDynamicDetail(null))
    }
}
