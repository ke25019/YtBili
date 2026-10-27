package com.ytstyle.bili.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ytstyle.bili.data.net.EmoteItem
import com.ytstyle.bili.data.net.Reply
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.relativeTime
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * Title, uploader row and the action bar.
 *
 * Coin and favourite are drawn as filled buttons with a label and a count
 * rather than the small grey chips used previously, so their state is
 * unmistakable. Quality selection moved into an overflow menu.
 */
@Composable
fun VideoInfoSection(
    state: PlayerUiState,
    onLike: () -> Unit,
    onCoin: () -> Unit,
    onFavorite: () -> Unit,
    onFollow: () -> Unit,
    onToggleAudioOnly: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onQuality: (Quality) -> Unit,
    onShare: () -> Unit,
    onUploaderClick: () -> Unit,
    onCastClick: () -> Unit,
    onWatchLater: () -> Unit,
) {
    val detail = state.detail ?: return
    val stat = detail.stat
    var qualityMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = detail.title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append(formatCount(stat?.view ?: 0))
                append("次播放 · ")
                append(relativeTime(detail.pubdate))
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onBackgroundSecondary,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onUploaderClick)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = detail.owner?.face.orEmpty().toSecureUrl(),
                contentDescription = detail.owner?.name,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.surfaceElevated),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detail.owner?.name.orEmpty(),
                    color = AppTheme.colors.onBackground,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "查看个人主页",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                )
            }
            PillButton(
                label = if (state.isFollowing) "已关注" else "关注",
                filled = !state.isFollowing,
                onClick = onFollow,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AppTheme.colors.onBackgroundSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryAction(
                modifier = Modifier.weight(1f),
                icon = if (state.isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                label = if (state.isLiked) "已点赞" else "点赞",
                count = stat?.like ?: 0,
                active = state.isLiked,
                activeTint = YtColors.Red,
                onClick = onLike,
            )
            PrimaryAction(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.MonetizationOn,
                label = if (state.coinsGiven > 0) "已投${state.coinsGiven}币" else "投币",
                count = stat?.coin ?: 0,
                active = state.coinsGiven > 0,
                activeTint = YtColors.CoinColor,
                onClick = onCoin,
            )
            // Bilibili uses a filled star for favourites rather than a heart.
            PrimaryAction(
                modifier = Modifier.weight(1f),
                icon = if (state.isFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                label = if (state.isFavorited) "已收藏" else "收藏",
                count = stat?.favorite ?: 0,
                active = state.isFavorited,
                activeTint = YtColors.FavColor,
                onClick = onFavorite,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallAction(
                icon = Icons.Filled.Share,
                label = "分享",
                active = false,
                onClick = onShare,
            )
            SmallAction(
                icon = Icons.Filled.Headphones,
                label = if (state.audioOnly) "仅音频" else "听视频",
                active = state.audioOnly,
                onClick = onToggleAudioOnly,
            )
            SmallAction(
                icon = Icons.Filled.Subtitles,
                label = if (state.danmakuEnabled) "弹幕开" else "弹幕关",
                active = state.danmakuEnabled,
                onClick = onToggleDanmaku,
            )
            SmallAction(
                icon = if (state.isInWatchLater) {
                    Icons.Filled.WatchLater
                } else {
                    Icons.Outlined.WatchLater
                },
                label = "稍后再看",
                active = state.isInWatchLater,
                onClick = onWatchLater,
            )
            SmallAction(
                icon = Icons.Filled.Cast,
                label = "投屏",
                active = false,
                onClick = onCastClick,
            )

            Box {
                SmallAction(
                    icon = Icons.Filled.HighQuality,
                    label = state.currentQuality.label,
                    active = false,
                    trailingArrow = true,
                    onClick = { qualityMenuOpen = true },
                )
                DropdownMenu(
                    expanded = qualityMenuOpen,
                    onDismissRequest = { qualityMenuOpen = false },
                    containerColor = AppTheme.colors.surfaceElevated,
                ) {
                    Text(
                        text = "清晰度",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                    state.availableQualities.forEach { quality ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = quality.label,
                                    color = if (quality == state.currentQuality) {
                                        YtColors.Red
                                    } else {
                                        AppTheme.colors.onBackground
                                    },
                                    fontSize = 14.sp,
                                )
                            },
                            trailingIcon = {
                                if (quality == state.currentQuality) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = YtColors.Red,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            },
                            onClick = {
                                qualityMenuOpen = false
                                onQuality(quality)
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = AppTheme.colors.divider)
    }
}

/**
 * A primary action: large icon over a bold label and a count.
 *
 * The active state changes both the tint and the background, so "已投2币" or
 * "已收藏" is obvious at a glance.
 */
@Composable
private fun PrimaryAction(
    icon: ImageVector,
    label: String,
    count: Long,
    active: Boolean,
    activeTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) activeTint.copy(alpha = 0.16f) else AppTheme.colors.surfaceElevated,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) activeTint else AppTheme.colors.onBackground,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = if (active) activeTint else AppTheme.colors.onBackground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            text = formatCount(count),
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SmallAction(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    trailingArrow: Boolean = false,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) AppTheme.colors.chipSelected else AppTheme.colors.chipBackground,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) {
                AppTheme.colors.chipSelectedContent
            } else {
                AppTheme.colors.onBackground
            },
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = if (active) {
                AppTheme.colors.chipSelectedContent
            } else {
                AppTheme.colors.onBackground
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        if (trailingArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AppTheme.colors.onBackgroundSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (filled) YtColors.Red else AppTheme.colors.chipBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (filled) Color.White else AppTheme.colors.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun CommentsSection(
    state: PlayerUiState,
    onReplyTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onLikeReply: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "评论 ${state.detail?.stat?.reply?.let(::formatCount) ?: ""}",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.replyText,
                onValueChange = onReplyTextChange,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)),
                placeholder = {
                    Text(
                        "添加评论...",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 13.sp,
                    )
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AppTheme.colors.chipBackground,
                    unfocusedContainerColor = AppTheme.colors.chipBackground,
                    focusedTextColor = AppTheme.colors.onBackground,
                    unfocusedTextColor = AppTheme.colors.onBackground,
                    cursorColor = YtColors.Red,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(8.dp))
            if (state.isSendingReply) {
                CircularProgressIndicator(
                    color = YtColors.Red,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = "发送",
                    color = if (state.replyText.isBlank()) {
                        AppTheme.colors.onBackgroundSecondary
                    } else {
                        YtColors.Red
                    },
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(enabled = state.replyText.isNotBlank(), onClick = onSend)
                        .padding(8.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        state.replies.forEach { reply ->
            CommentRow(
                reply = reply,
                globalEmotes = state.emotes,
                onLike = { onLikeReply(reply.rpid) },
            )
        }

        if (state.replies.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Comment,
                    contentDescription = null,
                    tint = AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "还没有评论",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CommentRow(
    reply: Reply,
    globalEmotes: Map<String, EmoteItem>,
    onLike: () -> Unit,
) {
    val content = reply.content
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        AsyncImage(
            model = reply.member?.avatar.orEmpty().toSecureUrl(),
            contentDescription = reply.member?.uname,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceElevated),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reply.member?.uname.orEmpty(),
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = relativeTime(reply.ctime),
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(4.dp))

            // Emotes attached to the reply take precedence; the panel map (only
            // populated when signed in) fills in the remaining tokens.
            CommentMessage(
                message = content?.message.orEmpty(),
                emotes = (content?.emote.orEmpty()) + globalEmotes,
            )

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.ThumbUp,
                    contentDescription = "点赞",
                    tint = AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.size(16.dp).clickable(onClick = onLike),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = reply.like.toString(),
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * Renders a comment, substituting `[token]` sequences with the emote image.
 *
 * A `FlowRow` of text runs and emote images is used rather than
 * `AnnotatedString` inline content: `appendInlineContent` is not present in the
 * Compose UI Text version this project builds against, so the inline-content
 * route does not compile.
 *
 * Tokens with no known image are left as literal text. Bilibili writes emotes
 * as bracketed names, so leaving them is readable and honest, whereas dropping
 * them would silently delete part of somebody's comment.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentMessage(message: String, emotes: Map<String, EmoteItem>) {
    val segments = remember(message, emotes) { splitIntoSegments(message, emotes) }

    // Nothing to substitute: render as a normal paragraph.
    if (segments.all { it is CommentSegment.Text }) {
        Text(
            text = message,
            color = AppTheme.colors.onBackground,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        return
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is CommentSegment.Text -> Text(
                    text = segment.value,
                    color = AppTheme.colors.onBackground,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )

                is CommentSegment.Emote -> AsyncImage(
                    model = segment.emote.bestUrl.toSecureUrl(),
                    contentDescription = segment.emote.text,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A run of either plain text or a single emote image. Internal so it can be tested. */
internal sealed interface CommentSegment {
    data class Text(val value: String) : CommentSegment
    data class Emote(val emote: EmoteItem) : CommentSegment
}

// The emote splitter is covered by CommentEmoteTest.

/**
 * Splits [message] on `[token]` boundaries, resolving tokens through [emotes].
 *
 * Unknown tokens stay inside the surrounding text run so the comment reads
 * exactly as written.
 */
internal fun splitIntoSegments(
    message: String,
    emotes: Map<String, EmoteItem>,
): List<CommentSegment> {
    if (emotes.isEmpty() || !message.contains('[')) {
        return listOf(CommentSegment.Text(message))
    }

    val segments = mutableListOf<CommentSegment>()
    val pending = StringBuilder()
    var index = 0

    fun flushText() {
        if (pending.isNotEmpty()) {
            segments += CommentSegment.Text(pending.toString())
            pending.clear()
        }
    }

    while (index < message.length) {
        val open = message.indexOf('[', index)
        if (open < 0) {
            pending.append(message, index, message.length)
            break
        }
        val close = message.indexOf(']', open)
        if (close < 0) {
            pending.append(message, index, message.length)
            break
        }

        pending.append(message, index, open)
        val token = message.substring(open, close + 1)
        val emote = emotes[token]

        if (emote != null && emote.bestUrl.isNotEmpty()) {
            flushText()
            segments += CommentSegment.Emote(emote)
        } else {
            pending.append(token)
        }
        index = close + 1
    }
    flushText()

    return segments.ifEmpty { listOf(CommentSegment.Text(message)) }
}

/** Horizontal quality chips; retained for reuse outside the overflow menu. */
@Composable
internal fun QualityRow(
    qualities: List<Quality>,
    current: Quality,
    onSelect: (Quality) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(qualities) { quality ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (quality == current) {
                            AppTheme.colors.chipSelected
                        } else {
                            AppTheme.colors.chipBackground
                        },
                    )
                    .clickable { onSelect(quality) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = quality.label,
                    color = if (quality == current) {
                        AppTheme.colors.chipSelectedContent
                    } else {
                        AppTheme.colors.onBackground
                    },
                    fontSize = 12.sp,
                )
            }
        }
    }
}
