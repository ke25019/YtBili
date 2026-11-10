package com.ytstyle.bili.ui.messages

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the 私信 conversation parser and the emote splitter.
 *
 * The payloads here are **synthetic but doc-accurate**: the live
 * `fetch_session_msgs` endpoint needs a login session, which this machine does
 * not have, so nothing below is a captured response. The shapes — the
 * `{code, data:{messages…}}` envelope, `content` as a serialised JSON document,
 * `e_infos` as a token→url list — follow the community API documentation.
 */
class SessionMessageTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun payload(text: String) = json.parseToJsonElement(text)

    /**
     * The endpoint answers `{code, message, ttl, data:{…}}`; the tests below
     * state the inner object, and this puts the envelope back around it. The
     * parser reads `data`, so passing the inner object directly would silently
     * test nothing.
     */
    private fun wrapped(inner: String) = """{"code":0,"message":"0","ttl":1,"data":$inner}"""

    private val selfUid = 42L

    @Test
    fun `text message parses with its side, time and emote`() {
        val page = parseSessionMessages(
            payload(
                wrapped(
                    """
                    {
                      "messages": [
                        {
                          "sender_uid": 100,
                          "msg_type": 1,
                          "content": "{\"content\":\"你好[tv_doge]\"}",
                          "msg_seqno": 20,
                          "msg_key": 900,
                          "timestamp": 1780272000,
                          "msg_status": 0
                        }
                      ],
                      "has_more": 0,
                      "min_seqno": 20,
                      "e_infos": [
                        {"text": "[tv_doge]", "url": "https://i0.hdslb.com/bfs/emote/doge.png", "size": 1}
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
            selfUid,
        )

        assertEquals(1, page.messages.size)
        val message = page.messages.first()
        assertEquals(SessionMessageKind.TEXT, message.kind)
        assertEquals("你好[tv_doge]", message.text)
        assertEquals(900L, message.key)
        assertEquals(20L, message.seqno)
        assertEquals(1780272000L, message.timeSeconds)
        assertFalse("a 100 is not self 42", message.outgoing)
        assertEquals("https://i0.hdslb.com/bfs/emote/doge.png", page.emotes["[tv_doge]"])
        assertFalse(page.hasMore)
        assertEquals(20L, page.minSeqno)
    }

    @Test
    fun `a message from this account is outgoing`() {
        val page = parseSessionMessages(
            payload(
                wrapped(
                    """{"messages":[{"sender_uid":42,"msg_type":1,
                       "content":"{\"content\":\"hi\"}","msg_seqno":1,"msg_key":1,
                       "timestamp":1780272000,"msg_status":0}]}""",
                ),
            ),
            selfUid,
        )
        assertTrue(page.messages.first().outgoing)
    }

    @Test
    fun `image message carries url and dimensions`() {
        val message = parseSessionMessages(
            payload(
                wrapped(
                    """{"messages":[{"sender_uid":1,"msg_type":2,
                       "content":"{\"url\":\"https://message.biliimg.com/a.jpg\",\"width\":300,\"height\":200}",
                       "msg_seqno":5,"msg_key":5,"timestamp":1780272000,"msg_status":0}]}""",
                ),
            ),
            selfUid,
        ).messages.first()

        assertEquals(SessionMessageKind.IMAGE, message.kind)
        assertEquals("https://message.biliimg.com/a.jpg", message.imageUrl)
        assertEquals(300, message.imageWidth)
        assertEquals(200, message.imageHeight)
    }

    @Test
    fun `shared video exposes its bvid and cover`() {
        val message = parseSessionMessages(
            payload(
                wrapped(
                    """{"messages":[{"sender_uid":1,"msg_type":7,
                       "content":"{\"author\":\"某人\",\"title\":\"标题\",\"thumb\":\"http://i2.hdslb.com/x.jpg\",\"bvid\":\"BV16v411e7CW\"}",
                       "msg_seqno":6,"msg_key":6,"timestamp":1780272000,"msg_status":0}]}""",
                ),
            ),
            selfUid,
        ).messages.first()

        assertEquals(SessionMessageKind.SHARE, message.kind)
        assertEquals("BV16v411e7CW", message.bvid)
        assertEquals("标题", message.title)
        assertEquals("某人", message.desc)
        assertEquals("http://i2.hdslb.com/x.jpg", message.cover)
    }

    @Test
    fun `share without bvid still finds one in the url`() {
        val message = parseSessionMessages(
            payload(
                wrapped(
                    """{"messages":[{"sender_uid":1,"msg_type":7,
                       "content":"{\"title\":\"t\",\"url\":\"https://www.bilibili.com/video/BV1xx411c7mD\"}",
                       "msg_seqno":7,"msg_key":7,"timestamp":1780272000,"msg_status":0}]}""",
                ),
            ),
            selfUid,
        ).messages.first()

        assertEquals("BV1xx411c7mD", message.bvid)
    }

    @Test
    fun `notice message keeps title and body`() {
        val message = parseSessionMessages(
            payload(
                wrapped(
                    """{"messages":[{"sender_uid":1,"msg_type":10,
                       "content":"{\"title\":\"流量奖励到账通知\",\"text\":\"恭喜您已获得奖励\"}",
                       "msg_seqno":8,"msg_key":8,"timestamp":1780272000,"msg_status":0}]}""",
                ),
            ),
            selfUid,
        ).messages.first()

        assertEquals(SessionMessageKind.NOTICE, message.kind)
        assertEquals("流量奖励到账通知", message.title)
        assertEquals("恭喜您已获得奖励", message.text)
    }

    @Test
    fun `system hint is read out of the serialised array`() {
        val message = parseSessionMessages(
            payload(
                wrapped(
                    """{"messages":[{"sender_uid":1,"msg_type":18,
                       "content":"{\"content\":\"[{\\\"text\\\":\\\"对方主动回复或关注你前\\\",\\\"color_day\\\":\\\"#9499A0\\\"},{\\\"text\\\":\\\"最多发送1条消息\\\"}]\"}",
                       "msg_seqno":9,"msg_key":9,"timestamp":1780272000,"msg_status":0}]}""",
                ),
            ),
            selfUid,
        ).messages.first()

        assertEquals(SessionMessageKind.SYSTEM, message.kind)
        assertEquals("对方主动回复或关注你前 最多发送1条消息", message.text)
    }

    @Test
    fun `a recalled message says who recalled it`() {
        fun recalledFor(sender: Long) = parseSessionMessages(
            payload(
                wrapped(
                    """{"messages":[{"sender_uid":$sender,"msg_type":1,
                       "content":"{\"content\":\"秘密\"}","msg_seqno":10,"msg_key":10,
                       "timestamp":1780272000,"msg_status":1}]}""",
                ),
            ),
            selfUid,
        ).messages.first()

        assertEquals(SessionMessageKind.RECALLED, recalledFor(100).kind)
        assertEquals("对方撤回了一条消息", recalledFor(100).text)
        assertEquals("你撤回了一条消息", recalledFor(selfUid).text)
    }

    @Test
    fun `an unknown message type keeps its raw body`() {
        val message = parseSessionMessages(
            payload(
                wrapped(
                    """{"messages":[{"sender_uid":1,"msg_type":99,
                       "content":"{\"whatever\":1}","msg_seqno":11,"msg_key":11,
                       "timestamp":1780272000,"msg_status":0}]}""",
                ),
            ),
            selfUid,
        ).messages.first()

        assertEquals(SessionMessageKind.UNKNOWN, message.kind)
        assertTrue(message.text.contains("whatever"))
    }

    @Test
    fun `the page is returned oldest first`() {
        val page = parseSessionMessages(
            payload(
                wrapped(
                    """{"messages":[
                       {"sender_uid":1,"msg_type":1,"content":"{\"content\":\"new\"}","msg_seqno":30,"msg_key":3,"timestamp":1780272300,"msg_status":0},
                       {"sender_uid":1,"msg_type":1,"content":"{\"content\":\"old\"}","msg_seqno":10,"msg_key":1,"timestamp":1780272000,"msg_status":0},
                       {"sender_uid":1,"msg_type":1,"content":"{\"content\":\"mid\"}","msg_seqno":20,"msg_key":2,"timestamp":1780272100,"msg_status":0}
                    ],"has_more":1,"min_seqno":10}""",
                ),
            ),
            selfUid,
        )

        assertEquals(listOf("old", "mid", "new"), page.messages.map { it.text })
        assertTrue(page.hasMore)
        assertEquals(10L, page.minSeqno)
    }

    /**
     * The repository hands the parser `MsgEnvelope.data`, so the bare object has
     * to work as well as the envelope — the same one-level trap that made the
     * dynamic feed render empty while every request returned 200.
     */
    @Test
    fun `the bare data object parses like the wrapped one`() {
        val bare = """{"messages":[{"sender_uid":1,"msg_type":1,
            "content":"{\"content\":\"hi\"}","msg_seqno":1,"msg_key":1,
            "timestamp":1780272000,"msg_status":0}],"has_more":1,"min_seqno":1}"""

        val fromBare = parseSessionMessages(payload(bare), selfUid)
        val fromWrapped = parseSessionMessages(payload(wrapped(bare)), selfUid)

        assertEquals(1, fromBare.messages.size)
        assertEquals(fromWrapped.messages.map { it.text }, fromBare.messages.map { it.text })
        assertTrue(fromBare.hasMore)
        assertEquals(1L, fromBare.minSeqno)
    }

    @Test
    fun `malformed payloads yield an empty page instead of throwing`() {
        listOf(
            "null",
            "{}",
            """{"code":-101,"message":"账号未登录"}""",
            """{"code":0,"data":null}""",
            """{"code":0,"data":{"messages":null}}""",
            """{"code":0,"data":{"messages":"nope"}}""",
        ).forEach { text ->
            val page = parseSessionMessages(payload(text), selfUid)
            assertTrue("expected no messages for $text", page.messages.isEmpty())
            assertFalse(page.hasMore)
        }
    }

    @Test
    fun `emote tokens are split out and unknown ones stay as text`() {
        val emotes = mapOf(
            "[tv_doge]" to "https://i0.hdslb.com/doge.png",
            "[大笑]" to "https://i0.hdslb.com/laugh.png",
        )

        val segments = splitIntoSessionSegments("哈[tv_doge]哈[未知]", emotes)

        // Text, the known emote, then everything after it — including the
        // unknown token, which is left as literal text rather than dropped.
        assertEquals(3, segments.size)
        assertEquals("哈", (segments[0] as SessionSegment.Text).value)
        assertEquals("[tv_doge]", (segments[1] as SessionSegment.Emote).token)
        assertEquals("https://i0.hdslb.com/doge.png", (segments[1] as SessionSegment.Emote).url)
        assertEquals("哈[未知]", (segments[2] as SessionSegment.Text).value)
    }

    @Test
    fun `the longest emote token wins`() {
        // `[tv]` is a prefix of `[tv_doge]`; a naive alternation would split the
        // longer token and leave `_doge]` behind as text.
        val emotes = mapOf(
            "[tv]" to "https://i0.hdslb.com/tv.png",
            "[tv_doge]" to "https://i0.hdslb.com/doge.png",
        )

        val segments = splitIntoSessionSegments("[tv_doge]", emotes)

        assertEquals(1, segments.size)
        assertEquals("[tv_doge]", (segments[0] as SessionSegment.Emote).token)
    }

    @Test
    fun `no emotes means one plain text segment`() {
        val segments = splitIntoSessionSegments("普通文字", emptyMap())
        assertEquals(1, segments.size)
        assertEquals("普通文字", (segments[0] as SessionSegment.Text).value)
    }
}
