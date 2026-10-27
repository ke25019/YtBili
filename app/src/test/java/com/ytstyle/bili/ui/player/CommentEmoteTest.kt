package com.ytstyle.bili.ui.player

import com.ytstyle.bili.data.net.EmoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the splitter that turns a comment containing `[token]` emotes into a
 * sequence of text runs and emote images.
 *
 * The important behaviour is that **unknown tokens survive as text**. Dropping
 * them would silently delete part of somebody's comment, and rendering them as
 * a broken image would be worse than showing the bracketed name Bilibili
 * actually writes.
 */
class CommentEmoteTest {

    private val laugh = EmoteItem(text = "[大笑]", url = "https://i0.hdslb.com/emote/laugh.png")
    private val cry = EmoteItem(text = "[大哭]", url = "https://i0.hdslb.com/emote/cry.png", gifUrl = "https://i0.hdslb.com/emote/cry.gif")
    private val emotes = mapOf("[大笑]" to laugh, "[大哭]" to cry)

    private fun texts(message: String, map: Map<String, EmoteItem>) =
        splitIntoSegments(message, map).filterIsInstance<CommentSegment.Text>().map { it.value }

    private fun emoteTokens(message: String, map: Map<String, EmoteItem>) =
        splitIntoSegments(message, map).filterIsInstance<CommentSegment.Emote>().map { it.emote.text }

    @Test
    fun `plain text is a single run`() {
        val segments = splitIntoSegments("没有表情的评论", emotes)
        assertEquals(1, segments.size)
        assertTrue(segments.first() is CommentSegment.Text)
    }

    /** With no emote map at all the message must pass through untouched. */
    @Test
    fun `unknown map leaves text intact`() {
        assertEquals(listOf("有大哭和大笑"), texts("有大哭和大笑", emptyMap()))
    }

    @Test
    fun `splits around a known emote`() {
        assertEquals(listOf("前面", "后面"), texts("前面[大笑]后面", emotes))
        assertEquals(listOf("[大笑]"), emoteTokens("前面[大笑]后面", emotes))
    }

    @Test
    fun `handles emotes at both ends`() {
        val segments = splitIntoSegments("[大笑]中间[大哭]", emotes)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is CommentSegment.Emote)
        assertEquals("中间", (segments[1] as CommentSegment.Text).value)
        assertTrue(segments[2] is CommentSegment.Emote)
    }

    @Test
    fun `handles adjacent emotes`() {
        assertEquals(listOf("[大笑]", "[大哭]"), emoteTokens("[大笑][大哭]", emotes))
    }

    /** An unrecognised token must stay in the text, not vanish. */
    @Test
    fun `keeps unknown tokens as text`() {
        val message = "这个[未知表情]不认得"
        assertEquals(listOf(message), texts(message, emotes))
        assertTrue(emoteTokens(message, emotes).isEmpty())
        // And the concatenation still equals the original.
        assertEquals(message, splitIntoSegments(message, emotes)
            .joinToString("") {
                when (it) {
                    is CommentSegment.Text -> it.value
                    is CommentSegment.Emote -> it.emote.text
                }
            })
    }

    /** An unclosed bracket must not be treated as a token. */
    @Test
    fun `unclosed bracket stays literal`() {
        val message = "这里有[大笑没有闭合"
        assertEquals(message, splitIntoSegments(message, emotes)
            .joinToString("") {
                when (it) {
                    is CommentSegment.Text -> it.value
                    is CommentSegment.Emote -> it.emote.text
                }
            })
    }

    /** The gif variant is preferred when the emote provides one. */
    @Test
    fun `prefers animated url when available`() {
        assertEquals("https://i0.hdslb.com/emote/cry.gif", cry.bestUrl)
        assertEquals("https://i0.hdslb.com/emote/laugh.png", laugh.bestUrl)
    }

    /** An emote with no URL must not be substituted for an empty image. */
    @Test
    fun `emote without url stays as text`() {
        val broken = mapOf("[坏]" to EmoteItem(text = "[坏]", url = ""))
        assertEquals(listOf("前[坏]后"), texts("前[坏]后", broken))
    }

    /** Reconstruction must always reproduce the original message. */
    @Test
    fun `segments always reconstruct the original`() {
        val samples = listOf(
            "普通评论",
            "[大笑]",
            "a[大笑]b[大哭]c",
            "[大笑][大哭]",
            "尾部[大笑]",
            "未知[呵呵]表情",
            "空[]方括号",
            "[大笑",
        )
        samples.forEach { message ->
            val rebuilt = splitIntoSegments(message, emotes).joinToString("") {
                when (it) {
                    is CommentSegment.Text -> it.value
                    is CommentSegment.Emote -> it.emote.text
                }
            }
            assertEquals("failed to reconstruct: $message", message, rebuilt)
        }
    }
}
