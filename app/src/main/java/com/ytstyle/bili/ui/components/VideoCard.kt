package com.ytstyle.bili.ui.components

import com.ytstyle.bili.ui.theme.AppTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ytstyle.bili.data.net.RecommendItem
import com.ytstyle.bili.data.net.SpaceVideo
import com.ytstyle.bili.ui.theme.YtColors

/**
 * YouTube's signature home-feed card: full-bleed 16:9 thumbnail, duration
 * badge bottom-right, then a channel avatar beside a two-line title and a
 * grey metadata line. Bilibili's own cards look nothing like this.
 */
@Composable
fun VideoCard(
    title: String,
    thumbnailUrl: String,
    uploaderName: String,
    uploaderAvatar: String,
    viewCount: Long,
    danmakuCount: Long,
    durationSeconds: Int,
    publishedAt: Long,
    modifier: Modifier = Modifier,
    /**
     * Grid variant: tighter padding and no avatar, since a two-column cell has
     * no room for one.
     */
    compact: Boolean = false,
    onClick: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(AppTheme.colors.surfaceElevated),
        ) {
            AsyncImage(
                model = thumbnailUrl.toThumbUrl(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            DurationBadge(
                seconds = durationSeconds,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 8.dp else 12.dp,
                    vertical = if (compact) 8.dp else 10.dp,
                ),
        ) {
            if (!compact) {
                AsyncImage(
                    model = uploaderAvatar.toSecureUrl(),
                    contentDescription = uploaderName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceElevated),
                )

                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = AppTheme.colors.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildMetadataLine(
                        uploader = uploaderName,
                        views = viewCount,
                        danmaku = danmakuCount,
                        publishedAt = publishedAt,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Compact card used in search results, history and channel pages. */
@Composable
fun VideoRow(
    title: String,
    thumbnailUrl: String,
    uploaderName: String,
    viewCount: Long,
    durationSeconds: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(160.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.surfaceElevated),
        ) {
            AsyncImage(
                model = thumbnailUrl.toThumbUrl(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            DurationBadge(
                seconds = durationSeconds,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$uploaderName · ${formatCount(viewCount)}次播放",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DurationBadge(seconds: Int, modifier: Modifier = Modifier) {
    if (seconds <= 0) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = formatDuration(seconds),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** "1.2万次播放 · 3小时前" — Bilibili counts, YouTube-style phrasing. */
fun buildMetadataLine(
    uploader: String,
    views: Long,
    danmaku: Long,
    publishedAt: Long,
): String = buildString {
    append(uploader)
    append(" · ")
    append(formatCount(views))
    append("次播放")
    if (danmaku > 0) {
        append(" · ")
        append(formatCount(danmaku))
        append("弹幕")
    }
    if (publishedAt > 0) {
        append(" · ")
        append(relativeTime(publishedAt))
    }
}

/**
 * Chinese 万/亿 grouping, matching how Bilibili renders big numbers.
 *
 * Locale.US is explicit so the decimal separator is always "." — relying on the
 * default locale would render "1,2万" in locales that use a comma.
 */
fun formatCount(value: Long): String = when {
    value >= 100_000_000 -> "%.1f亿".format(java.util.Locale.US, value / 100_000_000.0)
    value >= 10_000 -> "%.1f万".format(java.util.Locale.US, value / 10_000.0)
    else -> value.toString()
}

fun formatDuration(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun relativeTime(epochSeconds: Long): String {
    val diff = System.currentTimeMillis() / 1000 - epochSeconds
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60}分钟前"
        diff < 86_400 -> "${diff / 3600}小时前"
        diff < 2_592_000 -> "${diff / 86_400}天前"
        diff < 31_536_000 -> "${diff / 2_592_000}个月前"
        else -> "${diff / 31_536_000}年前"
    }
}

/**
 * Normalises a Bilibili thumbnail URL for display.
 *
 * Two things are load-bearing here:
 *
 *  1. **The recommendation endpoint returns `http://` thumbnails.** The app sets
 *     `cleartextTrafficPermitted="false"`, so Coil silently fails on them and
 *     the feed renders with no images at all. Bilibili's image CDNs serve the
 *     same assets over TLS, so the scheme is upgraded rather than weakening the
 *     network policy.
 *  2. Bilibili serves whatever size the URL asks for; the `@480w_270h` suffix
 *     stops the feed from pulling multi-megabyte originals.
 */
fun String.toThumbUrl(): String {
    if (isBlank()) return this
    val secure = when {
        startsWith("//") -> "https:$this"
        startsWith("http://") -> "https://" + removePrefix("http://")
        else -> this
    }
    return if (secure.contains("@")) secure else "$secure@480w_270h_1c.webp"
}

/**
 * Scheme-only normalisation for images that must keep their native aspect
 * (avatars). Prefer [toThumbUrl] for thumbnails, which also requests a size.
 */
fun String.toSecureUrl(): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http://") -> "https://" + removePrefix("http://")
    else -> this
}

/** Convenience for rendering either feed type with one card. */
fun RecommendItem.toCardModel() = CardModel(
    title = title,
    thumbnail = pic,
    uploader = owner?.name.orEmpty(),
    avatar = owner?.face.orEmpty(),
    views = stat?.view ?: 0,
    danmaku = stat?.danmaku ?: 0,
    duration = duration,
    publishedAt = pubdate,
)

fun SpaceVideo.toCardModel() = CardModel(
    title = title,
    thumbnail = pic,
    uploader = "",
    avatar = "",
    views = play,
    danmaku = 0,
    duration = parseDurationString(length),
    publishedAt = created,
)

data class CardModel(
    val title: String,
    val thumbnail: String,
    val uploader: String,
    val avatar: String,
    val views: Long,
    val danmaku: Long,
    val duration: Int,
    val publishedAt: Long,
)

/** Space listings return duration as "12:34", not seconds. */
fun parseDurationString(value: String): Int {
    val parts = value.split(':').mapNotNull { it.toIntOrNull() }
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> 0
    }
}
