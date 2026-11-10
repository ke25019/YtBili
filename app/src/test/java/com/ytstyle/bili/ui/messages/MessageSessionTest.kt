package com.ytstyle.bili.ui.messages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the direct-message session parser against a **synthetic but
 * doc-accurate** `get_sessions` payload.
 *
 * The real endpoint needs a logged-in cookie, which the machine running these
 * tests does not have, so none of these fixtures were captured — they were
 * written from the community API documentation and match the row kinds visible
 * in the reference 消息 screenshot. The parts that therefore remain unverified
 * are called out in the test names where they matter (the response's
 * `session_type` values, `account_info` on official rows, and the uid-keyed
 * `account/v1/user/cards` shape).
 *
 * What is being protected is the *tolerance*: an odd field must degrade to a
 * blank row, never to a thrown exception or a blanked page.
 */
class MessageSessionTest {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun payload(text: String): JsonElement = json.parseToJsonElement(text)

    /** 2026-06-01T00:00:00Z, the epoch the timestamp cases are written around. */
    private val june2026Seconds = 1_780_272_000L

    // ---- row kinds ------------------------------------------------------------

    @Test
    fun `a user session keeps its fields and denormalises a microsecond timestamp`() {
        val sessions = parseSessions(
            payload(
                """
                {
                  "session_list": [
                    {
                      "talker_id": 1234567,
                      "session_type": 1,
                      "is_follow": 1,
                      "unread_count": 3,
                      "session_ts": 1780272000000000,
                      "last_msg": {
                        "sender_uid": 1234567,
                        "receiver_type": 1,
                        "receiver_id": 99,
                        "msg_type": 1,
                        "content": "{\"text\":\"在吗\"}",
                        "msg_seqno": 12,
                        "timestamp": 1780271000
                      },
                      "system_msg_type": 0,
                      "live_status": 0,
                      "top_ts": 0,
                      "is_dnd": 0
                    }
                  ],
                  "has_more": 1,
                  "system_msg": {}
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals(1234567L, session.talkerId)
        assertEquals(june2026Seconds, session.timeSeconds)
        assertEquals(3, session.unreadCount)
        assertEquals("在吗", session.preview)
        assertEquals("", session.name)
        assertEquals("", session.face)
        assertFalse(session.isGroup)
        assertFalse(session.isSystem)
        assertFalse(session.isLive)
        assertNull(session.bvid)
    }

    @Test
    fun `a fan club session takes its name and cover from the group fields`() {
        val session = parseSessions(
            payload(
                """
                {
                  "session_list": [
                    {
                      "talker_id": 88,
                      "session_type": 2,
                      "group_name": "小明的粉丝团",
                      "group_cover": "http://i0.hdslb.com/bfs/group.jpg",
                      "unread_count": 0,
                      "session_ts": 1780272000000000,
                      "last_msg": { "msg_type": 1, "content": "{\"text\":\"欢迎加入\"}" }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        ).first()

        assertEquals("小明的粉丝团", session.name)
        assertEquals("http://i0.hdslb.com/bfs/group.jpg", session.face)
        assertTrue(session.isGroup)
        assertFalse(session.isSystem)
    }

    @Test
    fun `an official session takes its name and face from account_info`() {
        val sessions = parseSessions(
            payload(
                """
                {
                  "session_list": [
                    {
                      "talker_id": 1,
                      "session_type": 1,
                      "account_info": {
                        "name": "创作助手",
                        "pic_url": "https://i0.hdslb.com/bfs/assistant.png"
                      },
                      "system_msg_type": 7,
                      "live_status": 1,
                      "unread_count": 1,
                      "session_ts": 1780272000000000,
                      "last_msg": {
                        "msg_type": 10,
                        "content": "{\"title\":\"流量奖励到账通知\",\"text\":\"恭喜您已获得奖励\"}",
                        "timestamp": 1780271000
                      }
                    },
                    {
                      "talker_id": 2,
                      "session_type": 1,
                      "account_info": { "name": "社区中心", "pic_url": "https://i0.hdslb.com/bfs/community.png" },
                      "system_msg_type": 8,
                      "unread_count": 0,
                      "session_ts": 1780272000000000
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(2, sessions.size)
        val assistant = sessions.first()
        assertEquals("创作助手", assistant.name)
        assertEquals("https://i0.hdslb.com/bfs/assistant.png", assistant.face)
        assertTrue(assistant.isSystem)
        assertFalse(assistant.isGroup)
        assertTrue(assistant.isLive)
        // A msg_type 10 content document is a title plus body.
        assertEquals("流量奖励到账通知：恭喜您已获得奖励", assistant.preview)
        assertEquals("社区中心", sessions[1].name)
        assertTrue(sessions[1].isSystem)
    }

    /** `account_info` wins over a group field on the same row. */
    @Test
    fun `account_info takes precedence over group fields`() {
        val session = parseSessions(
            payload(
                """
                {
                  "session_list": [
                    {
                      "talker_id": 3,
                      "session_type": 2,
                      "group_name": "粉丝团",
                      "group_cover": "http://i0.hdslb.com/bfs/group.jpg",
                      "account_info": { "name": "客服消息", "pic_url": "https://i0.hdslb.com/bfs/cs.png" },
                      "system_msg_type": 8
                    }
                  ]
                }
                """.trimIndent(),
            ),
        ).first()

        assertEquals("客服消息", session.name)
        assertEquals("https://i0.hdslb.com/bfs/cs.png", session.face)
    }

    // ---- timestamps -----------------------------------------------------------

    @Test
    fun `session_ts accepts microseconds milliseconds and seconds`() {
        fun secondsOf(value: String): Long = parseSessions(
            payload("""{"session_list":[{"talker_id":9,"session_type":1,"session_ts":$value}]}"""),
        ).first().timeSeconds

        // The documented unit first, then the two the API has been seen using.
        assertEquals(june2026Seconds, secondsOf("1780272000000000"))
        assertEquals(june2026Seconds, secondsOf("1780272000000"))
        assertEquals(june2026Seconds, secondsOf("1780272000"))
    }

    @Test
    fun `an unusable session_ts falls back to the last message timestamp`() {
        val session = parseSessions(
            payload(
                """
                {
                  "session_list": [
                    {
                      "talker_id": 9,
                      "session_type": 1,
                      "session_ts": 1,
                      "last_msg": { "msg_type": 1, "content": "{\"text\":\"哦\"}", "timestamp": 1780271000 }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        ).first()

        assertEquals(1_780_271_000L, session.timeSeconds)
    }

    @Test
    fun `a missing or absurd timestamp becomes zero instead of a wild epoch`() {
        val missing = parseSessions(
            payload("""{"session_list":[{"talker_id":9,"session_type":1}]}"""),
        ).first()
        val absurd = parseSessions(
            payload("""{"session_list":[{"talker_id":9,"session_type":1,"session_ts":42}]}"""),
        ).first()

        assertEquals(0L, missing.timeSeconds)
        assertEquals(0L, absurd.timeSeconds)
    }

    // ---- previews -------------------------------------------------------------

    @Test
    fun `content with text decodes to that text`() {
        assertEquals("在吗", previewOf("{\"text\":\"在吗\"}"))
    }

    @Test
    fun `content with a title and text decodes to both`() {
        assertEquals(
            "流量奖励到账通知：恭喜您已获得奖励",
            previewOf("{\"title\":\"流量奖励到账通知\",\"text\":\"恭喜您已获得奖励\"}"),
        )
    }

    @Test
    fun `content with only a title decodes to the title`() {
        assertEquals("流量奖励到账通知", previewOf("{\"title\":\"流量奖励到账通知\"}"))
        // A title identical to the text is not repeated.
        assertEquals("通知", previewOf("{\"title\":\"通知\",\"text\":\"通知\"}"))
    }

    @Test
    fun `image content decodes to the image placeholder`() {
        assertEquals("[图片]", previewOf("{\"url\":\"https://i0.hdslb.com/bfs/a.jpg\",\"width\":720}"))
        assertEquals("[图片]", previewOf("{\"origin_image\":\"https://i0.hdslb.com/bfs/a.jpg\"}"))
        assertEquals("[图片]", previewOf("{\"thumb\":\"https://i0.hdslb.com/bfs/a.jpg\"}"))
    }

    @Test
    fun `an unrecognised document does not leak JSON braces into the row`() {
        assertEquals("[消息]", previewOf("{\"something\":{\"nested\":true}}"))
    }

    @Test
    fun `array content decodes to its text or a placeholder`() {
        assertEquals("对方撤回了一条消息", previewOf("[{\"text\":\"对方撤回了一条消息\"}]"))
        assertEquals("[消息]", previewOf("[]"))
        assertEquals("提示", previewOf("[\"提示\"]"))
    }

    @Test
    fun `plain non-JSON content stays as the raw text`() {
        assertEquals("[tv_doge] 你好", previewOf("[tv_doge] 你好"))
        assertEquals("在吗", previewOf("  在吗  "))
    }

    @Test
    fun `decoded text is whitespace-collapsed and capped`() {
        assertEquals("第一行 第二行", previewOf("{\"text\":\"第一行\\n第二行\"}"))

        val long = "啊".repeat(200)
        val capped = previewOf(long)
        assertTrue("preview should be capped, was ${capped.length}", capped.length <= 80)
        assertTrue("a capped preview should say so", capped.endsWith("…"))

        // Emoji tokens are not special-cased: they must survive verbatim.
        assertTrue(previewOf("[tv_doge] 很好").contains("[tv_doge]"))
    }

    @Test
    fun `empty content previews as empty`() {
        assertEquals("", previewOf(""))
        assertEquals("", previewOf("   "))
    }

    // ---- list-level tolerance -------------------------------------------------

    @Test
    fun `a null session_list yields an empty list`() {
        assertTrue(parseSessions(payload("""{"session_list":null,"has_more":0}""")).isEmpty())
    }

    @Test
    fun `an absent or mistyped session_list yields an empty list`() {
        assertTrue(parseSessions(null).isEmpty())
        assertTrue(parseSessions(payload("""{"has_more":0}""")).isEmpty())
        assertTrue(parseSessions(payload("""{"session_list":"nope"}""")).isEmpty())
        assertTrue(parseSessions(payload("""{"session_list":{}}""")).isEmpty())
    }

    @Test
    fun `a completely malformed payload never throws`() {
        listOf("[]", "\"text\"", "0", "null", "{}", "{\"session_list\":[1,2,3]}")
            .forEach { raw ->
                assertTrue("should be empty for: $raw", parseSessions(payload(raw)).isEmpty())
            }
    }

    @Test
    fun `one odd row does not drop the rest of the page`() {
        val sessions = parseSessions(
            payload(
                """
                {
                  "session_list": [
                    42,
                    { "talker_id": 7, "session_type": 1, "last_msg": { "msg_type": 1, "content": "hi" } },
                    { "session_type": 1 }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(2, sessions.size)
        assertEquals(7L, sessions.first().talkerId)
        assertEquals("hi", sessions.first().preview)
    }

    /**
     * A duplicate `LazyColumn` key crashes the list, so uniqueness is enforced
     * rather than assumed: a user row and a fan-club row may share a
     * `talker_id`, and the server answers `talker_id = 0` for some rows.
     */
    @Test
    fun `session ids stay unique for repeated and missing talker ids`() {
        val sessions = parseSessions(
            payload(
                """
                {
                  "session_list": [
                    { "talker_id": 5, "session_type": 1 },
                    { "talker_id": 5, "session_type": 2, "group_name": "粉丝团" },
                    { "talker_id": 0, "session_type": 1 },
                    { "talker_id": 0, "session_type": 1 }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(4, sessions.size)
        assertEquals(4, sessions.map { it.id }.distinct().size)
    }

    @Test
    fun `the payload wrapper used by some endpoints is tolerated`() {
        val sessions = parseSessions(
            payload("""{"data":{"session_list":[{"talker_id":11,"session_type":1}]}}"""),
        )
        assertEquals(1, sessions.size)
        assertEquals(11L, sessions.first().talkerId)
    }

    // ---- video references -----------------------------------------------------

    @Test
    fun `a shared video message exposes its bvid`() {
        val session = parseSessions(
            payload(
                """
                {
                  "session_list": [
                    {
                      "talker_id": 7,
                      "session_type": 1,
                      "last_msg": {
                        "msg_type": 7,
                        "content": "{\"bvid\":\"BV1xx411c7mD\",\"title\":\"这个视频\"}"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        ).first()

        assertEquals("BV1xx411c7mD", session.bvid)
        assertEquals("这个视频", session.preview)
    }

    // ---- card merging ---------------------------------------------------------

    private fun namelessSessions() = parseSessions(
        payload(
            """
            {
              "session_list": [
                { "talker_id": 1234567, "session_type": 1, "unread_count": 2 },
                { "talker_id": 88, "session_type": 2, "group_name": "小明的粉丝团" }
              ]
            }
            """.trimIndent(),
        ),
    )

    @Test
    fun `mergeUserCards fills a blank name and face`() {
        val sessions = namelessSessions()
        val merged = mergeUserCards(
            sessions,
            payload(
                """
                {
                  "code": 0,
                  "data": {
                    "1234567": {
                      "mid": 1234567,
                      "name": "小明",
                      "face": "https://i0.hdslb.com/bfs/face.jpg"
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("小明", merged[0].name)
        assertEquals("https://i0.hdslb.com/bfs/face.jpg", merged[0].face)
        // Everything else is left exactly as it was.
        assertEquals(2, merged[0].unreadCount)
        assertEquals(sessions[0].id, merged[0].id)
        assertEquals("小明的粉丝团", merged[1].name)
    }

    @Test
    fun `mergeUserCards accepts the bare uid-keyed object too`() {
        val merged = mergeUserCards(
            namelessSessions(),
            payload("""{"1234567":{"name":"小明"}}"""),
        )
        assertEquals("小明", merged[0].name)
        assertEquals("", merged[0].face)
    }

    @Test
    fun `mergeUserCards does not overwrite a resolved name`() {
        val merged = mergeUserCards(
            namelessSessions(),
            payload("""{"data":{"88":{"name":"不该出现"},"1234567":{"name":"小明"}}}"""),
        )
        assertEquals("小明的粉丝团", merged[1].name)
        assertEquals("小明", merged[0].name)
    }

    /** A missing, empty or mistyped card payload must not cost the screen a row. */
    @Test
    fun `mergeUserCards tolerates an empty or odd payload`() {
        val sessions = namelessSessions()
        listOf(
            null,
            payload("""{}"""),
            payload("""{"data":{}}"""),
            payload("""{"data":[]}"""),
            payload("""{"data":{"1234567":"oops"}}"""),
            payload("""{"data":{"1234567":null}}"""),
            payload("""[]"""),
            payload("""0"""),
        ).forEach { cards ->
            assertEquals("input changed for: $cards", sessions, mergeUserCards(sessions, cards))
        }
    }

    @Test
    fun `mergeUserCards handles an empty session list`() {
        assertEquals(emptyList<DirectMessageSession>(), mergeUserCards(emptyList(), payload("""{}""")))
    }
}
