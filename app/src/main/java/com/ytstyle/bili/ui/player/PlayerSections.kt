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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.ytstyle.bili.data.net.EmoteItem
import com.ytstyle.bili.data.net.PgcEpisode
import com.ytstyle.bili.data.net.Reply
import com.ytstyle.bili.data.net.ReplyPicture
import com.ytstyle.bili.ui.components.ImageViewer
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.relativeTime
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.components.toThumbUrl
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * Title, uploader row and the action bar.
 *
 * Three things changed together here:
 *
 *  - 点赞 / 投币 / 收藏 are now **icon + number only**. The label under each
 *    button was redundant with the icon and made the row tall and cramped on a
 *    phone; the active state is carried by the tint and the count instead.
 *  - The numbers come from [PlayerUiState.likeCount] and friends, i.e. the
 *    server's snapshot plus whatever this session changed, so a tap moves the
 *    number rather than only the icon.
 *  - Bangumi ([PlayerUiState.isPgc]) gets an episode strip and no archive
 *    actions, because like/coin/favourite are not archive operations there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoInfoSection(
    state: PlayerUiState,
    onLike: () -> Unit,
    onCoin: () -> Unit,
    onFavorite: () -> Unit,
    onFollow: () -> Unit,
    onToggleAudioOnly: () -> Unit,
    onQuality: (Quality) -> Unit,
    onShare: () -> Unit,
    onUploaderClick: () -> Unit,
    onCastClick: () -> Unit,
    onWatchLater: () -> Unit,
    onEpisodeClick: (PgcEpisode) -> Unit = {},
    /** Opens the offline-cache picker for this video. */
    onDownload: () -> Unit = {},
) {
    val detail = state.detail ?: return
    val stat = detail.stat
    var qualityMenuOpen by remember { mutableStateOf(false) }
    var descriptionExpanded by remember { mutableStateOf(false) }

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
                if (state.isPgc) {
                    append(state.seasonTypeName.ifEmpty { "番剧" })
                    append(" · 全")
                    append(state.episodes.size)
                    append("话")
                } else {
                    append(relativeTime(detail.pubdate))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onBackgroundSecondary,
        )

        // Long 简介 blocks. Shown collapsed to three lines with an explicit
        // toggle rather than pretending the whole blurb is worth the space.
        if (detail.desc.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail.desc,
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (descriptionExpanded) "收起" else "展开简介",
                color = AppTheme.colors.onBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable { descriptionExpanded = !descriptionExpanded }
                    .padding(vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !state.isPgc, onClick = onUploaderClick)
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
                    text = detail.owner?.name.orEmpty().ifEmpty { "哔哩哔哩番剧" },
                    color = AppTheme.colors.onBackground,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (state.isPgc) "出品 · 内容由版权方提供" else "查看个人主页",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                )
            }
            // 番剧 uses this slot for 追番: the season is what to subscribe to
            // there, and following the studio's account is a different relation
            // that nobody asks for from a play page.
            val subscribed = if (state.isPgc) state.seasonFollowed else state.isFollowing
            PillButton(
                label = when {
                    state.isPgc && subscribed -> "已追番"
                    state.isPgc -> "追番"
                    subscribed -> "已关注"
                    else -> "关注"
                },
                filled = !subscribed,
                onClick = onFollow,
                followed = subscribed,
            )
            if (!state.isPgc) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (state.isPgc) {
            Text(
                text = "番剧的点赞、投币与收藏在官方客户端中操作；这里可以直接观看与发送弹幕。",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryAction(
                    modifier = Modifier.weight(1f),
                    icon = if (state.isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    label = if (state.isLiked) "已点赞" else "点赞",
                    count = state.likeCount,
                    active = state.isLiked,
                    activeTint = YtColors.Red,
                    onClick = onLike,
                )
                PrimaryAction(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.MonetizationOn,
                    label = if (state.coinsGiven > 0) "已投${state.coinsGiven}币" else "投币",
                    count = state.coinCount,
                    active = state.coinsGiven > 0,
                    activeTint = YtColors.CoinColor,
                    onClick = onCoin,
                )
                // Bilibili uses a filled star for favourites rather than a heart.
                PrimaryAction(
                    modifier = Modifier.weight(1f),
                    icon = if (state.isFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    label = if (state.isFavorited) "已收藏" else "收藏",
                    count = state.favCount,
                    active = state.isFavorited,
                    activeTint = YtColors.FavColor,
                    onClick = onFavorite,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // FlowRow, not Row: six chips do not fit across a 360dp phone, and a Row
        // simply pushed the last ones ("稍后再看") off the right edge where they
        // could not be read or tapped. Wrapping keeps every label on screen.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallAction(
                icon = Icons.Filled.Share,
                label = "分享",
                active = false,
                onClick = onShare,
            )
            SmallAction(
                icon = Icons.Filled.Headphones,
                // One wording for both states, as requested: the chip's fill
                // already shows whether the mode is on.
                label = "听视频",
                active = state.audioOnly,
                onClick = onToggleAudioOnly,
            )
            // The 弹幕开/关 chip that used to sit here is gone on request: the
            // composer's own switch, immediately right of the input pill, is the
            // only danmaku toggle now. Two switches for one setting, a screen
            // apart, disagreed whenever one of them was missed.
            if (!state.isPgc) {
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
            }
            if (!state.isPgc) {
                SmallAction(
                    icon = Icons.Filled.Download,
                    label = "缓存",
                    active = false,
                    onClick = onDownload,
                )
            }
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

        if (state.isPgc && state.episodes.isNotEmpty()) {
            EpisodesSection(
                episodes = state.episodes,
                currentEpId = state.currentEpId,
                onSelect = onEpisodeClick,
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = AppTheme.colors.divider)
    }
}

/**
 * The episode strip for a bangumi season.
 *
 * Highlighting follows `currentEpId` rather than an index so a season whose
 * episodes are not returned in order still marks the right one.
 */
@Composable
private fun EpisodesSection(
    episodes: List<PgcEpisode>,
    currentEpId: Long,
    onSelect: (PgcEpisode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "选集（${episodes.size}）",
            style = MaterialTheme.typography.titleSmall,
            color = AppTheme.colors.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(episodes, key = { it.playId }) { episode ->
                val selected = episode.playId == currentEpId
                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) {
                                YtColors.Red.copy(alpha = 0.16f)
                            } else {
                                AppTheme.colors.surfaceElevated
                            },
                        )
                        .clickable { onSelect(episode) }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                ) {
                    Text(
                        text = episode.title.ifBlank { "正片" },
                        color = if (selected) YtColors.Red else AppTheme.colors.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (episode.longTitle.isNotBlank()) {
                        Text(
                            text = episode.longTitle,
                            color = AppTheme.colors.onBackgroundSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A primary action: an icon beside its number, and nothing else.
 *
 * [label] is still required, but only as the accessibility description — it is
 * deliberately not drawn.
 *
 * The active state is the brand-tinted background plus a tinted icon and
 * counter. A solid fill was tried and reverted on request: the actual complaint
 * behind "点了点赞但 UI 没有变化" was that the *request* was being judged a
 * failure, not that the styling was too quiet, so the real fix is in
 * `BiliRepository.toAckResult()`.
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
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (active) activeTint.copy(alpha = 0.16f) else AppTheme.colors.surfaceElevated,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) activeTint else AppTheme.colors.onBackground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatCount(count),
            color = if (active) activeTint else AppTheme.colors.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
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

/**
 * The 关注 pill.
 *
 * Following is drawn as a **ticked** pill in the secondary colour rather than
 * the same shape in a slightly different grey, so "已关注" reads as a completed
 * state instead of a button that looks like it still wants pressing.
 */
@Composable
private fun PillButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    followed: Boolean = false,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (filled) YtColors.Red else AppTheme.colors.surfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (followed) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = AppTheme.colors.onBackgroundSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            color = if (filled) Color.White else AppTheme.colors.onBackgroundSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Comments, including replies.
 *
 * The composer has two modes: a top-level comment, and a reply aimed at one
 * comment. In reply mode the target is named above the field and can be
 * cleared, so it is never ambiguous who is being answered — the previous build
 * could only post top-level comments.
 */
@Composable
fun CommentsSection(
    state: PlayerUiState,
    onReplyTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onLikeReply: (Long) -> Unit,
    onDislikeReply: (Long) -> Unit,
    onReplyTo: (Reply) -> Unit,
    onCancelReply: () -> Unit,
    onLoadMore: () -> Unit,
    onExpandThread: (Reply) -> Unit,
    onSortChange: (ReplySort) -> Unit,
    /** Opens a commenter's space, from their avatar. */
    onUserClick: (Long) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(12.dp))

        // "最热评论" + the 最热/最新 switch, as the official client has it.
        ReplySortRow(current = state.replySort, onSelect = onSortChange)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "共 ${state.detail?.stat?.reply?.let(::formatCount) ?: "0"} 条评论",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            // Progress through the thread, so "loaded 20 of 3.1万" is visible
            // instead of the list simply appearing to stop.
            if (state.replyTotalCount > 0) {
                Text(
                    text = "已加载 ${state.replies.size}/${formatCount(state.replyTotalCount.toLong())}",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        state.replyTargetName?.let { name ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppTheme.colors.surfaceElevated)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "正在回复 @$name",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "取消回复",
                    tint = AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(onClick = onCancelReply),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.replyText,
                onValueChange = onReplyTextChange,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)),
                placeholder = {
                    Text(
                        text = state.replyTargetName?.let { "回复 @$it" } ?: "发送评论",
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

        // Result of the last attempt, right under the field. It used to be a
        // strip pinned to the bottom of the screen, which the soft keyboard hid
        // at exactly the moment somebody was posting a comment — so a rejected
        // send looked like nothing happened at all.
        state.commentFeedback?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                color = if (state.commentFeedbackIsError) {
                    YtColors.Red
                } else {
                    AppTheme.colors.onBackgroundSecondary
                },
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }

        if (!state.isLoggedIn) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "未登录：可以浏览评论，登录后才能发送评论、点赞与投币。",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(8.dp))

        val ownerMid = state.detail?.owner?.mid ?: 0L
        fun postedByUploader(mid: Long) = ownerMid != 0L && mid == ownerMid

        state.replies.forEach { reply ->
            CommentRow(
                reply = reply,
                globalEmotes = state.emotes,
                isUp = postedByUploader(reply.mid),
                isTop = reply.rpid in state.topReplyIds,
                onLike = { onLikeReply(reply.rpid) },
                onDislike = { onDislikeReply(reply.rpid) },
                onReply = { onReplyTo(reply) },
                onUserClick = onUserClick,
            )

            // Replies live in a grey box under their comment, the way the
            // official client draws them: the embedded preview first, then a
            // tappable "…等人 共N条回复" line for the rest.
            //
            // The main endpoint embeds only two or three of them, so the rest
            // still needs an explicit fetch — but the ones that *did* arrive are
            // now shown immediately instead of being hidden behind a cap, which
            // is what made threads look like they had not loaded.
            val children = reply.replies.orEmpty()
            if (children.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 44.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppTheme.colors.surfaceElevated)
                        .padding(vertical = 4.dp),
                ) {
                    children.forEachIndexed { index, child ->
                        // A hairline between replies. Without it the nested rows
                        // ran together into one block and it was hard to tell
                        // where one person's reply ended and the next began.
                        if (index > 0) {
                            HorizontalDivider(
                                color = AppTheme.colors.divider,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 38.dp),
                            )
                        }
                        CommentRow(
                            reply = child,
                            globalEmotes = state.emotes,
                            isUp = postedByUploader(child.mid),
                            isTop = false,
                            onLike = { onLikeReply(child.rpid) },
                            onDislike = { onDislikeReply(child.rpid) },
                            onReply = { onReplyTo(child) },
                            onUserClick = onUserClick,
                            nested = true,
                        )
                    }

                    if (reply.count > children.size) {
                        val opener = children.firstOrNull()?.member?.uname.orEmpty()
                        Text(
                            text = buildString {
                                if (opener.isNotEmpty()) {
                                    append(opener)
                                    append(" 等人 ")
                                }
                                append("共${reply.count}条回复")
                            },
                            color = YtColors.Red,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { onExpandThread(reply) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            } else if (reply.count > 0) {
                Text(
                    text = "共${reply.count}条回复",
                    color = YtColors.Red,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(start = 44.dp, bottom = 6.dp)
                        .clickable { onExpandThread(reply) }
                        .padding(vertical = 2.dp),
                )
            }
        }

        // Paging: the endpoint serves 20 top-level comments per call and this is
        // what turns a truncated list into a full one.
        if (state.hasMoreReplies) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppTheme.colors.chipBackground)
                    .clickable(enabled = !state.isLoadingMoreReplies, onClick = onLoadMore)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isLoadingMoreReplies) {
                    CircularProgressIndicator(
                        color = YtColors.Red,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = "加载更多评论",
                        color = AppTheme.colors.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
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

/**
 * One comment.
 *
 * Carries the three markers the official client shows — the UP badge, the TOP
 * badge for a pinned comment, and the commenter's IP location — plus reply and
 * like/dislike actions.
 */
@Composable
private fun CommentRow(
    reply: Reply,
    globalEmotes: Map<String, EmoteItem>,
    isUp: Boolean,
    isTop: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onReply: () -> Unit,
    /** Opens the commenter's space; the avatar is the tap target. */
    onUserClick: (Long) -> Unit = {},
    nested: Boolean = false,
) {
    val content = reply.content
    val liked = reply.action == 1
    val disliked = reply.action == 2
    val message = content?.message.orEmpty()
    // Emotes attached to the reply take precedence; the panel map (only
    // populated when signed in) fills in the remaining tokens.
    val emotes = (content?.emote.orEmpty()) + globalEmotes

    if (nested) {
        // Compact form inside a thread box.
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = reply.member?.avatar.orEmpty().toSecureUrl(),
                    contentDescription = reply.member?.uname,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceElevated)
                        .clickable(enabled = reply.mid > 0) { onUserClick(reply.mid) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = reply.member?.uname.orEmpty(),
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isUp) {
                    Spacer(Modifier.width(4.dp))
                    UpBadge()
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = relativeTime(reply.ctime),
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(3.dp))
            CommentMessage(message = message, emotes = emotes)
            if (content?.pictures.orEmpty().isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                CommentPictures(content?.pictures.orEmpty())
            }
            Spacer(Modifier.height(4.dp))
            CommentActions(
                like = reply.like,
                liked = liked,
                disliked = disliked,
                onLike = onLike,
                onDislike = onDislike,
                onReply = onReply,
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        // Tapping the avatar opens the commenter's space, as it does in the
        // reference client. A mid of 0 means the payload carried no author, so
        // there is nothing to open and the click is dropped.
        AsyncImage(
            model = reply.member?.avatar.orEmpty().toSecureUrl(),
            contentDescription = reply.member?.uname,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceElevated)
                .clickable(enabled = reply.mid > 0) { onUserClick(reply.mid) },
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reply.member?.uname.orEmpty(),
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isUp) {
                    Spacer(Modifier.width(5.dp))
                    UpBadge()
                }
                if (isTop) {
                    Spacer(Modifier.width(5.dp))
                    TopBadge()
                }
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(2.dp))
            // "10-27 · IP属地：河南", the line the official client prints.
            Text(
                text = buildString {
                    append(relativeTime(reply.ctime))
                    val location = reply.replyControl?.location.orEmpty()
                    if (location.isNotEmpty()) {
                        append(" · ")
                        append(location)
                    }
                },
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))

            CommentMessage(message = message, emotes = emotes)

            if (content?.pictures.orEmpty().isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                CommentPictures(content?.pictures.orEmpty())
            }

            Spacer(Modifier.height(6.dp))
            CommentActions(
                like = reply.like,
                liked = liked,
                disliked = disliked,
                onLike = onLike,
                onDislike = onDislike,
                onReply = onReply,
            )
        }
    }
}

/**
 * Images attached to a comment.
 *
 * Bilibili ships them as `content.pictures`, each with its own pixel size, and
 * **omits the key entirely** when a comment has no image — verified against a
 * live response rather than assumed, because the docs and the captured fixture
 * both lack the field. A single image is drawn at its own aspect ratio; several
 * go into the same three-across grid the dynamic page uses.
 *
 * Tapping opens the shared [ImageViewer], which is where zoom, 保存 and 分享
 * live — the same component the 动态图文 viewer uses.
 */
@Composable
private fun CommentPictures(pictures: List<ReplyPicture>) {
    if (pictures.isEmpty()) return

    var viewerAt by remember { mutableStateOf<Int?>(null) }
    val urls = remember(pictures) { pictures.map { it.imgSrc } }

    if (pictures.size == 1) {
        val only = pictures.first()
        AsyncImage(
            model = only.imgSrc.toThumbUrl(),
            contentDescription = "评论图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(only.aspectRatio)
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.surfaceElevated)
                .clickable { viewerAt = 0 },
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            pictures.take(MAX_COMMENT_PICTURES).chunked(3).forEachIndexed { rowIndex, rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowItems.forEachIndexed { columnIndex, picture ->
                        val index = rowIndex * 3 + columnIndex
                        AsyncImage(
                            model = picture.imgSrc.toThumbUrl(),
                            contentDescription = "评论图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(AppTheme.colors.surfaceElevated)
                                .clickable { viewerAt = index },
                        )
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (pictures.size > MAX_COMMENT_PICTURES) {
                Text(
                    text = "共 ${pictures.size} 张图片",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }

    viewerAt?.let { at ->
        ImageViewer(urls = urls, startIndex = at, onDismiss = { viewerAt = null })
    }
}

/** Ceiling for the grid; the viewer still receives every image. */
private const val MAX_COMMENT_PICTURES = 9

/** 点赞 (with its count), 点踩, and 回复 — the row under every comment. */
@Composable
private fun CommentActions(
    like: Int,
    liked: Boolean,
    disliked: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onReply: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            contentDescription = if (liked) "取消点赞" else "点赞",
            tint = if (liked) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
            modifier = Modifier.size(16.dp).clickable(onClick = onLike),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = like.toString(),
            color = if (liked) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
        )

        Spacer(Modifier.width(18.dp))
        Icon(
            imageVector = Icons.Outlined.ThumbDown,
            contentDescription = if (disliked) "取消点踩" else "点踩",
            tint = if (disliked) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
            modifier = Modifier.size(16.dp).clickable(onClick = onDislike),
        )

        Spacer(Modifier.width(18.dp))
        Text(
            text = "回复",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
            modifier = Modifier
                .clickable(onClick = onReply)
                .padding(vertical = 2.dp),
        )
    }
}

/** The small "UP" marker next to a commenter who is the video's uploader. */
@Composable
private fun UpBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(YtColors.Red.copy(alpha = 0.16f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = "UP",
            color = YtColors.Red,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The "TOP" marker for a pinned comment. */
@Composable
private fun TopBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(AppTheme.colors.chipBackground)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            // "置顶↑" rather than "TOP": the badge marks a comment the UP pinned,
            // and the arrow says which way it was pinned.
            text = "置顶↑",
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
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
