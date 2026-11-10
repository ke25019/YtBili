package com.ytstyle.bili.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ytstyle.bili.data.net.RecommendItem
import com.ytstyle.bili.data.net.UgcEpisode
import com.ytstyle.bili.data.net.UgcSeason
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.relativeTime
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.components.toThumbUrl
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * The two sections under the player.
 *
 * There used to be a third 发弹幕 tab; it became the inline pill in the header
 * ([DanmakuPill]) instead, which is both what the reference client does and one
 * fewer tap before a danmaku can be sent.
 */
enum class PlayerTab(val label: String) {
    Intro("简介"),
    Comments("评论"),
}

/**
 * Tab row with the danmaku composer on its right.
 *
 * [trailing] is a slot rather than a parameter because the pill needs the whole
 * danmaku state and its callbacks, and threading those through here would make
 * this row know about things it has no business knowing.
 */
@Composable
fun PlayerTabRow(
    selected: PlayerTab,
    onSelect: (PlayerTab) -> Unit,
    commentCount: String = "",
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerTab.entries.forEach { entry ->
                val active = entry == selected
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (entry == PlayerTab.Comments && commentCount.isNotEmpty()) {
                            "${entry.label} $commentCount"
                        } else {
                            entry.label
                        },
                        color = if (active) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(26.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (active) YtColors.Red else Color.Transparent),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.divider))
    }
}

/**
 * The inline danmaku composer that sits in the player's header.
 *
 * Compact by design: a pill the width of the space left over, with the colour
 * picker and the danmaku on/off switch beside it. Tapping it opens the
 * keyboard; the IME's 发送 action posts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DanmakuPill(
    state: PlayerUiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleDanmaku: () -> Unit,
    /** Opens the 字体大小 / 位置 / 颜色 panel that sits under this row. */
    onToggleSendPanel: () -> Unit,
    /** True while that panel is open, so the dot reads as armed. */
    sendPanelOpen: Boolean = false,
    /** Reports focus so the player can pause while the user is typing. */
    onComposingChange: (Boolean) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    /**
     * 是否正在输入弹幕 — deliberately strict.
     *
     * Focus alone is not proof that somebody is typing: the field keeps focus
     * after the keyboard has been dismissed, and pausing a video for a keyboard
     * that is not on screen is exactly the false positive this guards against.
     * Both signals have to agree.
     */
    val imeVisible = WindowInsets.isImeVisible
    val composing = focused && imeVisible

    // Typing pauses the video; see PlayerScreen's composing effect.
    LaunchedEffect(composing) { onComposingChange(composing) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .width(196.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(AppTheme.colors.chipBackground)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The colour dot now opens the full 字体大小 / 位置 / 颜色 panel
            // rather than a short list of colour names: colour is only one of
            // the three things the composer needs to set.
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleSendPanel),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .then(
                            if (state.danmakuVipGradient) {
                                Modifier.background(
                                    Brush.sweepGradient(
                                        listOf(
                                            Color(0xFFFF5C8A),
                                            Color(0xFFFFD166),
                                            Color(0xFF7CE38B),
                                            Color(0xFF66CCFF),
                                            Color(0xFF9B30FF),
                                            Color(0xFFFF5C8A),
                                        ),
                                    ),
                                )
                            } else {
                                Modifier.background(
                                    Color(state.danmakuColor.toInt() or 0xFF000000.toInt()),
                                )
                            },
                        )
                        .border(
                            width = if (sendPanelOpen) 2.dp else 1.dp,
                            color = if (sendPanelOpen) {
                                YtColors.Red
                            } else {
                                AppTheme.colors.divider
                            },
                            shape = CircleShape,
                        ),
                )
            }
            Spacer(Modifier.width(6.dp))

            BasicTextField(
                value = state.danmakuText,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                enabled = state.isLoggedIn,
                textStyle = TextStyle(fontSize = 12.sp, color = AppTheme.colors.onBackground),
                singleLine = true,
                cursorBrush = SolidColor(YtColors.Red),
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                // Sending ends the composing state even though the field keeps
                // focus, so the video resumes as soon as the danmaku is posted.
                keyboardActions = KeyboardActions(onSend = {
                    onComposingChange(false)
                    onSend()
                }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.danmakuText.isEmpty()) {
                            Text(
                                text = when {
                                    !state.isLoggedIn -> "登录后可发弹幕"
                                    focused -> "弹幕输入中"
                                    else -> "发个友善的弹幕见证当下"
                                },
                                color = AppTheme.colors.onBackgroundSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )

            if (state.isSendingDanmaku) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(
                    color = YtColors.Red,
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            } else if (state.danmakuText.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送弹幕",
                    tint = YtColors.Red,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable {
                            onComposingChange(false)
                            onSend()
                        },
                )
            }
        }

        Spacer(Modifier.width(4.dp))
        // The icon used to be `size(30.dp) ... clickable().padding(4.dp)`, which
        // left the glyph with 22dp of room while `Icon` still wanted its 24dp
        // default — so this one control scaled differently from every other icon
        // in the player. Now the touch target and the glyph are stated
        // separately and in the right order.
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleDanmaku),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Subtitles,
                contentDescription = if (state.danmakuEnabled) "关闭弹幕" else "打开弹幕",
                tint = if (state.danmakuEnabled) {
                    YtColors.Red
                } else {
                    AppTheme.colors.onBackgroundSecondary
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Superseded by [DanmakuPill].
 *
 * This was the 发弹幕 tab's own full-width composer. The tab is gone — the pill
 * in the player header replaced it — so nothing calls this any more. It is kept
 * for one release because a full-width composer is the obvious fallback if the
 * pill turns out to be too cramped on a small screen.
 */
@Composable
fun DanmakuComposer(
    state: PlayerUiState,
    /** Current playback position, so the composer can say where it will land. */
    positionMs: Long,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleDanmaku: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "发弹幕",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "弹幕会出现在当前播放位置（${formatClock(positionMs)}）。",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.danmakuText,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)),
                placeholder = {
                    Text(
                        text = "发一条友善的弹幕",
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
            if (state.isSendingDanmaku) {
                CircularProgressIndicator(
                    color = YtColors.Red,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = "发送弹幕",
                    tint = if (state.danmakuText.isBlank()) {
                        AppTheme.colors.onBackgroundSecondary
                    } else {
                        YtColors.Red
                    },
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(enabled = state.danmakuText.isNotBlank(), onClick = onSend)
                        .padding(2.dp),
                )
            }
        }

        state.danmakuFeedback?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                color = if (state.danmakuFeedbackIsError) {
                    YtColors.Red
                } else {
                    AppTheme.colors.onBackgroundSecondary
                },
                fontSize = 12.sp,
            )
        }

        if (!state.isLoggedIn) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "未登录：登录后才能发送弹幕。",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "显示弹幕", color = AppTheme.colors.onBackground, fontSize = 14.sp)
                Text(
                    text = "关掉后画面上的弹幕会隐藏，字号与速度在「设置 → 弹幕设置」",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.danmakuEnabled,
                onCheckedChange = { onToggleDanmaku() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = YtColors.Red,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = AppTheme.colors.divider,
                ),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The 合集 (UGC season) entry from the video detail payload.
 *
 * Collapsed it is a single row — "合集：<title> 3/368" — and expanding it lists
 * the episodes, which is what the official client does.
 */
@Composable
fun CollectionSection(
    season: UgcSeason,
    currentBvid: String,
    onOpenVideo: (String) -> Unit,
) {
    val episodes = season.allEpisodes
    if (episodes.isEmpty()) return

    var expanded by remember(season.id) { mutableStateOf(false) }
    val index = episodes.indexOfFirst { it.bvid == currentBvid }.let { if (it >= 0) it + 1 else 1 }

    Spacer(Modifier.height(14.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.colors.surfaceElevated),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "合集",
                color = YtColors.Red,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = season.title,
                color = AppTheme.colors.onBackground,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$index/${episodes.size}",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起合集" else "展开合集",
                tint = AppTheme.colors.onBackgroundSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        if (expanded) {
            episodes.forEachIndexed { index, episode ->
                CollectionEpisodeRow(
                    // The ordinal, not `episode.title`: on a UGC season that
                    // field holds the video's *title*, so rendering it in a
                    // 24dp column stacked one character per line.
                    number = index + 1,
                    episode = episode,
                    isCurrent = episode.bvid == currentBvid,
                    onClick = { onOpenVideo(episode.bvid) },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun CollectionEpisodeRow(
    number: Int,
    episode: UgcEpisode,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString(),
            color = if (isCurrent) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = episode.displayTitle,
            color = if (isCurrent) YtColors.Red else AppTheme.colors.onBackground,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "正在播放",
                tint = YtColors.Red,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Text(
                text = formatClock(episode.durationSeconds * 1000L),
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * The related-video rail.
 *
 * Only the first page the endpoint returns is shown; it is a "watch next"
 * suggestion list, not an infinite feed.
 */
@Composable
fun RelatedSection(
    videos: List<RecommendItem>,
    onOpenVideo: (String) -> Unit,
) {
    if (videos.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(18.dp))
        Text(
            text = "相关推荐",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(4.dp))
        videos.forEach { item ->
            RelatedRow(item = item, onClick = { onOpenVideo(item.bvid) })
        }
    }
}

@Composable
private fun RelatedRow(item: RecommendItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(168.dp)
                .height(95.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.surfaceElevated),
        ) {
            AsyncImage(
                model = item.pic.toThumbUrl(),
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().height(95.dp),
            )
            if (item.duration > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = formatClock(item.duration * 1000L),
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = AppTheme.colors.onBackground,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = item.owner?.face.orEmpty().toSecureUrl(),
                    contentDescription = item.owner?.name,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppTheme.colors.surfaceElevated),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.owner?.name.orEmpty(),
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(formatCount(item.stat?.view ?: 0))
                    append("次播放")
                    if (item.pubdate > 0) {
                        append(" · ")
                        append(relativeTime(item.pubdate))
                    }
                },
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 最热 / 最新 for the comment list. */
@Composable
fun ReplySortRow(
    current: ReplySort,
    onSelect: (ReplySort) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (current == ReplySort.Hot) "最热评论" else "最新评论",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
        )
        Spacer(Modifier.weight(1f))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ReplySort.entries.toList()) { option ->
                val selected = option == current
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) {
                                AppTheme.colors.chipSelected
                            } else {
                                AppTheme.colors.chipBackground
                            },
                        )
                        .clickable { onSelect(option) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = option.label,
                        color = if (selected) {
                            AppTheme.colors.chipSelectedContent
                        } else {
                            AppTheme.colors.onBackground
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (selected) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = AppTheme.colors.chipSelectedContent,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}
