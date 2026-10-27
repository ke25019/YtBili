package com.ytstyle.bili.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the field whose shape change broke login.
 *
 * The failure that motivated this: `/x/web-interface/nav` returns `vip_label` as
 * an **object** once signed in, but the model declared it as `String`. The whole
 * response failed to deserialise with *"Expected beginning of the string, but
 * got {"*, which surfaced to the user as 会话校验失败 — even though the session
 * cookies had already been stored and the account was in fact signed in.
 */
class FlexibleTextTest {

    private val json = NetworkModule.provideJson()

    @Test
    fun `accepts a plain string`() {
        val decoded = json.decodeFromString<Holder>("""{"vip_label":"大会员"}""")
        assertEquals("大会员", decoded.vipLabel)
    }

    /**
     * The exact shape from the failing response: an object carrying the display
     * text alongside a badge image path.
     */
    @Test
    fun `accepts an object and takes its text`() {
        val payload = """
            {"vip_label":{"path":"http://i0.hdslb.com/bfs/vip/label.png","text":"年度大会员",
             "label_theme":"annual_vip","text_color":"#FFFFFF","bg_style":1}}
        """.trimIndent()
        val decoded = json.decodeFromString<Holder>(payload)
        assertEquals("年度大会员", decoded.vipLabel)
    }

    /** An object with only a path yields an empty label, not a crash. */
    @Test
    fun `object without text yields empty`() {
        val decoded = json.decodeFromString<Holder>("""{"vip_label":{"path":"x.png"}}""")
        assertEquals("", decoded.vipLabel)
    }

    /** A missing field falls back to the declared default. */
    @Test
    fun `missing field uses the default`() {
        val decoded = json.decodeFromString<Holder>("""{}""")
        assertEquals("", decoded.vipLabel)
    }

    @Test
    fun `null yields empty`() {
        val decoded = json.decodeFromString<Holder>("""{"vip_label":null}""")
        assertEquals("", decoded.vipLabel)
    }

    /** A numeric value is coerced rather than rejected. */
    @Test
    fun `number is coerced to its textual form`() {
        val decoded = json.decodeFromString<Holder>("""{"vip_label":123}""")
        assertEquals("123", decoded.vipLabel)
    }

    /**
     * The decisive one: the full nav payload from a signed-in account must now
     * parse, including every other field the app relies on.
     */
    @Test
    fun `signed-in nav payload parses`() {
        val payload = """
            {"code":0,"message":"OK","ttl":1,"data":{
              "isLogin":true,"uname":"b站上没有名字","face":"https://i0.hdslb.com/bfs/face/x.jpg",
              "mid":123456,"money":895,"moral":70,
              "level_info":{"current_level":5,"current_min":10800,"next_exp":28800},
              "vipStatus":1,
              "vip_label":{"path":"http://i0.hdslb.com/bfs/vip/label.png","text":"年度大会员"},
              "wbi_img":{
                "img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
                "sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}
            }}
        """.trimIndent()

        val response = json.decodeFromString<BiliResponse<NavData>>(payload)

        assertTrue("must parse successfully", response.isSuccess)
        val nav = response.data!!
        assertTrue(nav.isLogin)
        assertEquals("b站上没有名字", nav.uname)
        assertEquals(5, nav.level_info?.currentLevel)
        assertEquals(1, nav.vipStatus)
        assertEquals("年度大会员", nav.vipLabel)
        // The WBI keys must survive too, or every signed request breaks.
        assertTrue(nav.wbiImg?.imgUrl.orEmpty().isNotEmpty())
    }

    /** Minimal wrapper so the serializer can be exercised on its own. */
    @Serializable
    private data class Holder(
        @Serializable(with = FlexibleTextSerializer::class)
        @SerialName("vip_label")
        val vipLabel: String = "",
    )
}
