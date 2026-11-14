package com.ytstyle.bili.ui.dynamic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.data.net.DynamicEntry
import com.ytstyle.bili.data.net.DynamicKind
import com.ytstyle.bili.data.net.DynamicRailUser
import com.ytstyle.bili.ui.components.ImageViewer
import com.ytstyle.bili.ui.components.ThumbnailDuration
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.components.toThumbUrl
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/** Images beyond this are replaced by a `+N` overlay, as the official card does. */
private const val MAX_GRID_IMAGES = 9

/**
 * The middle tab: the 动态 feed of followed uploaders, laid out like Bilibili's
 * own page — a filter tab row on top, a vertical rail of the followed
 * uploaders down the left, and the cards to their right.
 *
 * The rail's `我` entry and every avatar are served by the *public*
 * `feed/space` endpoint, so they work even when a single-uploader view is all
 * the server will answer for.
 */
@Composable
fun DynamicScreen(
    onVideoClick: (String) -> Unit,
    onLoginClick: () -> Unit,
    onOpenDetail: (DynamicEntry) -> Unit,
    viewModel: DynamicViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var composer by remember { mutableStateOf<DynamicComposerTarget?>(null) }

    // The write result (已转发 / 失败原因) clears itself, like the player's.
    state.feedback?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(3_000)
            viewModel.dismissFeedback()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            // The tab row is this screen's header, so it has to clear the status
            // bar: without the inset it was drawn underneath the clock and the
            // battery, which is what "通知栏区域" was about.
            .statusBarsPadding(),
    ) {
        DynamicTabRow(selected = state.tab, onSelect = viewModel::selectTab)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(AppTheme.colors.divider),
        )
        state.feedback?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.feedbackIsError) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        when {
            state.notLoggedIn && !state.isLoading -> LoginPrompt(onLoginClick)

            state.isLoading && state.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            else -> Row(modifier = Modifier.fillMaxSize()) {
                RailColumn(
                    rail = state.rail,
                    users = state.railUsers,
                    liveCount = state.liveCount,
                    onSelect = viewModel::selectRail,
                )
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(AppTheme.colors.divider),
                )
                FeedColumn(
                    state = state,
                    onVideoClick = onVideoClick,
                    onRetry = viewModel::refresh,
                    onLoadMore = viewModel::loadMore,
                    onLike = viewModel::toggleLike,
                    onRepost = { entry -> composer = DynamicComposerTarget(entry, isRepost = true) },
                    onComment = { entry -> composer = DynamicComposerTarget(entry, isRepost = false) },
                    onOpenDetail = onOpenDetail,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    composer?.let { target ->
        DynamicComposerDialog(
            target = target,
            onDismiss = { composer = null },
            onSend = { text ->
                if (target.isRepost) {
                    viewModel.repost(target.entry, text)
                } else {
                    viewModel.comment(target.entry, text)
                }
                composer = null
            },
        )
    }
}

/** Which dynamic the composer belongs to, and which write it will send. */
private data class DynamicComposerTarget(val entry: DynamicEntry, val isRepost: Boolean)

/**
 * The 转发 / 评论 composer.
 *
 * One dialog for both because they differ only in the hint and the button
 * label. A repost may be sent with an empty message (that is a plain 转发);
 * a comment may not, so its button stays disabled until something is typed.
 */
@Composable
private fun DynamicComposerDialog(
    target: DynamicComposerTarget,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surfaceElevated,
        title = {
            Text(
                text = if (target.isRepost) "转发动态" else "评论动态",
                color = AppTheme.colors.onBackground,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                Text(
                    text = "@${target.entry.authorName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            text = if (target.isRepost) "说点什么（可留空）" else "发一条友善的评论",
                            color = AppTheme.colors.onBackgroundSecondary,
                        )
                    },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(text.trim()) },
                enabled = target.isRepost || text.isNotBlank(),
            ) {
                Text(text = "发送", color = YtColors.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = AppTheme.colors.onBackgroundSecondary)
            }
        },
    )
}

/** 全部 / 投稿 / 番剧 / 专栏, each mapped to a `feed/all` type. */
@Composable
private fun DynamicTabRow(selected: DynamicTab, onSelect: (DynamicTab) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items = DynamicTab.entries, key = { it.name }) { tab ->
            val active = tab == selected
            Column(
                modifier = Modifier
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tab.label,
                    color = if (active) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (active) YtColors.Red else Color.Transparent),
                )
            }
        }
    }
}

/**
 * The left rail.
 *
 * Avatars come from `x/relation/followings`; when that call fails the three
 * fixed entries still render, because the rail is navigation, not content.
 */
@Composable
private fun RailColumn(
    rail: DynamicRail,
    users: List<DynamicRailUser>,
    liveCount: Int,
    onSelect: (DynamicRail) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .background(AppTheme.colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailEntry(
            label = if (liveCount > 0) "Live($liveCount)" else "Live",
            selected = rail == DynamicRail.Live,
            onClick = { onSelect(DynamicRail.Live) },
        )
        RailEntry(
            label = "全部动态",
            selected = rail == DynamicRail.All,
            onClick = { onSelect(DynamicRail.All) },
        )
        RailEntry(
            label = "我",
            selected = rail == DynamicRail.Mine,
            onClick = { onSelect(DynamicRail.Mine) },
        )

        if (users.isNotEmpty()) Spacer(Modifier.height(6.dp))

        users.forEach { user ->
            val active = rail is DynamicRail.User && rail.mid == user.mid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(DynamicRail.User(user.mid, user.name)) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val face = user.face.toSecureUrl()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) YtColors.Red else AppTheme.colors.surfaceElevated,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (face.isNotBlank()) {
                        AsyncImage(
                            model = face,
                            contentDescription = user.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = user.name.take(1).ifBlank { "U" },
                            color = if (active) Color.White else AppTheme.colors.onBackgroundSecondary,
                            fontSize = 16.sp,
                        )
                    }
                }
                if (user.name.isNotBlank()) {
                    Text(
                        text = user.name,
                        color = if (active) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RailEntry(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) YtColors.Red else AppTheme.colors.onBackground,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * The cards.
 *
 * Paging triggers when the third-from-last card becomes visible; the extra
 * state keys make sure the effect re-runs after a tab or rail switch instead of
 * firing once on the first composition.
 */
@Composable
private fun FeedColumn(
    state: DynamicUiState,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onLike: (DynamicEntry) -> Unit,
    onRepost: (DynamicEntry) -> Unit,
    onComment: (DynamicEntry) -> Unit,
    onOpenDetail: (DynamicEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore, state.entries.size, state.rail, state.tab) {
        if (shouldLoadMore && state.hasMore && !state.isLoading && !state.isLoadingMore) onLoadMore()
    }

    val error = state.error
    when {
        error != null && state.entries.isEmpty() -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                TextButton(onClick = onRetry) { Text(text = "重试", color = YtColors.Red) }
            }
        }

        state.entries.isEmpty() -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (state.rail == DynamicRail.Live) "关注的人都没有在直播" else "还没有动态",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onBackgroundSecondary,
            )
        }

        else -> LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(items = state.entries, key = { it.id }) { entry ->
                DynamicCard(
                    entry = entry,
                    liked = entry.id in state.likedIds,
                    delta = state.deltas[entry.id] ?: DynamicDelta(),
                    onVideoClick = onVideoClick,
                    onLike = { onLike(entry) },
                    onRepost = { onRepost(entry) },
                    onComment = { onComment(entry) },
                    onOpenDetail = { onOpenDetail(entry) },
                )
            }
            if (state.isLoadingMore) {
                item(key = "loading-more") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = YtColors.Red,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicCard(
    entry: DynamicEntry,
    liked: Boolean,
    delta: DynamicDelta,
    onVideoClick: (String) -> Unit,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onComment: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Tapping the card's body opens the reading page. The image grid
                // and the action row keep their own handlers, which win because
                // they are children of this one.
                .clickable(onClick = onOpenDetail)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val face = entry.authorFace.toSecureUrl()
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    if (face.isNotBlank()) {
                        AsyncImage(
                            model = face,
                            contentDescription = entry.authorName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = entry.authorName.take(1).ifBlank { "U" },
                            color = AppTheme.colors.onBackgroundSecondary,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.authorName.ifBlank { "未知用户" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.pubTimeLabel.isNotBlank()) {
                        Text(
                            text = entry.pubTimeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTheme.colors.onBackgroundSecondary,
                            maxLines = 1,
                        )
                    }
                }
                if (entry.isPinned) Pill(text = "置顶")
            }

            if (entry.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onBackground,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when (entry.kind) {
                DynamicKind.VIDEO -> VideoMajor(entry = entry, onVideoClick = onVideoClick)
                DynamicKind.LIVE -> LiveMajor(entry = entry)
                DynamicKind.ARTICLE -> ArticleMajor(entry = entry)
                else -> Unit
            }

            if (entry.imageUrls.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ImageGrid(urls = entry.imageUrls)
            }

            if (entry.kind == DynamicKind.FORWARD) {
                Spacer(Modifier.height(8.dp))
                ForwardBox(entry = entry, onVideoClick = onVideoClick)
            }

            Spacer(Modifier.height(10.dp))
            ActionRow(
                entry = entry,
                liked = liked,
                delta = delta,
                onLike = onLike,
                onRepost = onRepost,
                onComment = onComment,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(AppTheme.colors.divider),
        )
    }
}

/** A投稿 embedded in a dynamic: thumbnail, duration, title, play count. */
@Composable
private fun VideoMajor(entry: DynamicEntry, onVideoClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.colors.surfaceElevated)
            .clickable(enabled = entry.videoBvid.isNotEmpty()) {
                onVideoClick(entry.videoBvid)
            },
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = entry.videoCover.toThumbUrl(),
                contentDescription = entry.videoTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (entry.videoDurationLabel.isNotEmpty()) {
                // Over a video frame the pill stays: this is a thumbnail, not a
                // single-column list row.
                ThumbnailDuration(
                    text = entry.videoDurationLabel,
                    plain = false,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(10.dp)) {
            if (entry.videoTitle.isNotBlank()) {
                Text(
                    text = entry.videoTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.videoViewText.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${entry.videoViewText}播放",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A 专栏 dynamic: cover on the left, title beside it. */
@Composable
private fun ArticleMajor(entry: DynamicEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.colors.surfaceElevated)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.articleCover.isNotBlank()) {
            AsyncImage(
                model = entry.articleCover.toThumbUrl(),
                contentDescription = entry.articleTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AppTheme.colors.surface),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.articleTitle.ifBlank { "专栏文章" },
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "专栏",
                style = MaterialTheme.typography.labelSmall,
                color = YtColors.Red,
            )
        }
    }
}

/** A live dynamic; the title is only present when `live_rcmd` could be read. */
@Composable
private fun LiveMajor(entry: DynamicEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.colors.surfaceElevated)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.liveCover.isNotBlank()) {
            AsyncImage(
                model = entry.liveCover.toThumbUrl(),
                contentDescription = entry.liveTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AppTheme.colors.surface),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.liveTitle.ifBlank { "正在直播" },
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = "直播中", style = MaterialTheme.typography.labelSmall, color = YtColors.Red)
        }
    }
}

/** The quoted original of a 转发, one level deep. */
@Composable
private fun ForwardBox(entry: DynamicEntry, onVideoClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.colors.surfaceElevated)
            .padding(10.dp),
    ) {
        if (entry.forwardAuthorName.isNotBlank()) {
            Text(
                text = "@${entry.forwardAuthorName}",
                style = MaterialTheme.typography.labelMedium,
                color = AppTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.forwardText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.forwardText,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onBackground,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.forwardImageUrls.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ImageGrid(urls = entry.forwardImageUrls)
        }
        if (entry.forwardVideoCover.isNotBlank() || entry.forwardVideoTitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = entry.forwardVideoBvid.isNotEmpty()) {
                        onVideoClick(entry.forwardVideoBvid)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.forwardVideoCover.isNotBlank()) {
                    AsyncImage(
                        model = entry.forwardVideoCover.toThumbUrl(),
                        contentDescription = entry.forwardVideoTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(120.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppTheme.colors.surface),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = entry.forwardVideoTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One to nine images: a single one gets a 16:9 box, the rest are squared in a
 * three-across grid. More than nine collapse into the last cell's `+N`.
 */
@Composable
private fun ImageGrid(urls: List<String>) {
    // Tapping any cell opens the full-screen viewer at that image. The viewer is
    // handed the *whole* list, not the nine the grid shows, so `+N` finally has
    // somewhere to lead.
    var viewerAt by remember { mutableStateOf<Int?>(null) }

    if (urls.size == 1) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.surfaceElevated)
                .clickable { viewerAt = 0 },
        ) {
            AsyncImage(
                model = urls.first().toThumbUrl(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Only composed while open, so a closed viewer costs nothing.
        viewerAt?.let { at ->
            ImageViewer(urls = urls, startIndex = at, onDismiss = { viewerAt = null })
        }
        return
    }

    val shown = urls.take(MAX_GRID_IMAGES)
    val hidden = urls.size - shown.size
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.chunked(3).forEachIndexed { rowIndex, rowUrls ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowUrls.forEachIndexed { columnIndex, url ->
                    val index = rowIndex * 3 + columnIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppTheme.colors.surfaceElevated)
                            .clickable { viewerAt = index },
                    ) {
                        AsyncImage(
                            model = url.toThumbUrl(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (hidden > 0 && index == shown.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x99000000)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+$hidden",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
                // Keep a short last row left-aligned with the rows above it.
                repeat(3 - rowUrls.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    viewerAt?.let { at ->
        ImageViewer(urls = urls, startIndex = at, onDismiss = { viewerAt = null })
    }
}

/**
 * 转发 / 评论 / 点赞, now writable.
 *
 * Every count is `snapshot + delta`: the payload's `module_stat` was captured
 * when the page was fetched and the server does not push updates, so the
 * optimistic delta is the only thing that moves a number.
 */
@Composable
private fun ActionRow(
    entry: DynamicEntry,
    liked: Boolean,
    delta: DynamicDelta,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onComment: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionItem(
            icon = Icons.Filled.Share,
            label = "转发",
            count = entry.forwardCount + delta.forward,
            onClick = onRepost,
        )
        ActionItem(
            icon = Icons.AutoMirrored.Filled.Comment,
            label = "评论",
            count = entry.commentCount + delta.comment,
            onClick = onComment,
        )
        ActionItem(
            icon = if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            label = if (liked) "取消点赞" else "点赞",
            count = entry.likeCount + delta.like,
            onClick = onLike,
            accent = if (liked) YtColors.Red else null,
        )
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    count: Long,
    onClick: () -> Unit,
    /** `null` means the muted default; a liked item passes the brand red. */
    accent: Color? = null,
) {
    val tint = accent ?: AppTheme.colors.onBackgroundSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        if (count > 0) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = formatCount(count),
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(YtColors.Red)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun LoginPrompt(onLoginClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "登录后查看关注动态",
                color = AppTheme.colors.onBackground,
                modifier = Modifier.padding(16.dp),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(YtColors.Red)
                    .clickable(onClick = onLoginClick)
                    .padding(horizontal = 28.dp, vertical = 10.dp),
            ) {
                Text(text = "登录", color = Color.White)
            }
        }
    }
}
