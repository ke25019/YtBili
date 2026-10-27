package com.ytstyle.bili.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.ytstyle.bili.data.danmaku.DanmakuItem
import kotlin.math.max

/** Danmaku modes Bilibili uses. */
object DanmakuMode {
    const val SCROLL = 1
    const val BOTTOM = 4
    const val TOP = 5
}

/**
 * User-configurable danmaku appearance, mirroring Bilibili's own danmaku
 * settings sheet.
 */
data class DanmakuConfig(
    val enabled: Boolean = true,
    /** Font scale, 0.5x – 1.5x. */
    val scale: Float = 1f,
    /** Overall opacity, 0.3 – 1.0. */
    val opacity: Float = 1f,
    /** Fraction of the player height that may hold danmaku, 0.25 – 1.0. */
    val area: Float = 1f,
    /** Seconds for a comment to cross the screen; larger is slower. */
    val traverseSeconds: Float = 8f,
    /** Modes to render; empty means all. */
    val types: Set<Int> = setOf(DanmakuMode.SCROLL, DanmakuMode.BOTTOM, DanmakuMode.TOP),
    /** Speed multiplier from long-press fast-forward. */
    val speedMultiplier: Float = 1f,
)

/**
 * Renders danmaku over the video surface.
 *
 * Scrolling comments are assigned to "lanes": a comment takes the first lane
 * whose previous occupant will have cleared the right edge before this one
 * arrives. That is the same collision rule the official client uses.
 *
 * Three things exist specifically to keep the frame rate up, because the naive
 * version dropped frames badly:
 *
 *  1. **Text is measured once and cached.** Measuring every visible comment on
 *     every frame was the single biggest cost.
 *  2. **Comments are bucketed by second**, so a frame walks ~13 buckets instead
 *     of filtering the whole list.
 *  3. **The outline is a text shadow**, not eight offset stroke passes.
 *
 * [videoAspectRatio] is the width/height of the video itself; danmaku are
 * clipped to the letterboxed video rectangle so they never appear over the
 * black bars outside the picture.
 */
@Composable
fun DanmakuOverlay(
    items: List<DanmakuItem>,
    positionMs: () -> Long,
    isPlaying: Boolean,
    videoAspectRatio: Float?,
    config: DanmakuConfig,
    modifier: Modifier = Modifier,
) {
    if (!config.enabled || items.isEmpty() || config.types.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()

    // One state write per frame is what re-runs the draw block.
    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            withFrameNanos { frameNanos = it }
        }
    }

    val buckets = remember(items) { bucketBySecond(items) }
    val layouts = remember(items) { HashMap<DanmakuItem, TextLayoutResult>(items.size) }
    val styleCache = remember(items) { HashMap<Int, TextStyle>(8) }

    Canvas(modifier = modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") frameNanos

        val now = positionMs()
        val videoRect = fittedVideoRect(size, videoAspectRatio)
        if (videoRect.width <= 0f || videoRect.height <= 0f) return@Canvas

        // `area` shrinks the region comments may occupy, keeping the bottom of
        // the picture clear.
        val usableHeight = videoRect.height * config.area.coerceIn(0.25f, 1f)
        val laneHeight = dp(28)
        val laneCount = max(1, (usableHeight / laneHeight).toInt())
        val laneFreeAt = LongArray(laneCount)

        val traverse = (config.traverseSeconds / max(config.speedMultiplier, 0.25f))
            .coerceIn(2f, 20f) * 1000f

        val firstSecond = ((now - traverse.toLong()) / 1000L).toInt()
        val lastSecond = ((now + 300L) / 1000L).toInt()

        for (second in firstSecond..lastSecond) {
            val bucket = buckets[second] ?: continue
            for (item in bucket) {
                // Mode filter: Bilibili lets each type be switched off.
                if (item.mode !in config.types) continue

                val layout = layoutFor(item, layouts, textMeasurer, styleCache, config)
                when (item.mode) {
                    DanmakuMode.BOTTOM -> drawFixed(
                        layout = layout,
                        rect = videoRect,
                        y = videoRect.top + usableHeight - dp(40),
                    )

                    DanmakuMode.TOP -> drawFixed(
                        layout = layout,
                        rect = videoRect,
                        y = videoRect.top + dp(12),
                    )

                    else -> drawScrolling(
                        item = item,
                        layout = layout,
                        now = now,
                        rect = videoRect,
                        laneHeight = laneHeight,
                        laneFreeAt = laneFreeAt,
                        traverse = traverse,
                    )
                }
            }
        }
    }
}

/**
 * The rectangle the video actually occupies inside [container], assuming a
 * fit-centre resize. Danmaku are clipped to this so they never spill onto the
 * letterbox bars.
 */
internal fun fittedVideoRect(container: Size, aspectRatio: Float?): Rect {
    if (aspectRatio == null || aspectRatio <= 0f || container.width <= 0f || container.height <= 0f) {
        return Rect(0f, 0f, container.width, container.height)
    }
    val containerAspect = container.width / container.height
    return if (containerAspect > aspectRatio) {
        // Wider than the video: bars left and right.
        val width = container.height * aspectRatio
        val left = (container.width - width) / 2f
        Rect(left, 0f, left + width, container.height)
    } else {
        // Narrower than the video: bars above and below.
        val height = container.width / aspectRatio
        val top = (container.height - height) / 2f
        Rect(0f, top, container.width, top + height)
    }
}

/** Groups comments into one-second buckets keyed by their start second. */
private fun bucketBySecond(items: List<DanmakuItem>): Map<Int, List<DanmakuItem>> {
    val map = HashMap<Int, MutableList<DanmakuItem>>()
    items.forEach { item ->
        map.getOrPut((item.timeMs / 1000L).toInt()) { mutableListOf() }.add(item)
    }
    return map
}

/**
 * Returns the measured layout for [item], measuring on first use.
 *
 * The cache key includes the scale and opacity so that changing either setting
 * re-measures rather than reusing a stale layout.
 */
private fun layoutFor(
    item: DanmakuItem,
    cache: HashMap<DanmakuItem, TextLayoutResult>,
    measurer: TextMeasurer,
    styleCache: HashMap<Int, TextStyle>,
    config: DanmakuConfig,
): TextLayoutResult = cache.getOrPut(item) {
    val key = (item.fontSize shl 24) xor item.color xor
        (config.scale * 100f).toInt() xor (config.opacity * 100f).toInt()
    val style = styleCache.getOrPut(key) {
        TextStyle(
            fontSize = (item.fontSize * 0.62f * config.scale).sp,
            fontWeight = FontWeight.Medium,
            color = Color(item.color or 0xFF000000.toInt()).copy(alpha = config.opacity),
            // A single shadow replaces eight stroke passes.
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.85f * config.opacity),
                offset = Offset(0f, 0f),
                blurRadius = 3f,
            ),
        )
    }
    measurer.measure(text = item.text, style = style, maxLines = 1)
}

private fun DrawScope.drawScrolling(
    item: DanmakuItem,
    layout: TextLayoutResult,
    now: Long,
    rect: Rect,
    laneHeight: Float,
    laneFreeAt: LongArray,
    traverse: Float,
) {
    val textWidth = layout.size.width.toFloat()
    val elapsed = (now - item.timeMs).toFloat()
    if (elapsed < 0f || elapsed > traverse) return

    val progress = elapsed / traverse
    val x = rect.right - (rect.width + textWidth) * progress
    if (x > rect.right || x + textWidth < rect.left) return

    val lane = laneFreeAt.indexOfFirst { it <= item.timeMs }
    if (lane < 0) return
    laneFreeAt[lane] = item.timeMs + (textWidth / (rect.width + textWidth) * traverse).toLong()

    drawText(
        textLayoutResult = layout,
        topLeft = Offset(x, rect.top + lane * laneHeight + laneHeight * 0.25f),
    )
}

private fun DrawScope.drawFixed(
    layout: TextLayoutResult,
    rect: Rect,
    y: Float,
) {
    val x = rect.left + (rect.width - layout.size.width) / 2f
    if (x < rect.left) return
    drawText(textLayoutResult = layout, topLeft = Offset(x, y))
}

/** Converts dp to pixels inside a draw scope. */
private fun DrawScope.dp(value: Int): Float = value * density
