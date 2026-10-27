package com.ytstyle.bili.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Tests for the display formatters used across the feed and player.
 *
 * The default locale is switched to one that uses a comma as the decimal
 * separator, which is what `formatCount` would have produced before it was
 * pinned to Locale.US.
 */
class FormattingTest {

    private val originalLocale = Locale.getDefault()

    @org.junit.After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `counts below ten thousand are shown verbatim`() {
        assertEquals("0", formatCount(0))
        assertEquals("1", formatCount(1))
        assertEquals("999", formatCount(999))
        assertEquals("9999", formatCount(9_999))
    }

    @Test
    fun `counts use wan grouping`() {
        assertEquals("1.0万", formatCount(10_000))
        assertEquals("1.2万", formatCount(12_345))
        assertEquals("100.0万", formatCount(1_000_000))
        assertEquals("9876.5万", formatCount(98_765_432))
    }

    /**
     * Documents a known rounding quirk at the 亿 boundary: a value just under
     * 100 million rounds up and renders as "10000.0万" rather than "9999.9万".
     * Cosmetic, and rare enough in practice to leave as-is.
     */
    @Test
    fun `value just under yi rounds up within wan`() {
        assertEquals("10000.0万", formatCount(99_999_999))
        assertEquals("1.0亿", formatCount(100_000_000))
    }

    @Test
    fun `counts use yi grouping at one hundred million`() {
        assertEquals("1.0亿", formatCount(100_000_000))
        assertEquals("1.5亿", formatCount(150_000_000))
        assertEquals("10.0亿", formatCount(1_000_000_000))
    }

    /** Guards against a locale leaking a comma into the decimal separator. */
    @Test
    fun `decimal separator is a dot regardless of default locale`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1.2万", formatCount(12_345))

        Locale.setDefault(Locale.FRANCE)
        assertEquals("1.2万", formatCount(12_345))
    }

    @Test
    fun `durations under an hour omit the hour field`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:05", formatDuration(5))
        assertEquals("0:59", formatDuration(59))
        assertEquals("1:00", formatDuration(60))
        assertEquals("1:30", formatDuration(90))
        assertEquals("59:59", formatDuration(3_599))
    }

    @Test
    fun `durations of an hour or more include hours`() {
        assertEquals("1:00:00", formatDuration(3_600))
        assertEquals("1:01:01", formatDuration(3_661))
        assertEquals("10:00:00", formatDuration(36_000))
    }

    @Test
    fun `duration strings from the api are converted to seconds`() {
        assertEquals(90, parseDurationString("1:30"))
        assertEquals(3_661, parseDurationString("1:01:01"))
        assertEquals(0, parseDurationString(""))
        assertEquals(0, parseDurationString("garbage"))
    }

    /**
     * Only 2- and 3-group forms are valid. Anything else is treated as
     * unparseable and yields 0, which renders as "0:00" rather than a
     * plausible-but-wrong duration.
     */
    @Test
    fun `duration parsing rejects unexpected shapes`() {
        assertEquals(0, parseDurationString("1:30:00:00"))
        assertEquals(0, parseDurationString("90"))
        assertEquals(0, parseDurationString("a:b"))
    }

    @Test
    fun `metadata line joins uploader views danmaku and age`() {
        val now = System.currentTimeMillis() / 1000
        val line = buildMetadataLine(
            uploader = "某UP主",
            views = 23_456,
            danmaku = 1_234,
            publishedAt = now - 7_200, // two hours ago
        )
        assertEquals("某UP主 · 2.3万次播放 · 1234弹幕 · 2小时前", line)
    }

    @Test
    fun `metadata line omits danmaku when there are none`() {
        val line = buildMetadataLine(
            uploader = "UP",
            views = 100,
            danmaku = 0,
            publishedAt = 0,
        )
        assertEquals("UP · 100次播放", line)
    }

    @Test
    fun `relative time buckets`() {
        val now = System.currentTimeMillis() / 1000
        assertEquals("刚刚", relativeTime(now - 10))
        assertEquals("5分钟前", relativeTime(now - 300))
        assertEquals("3小时前", relativeTime(now - 10_800))
        assertEquals("2天前", relativeTime(now - 172_800))
        assertEquals("2个月前", relativeTime(now - 5_184_000))
        assertEquals("1年前", relativeTime(now - 31_536_000))
    }
}
