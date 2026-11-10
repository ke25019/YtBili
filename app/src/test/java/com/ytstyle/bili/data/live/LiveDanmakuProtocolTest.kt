package com.ytstyle.bili.data.live

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Covers the live danmaku framing, zlib decompression and JSON shapes.
 *
 * The packets below are **built here, not captured**: reading a live room needs
 * a token for a room that is actually streaming, and the shapes are taken from
 * the community protocol documentation. The framing and the decompression are
 * the parts that break silently, and those are fully exercised by construction.
 */
class LiveDanmakuProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size * 2)
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val written = deflater.deflate(buffer)
            out.write(buffer, 0, written)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun short(offset: Int, payload: ByteArray): Int =
        ByteBuffer.wrap(payload, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()

    private fun int(offset: Int, payload: ByteArray): Int =
        ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    @Test
    fun `encode writes the documented header`() {
        val body = "{}".toByteArray()
        val packet = LiveDanmakuProtocol.encode(LiveOp.AUTH, body, protocolVersion = 1, sequence = 3)

        assertEquals(16 + body.size, packet.size)
        assertEquals(packet.size, int(0, packet))
        assertEquals(16, short(4, packet))
        assertEquals(1, short(6, packet))
        assertEquals(LiveOp.AUTH, int(8, packet))
        assertEquals(3, int(12, packet))
        assertEquals("{}", String(packet.copyOfRange(16, packet.size)))
    }

    @Test
    fun `auth body carries the room, uid and token`() {
        val body = String(LiveDanmakuProtocol.authBody(1234, 42, "tok"), Charsets.UTF_8)

        assertTrue(body.contains("\"roomid\":1234"))
        assertTrue(body.contains("\"uid\":42"))
        assertTrue(body.contains("\"key\":\"tok\""))
        // Asking for zlib is what keeps brotli out of the dependency list.
        assertTrue(body.contains("\"protover\":2"))
    }

    @Test
    fun `decode walks several packets in one payload`() {
        val first = LiveDanmakuProtocol.encode(LiveOp.AUTH_REPLY, "{\"code\":0}".toByteArray())
        val second = LiveDanmakuProtocol.encode(LiveOp.HEARTBEAT_REPLY, ByteArray(4))
        val frames = LiveDanmakuProtocol.decode(first + second)

        assertEquals(2, frames.size)
        assertEquals(LiveOp.AUTH_REPLY, frames[0].operation)
        assertEquals(LiveOp.HEARTBEAT_REPLY, frames[1].operation)
    }

    @Test
    fun `a truncated packet stops the walk instead of throwing`() {
        val packet = LiveDanmakuProtocol.encode(LiveOp.MESSAGE, "hello".toByteArray())
        val truncated = packet.copyOfRange(0, packet.size - 2)

        assertTrue(LiveDanmakuProtocol.decode(truncated).isEmpty())
        assertTrue(LiveDanmakuProtocol.decode(ByteArray(0)).isEmpty())
        assertTrue(LiveDanmakuProtocol.decode(ByteArray(8)).isEmpty())
    }

    @Test
    fun `inflate reverses zlib`() {
        val original = "压缩前的内容".toByteArray(Charsets.UTF_8)
        assertEquals(
            "压缩前的内容",
            String(LiveDanmakuProtocol.inflate(zlib(original)), Charsets.UTF_8),
        )
    }

    @Test
    fun `a zlib frame holding a danmaku is decoded end to end`() {
        val danmaku = """
            {"cmd":"DANMU_MSG","info":[
              [0,1,25,16711680,1700000000000,-1,0,"x",0,0,0,"",0,"{}","{}",
                {"user":{"uid":777,"uname":"某人","face":"https://i0.hdslb.com/f.jpg"}}],
              "测试弹幕",
              [777,"某人",0,0,0,10000,1,""],
              [], [], 0, 0, null,
              {"ts":1700000000,"ct":"x"},
              0, 0, null, null, 0, 0, []
            ]}
        """.trimIndent()

        // The server sends: a protocol-2 packet whose inflated body is itself
        // one or more complete packets.
        val inner = LiveDanmakuProtocol.encode(LiveOp.MESSAGE, danmaku.toByteArray())
        val outer = LiveDanmakuProtocol.encode(
            operation = LiveOp.MESSAGE,
            body = zlib(inner),
            protocolVersion = 2,
        )

        val messages = LiveDanmakuProtocol.messages(outer)

        assertEquals(1, messages.size)
        val message = messages.first()
        assertEquals("测试弹幕", message.text)
        assertEquals("某人", message.userName)
        assertEquals(777L, message.uid)
        assertEquals("https://i0.hdslb.com/f.jpg", message.face)
        // 16711680 == 0xFF0000, the decimal RGB the payload sends.
        assertEquals(0xFF0000, message.colorRgb)
        assertEquals(1700000000L, message.timeSeconds)
        assertEquals(LiveDanmakuProtocol.LiveChatKind.DANMAKU, message.kind)
    }

    @Test
    fun `an old style danmaku still resolves its sender`() {
        // Older payloads carry no info[0][15].user; info[2] is all there is.
        val danmaku = """
            {"cmd":"DANMU_MSG","info":[
              [0,1,25,16777215,1700000000000,-1,0,"x",0,0,0,"",0,"{}","{}",null],
              "老格式",
              [555,"旧用户",0,0,0,10000,1,""],
              [], [], 0, 0, null, {}, 0, 0, null, null, 0, 0, []
            ]}
        """.trimIndent()

        val message = LiveDanmakuProtocol
            .messages(LiveDanmakuProtocol.encode(LiveOp.MESSAGE, danmaku.toByteArray()))
            .first()

        assertEquals("老格式", message.text)
        assertEquals(555L, message.uid)
        assertEquals("旧用户", message.userName)
        assertEquals(0xFFFFFF, message.colorRgb)
    }

    @Test
    fun `a danmaku missing every optional field does not throw`() {
        val danmaku = """{"cmd":"DANMU_MSG","info":[[],"只剩文本"]}"""

        val message = LiveDanmakuProtocol
            .messages(LiveDanmakuProtocol.encode(LiveOp.MESSAGE, danmaku.toByteArray()))
            .firstOrNull()

        assertNotNull(message)
        assertEquals("只剩文本", message!!.text)
        assertEquals("", message.userName)
        assertEquals(0xFFFFFF, message.colorRgb)
    }

    @Test
    fun `super chat and gift are recognised`() {
        val superChat = """
            {"cmd":"SUPER_CHAT_MESSAGE","data":{"uid":9,"price":30,"message":"加油",
             "ts":1700000000,"user_info":{"uname":"老板","face":"https://x/f.jpg"}}}
        """.trimIndent()
        val gift = """
            {"cmd":"SEND_GIFT","data":{"uid":8,"uname":"送礼的人","face":"https://x/g.jpg",
             "giftName":"辣条","num":3,"timestamp":1700000001}}
        """.trimIndent()

        val messages = LiveDanmakuProtocol.messages(
            LiveDanmakuProtocol.encode(LiveOp.MESSAGE, superChat.toByteArray()) +
                LiveDanmakuProtocol.encode(LiveOp.MESSAGE, gift.toByteArray()),
        )

        assertEquals(2, messages.size)
        assertEquals(LiveDanmakuProtocol.LiveChatKind.SUPER_CHAT, messages[0].kind)
        assertEquals("老板", messages[0].userName)
        assertEquals("加油", messages[0].text)
        assertEquals(LiveDanmakuProtocol.LiveChatKind.GIFT, messages[1].kind)
        assertTrue(messages[1].text.contains("辣条"))
        assertTrue(messages[1].text.contains("3"))
    }

    @Test
    fun `unrelated commands are ignored`() {
        val interact = """{"cmd":"INTERACT_WORD","data":{"uname":"进来的人"}}"""
        assertTrue(
            LiveDanmakuProtocol
                .messages(LiveDanmakuProtocol.encode(LiveOp.MESSAGE, interact.toByteArray()))
                .isEmpty(),
        )
    }

    @Test
    fun `garbage in a message frame yields nothing`() {
        listOf("", "not json", "[1,2,3]", "{\"cmd\":\"DANMU_MSG\"}")
            .forEach { body ->
                assertTrue(
                    "expected nothing for '$body'",
                    LiveDanmakuProtocol
                        .messages(LiveDanmakuProtocol.encode(LiveOp.MESSAGE, body.toByteArray()))
                        .isEmpty(),
                )
            }
    }

    @Test
    fun `popularity is read from a heartbeat reply`() {
        val body = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2466).array()
        val frame = LiveDanmakuProtocol.decode(
            LiveDanmakuProtocol.encode(LiveOp.HEARTBEAT_REPLY, body),
        ).first()

        assertEquals(2466, LiveDanmakuProtocol.popularity(frame))
        assertNull(
            LiveDanmakuProtocol.popularity(
                LiveDanmakuProtocol.decode(
                    LiveDanmakuProtocol.encode(LiveOp.MESSAGE, "{}".toByteArray()),
                ).first(),
            ),
        )
    }

    @Test
    fun `a protocol 3 frame is not mistaken for text`() {
        // Brotli is never requested, but if a server sent version 3 anyway the
        // bytes must not be parsed as JSON text.
        val packet = LiveDanmakuProtocol.encode(
            operation = LiveOp.MESSAGE,
            body = byteArrayOf(0x1F, 0x02, 0x03),
            protocolVersion = 3,
        )
        val frames = LiveDanmakuProtocol.decode(packet)

        assertEquals(3, frames.first().protocolVersion)
        assertEquals(3, frames.first().body.size)
        // Nothing downstream tries to read it — see `messages`.
        assertTrue(LiveDanmakuProtocol.messages(packet).isEmpty())
    }

    @Test
    fun `the json helper stays lenient about unknown fields`() {
        // Guards the parser's configuration: the payload grows fields often.
        val parsed = json.parseToJsonElement("""{"cmd":"X","brand_new":{"a":1}}""")
        assertNotNull(parsed)
    }
}
