package com.ytstyle.bili.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.ui.components.relativeTime
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * The native message centre: four shortcuts with unread badges over the
 * 私信会话 list, replacing the embedded browser page.
 *
 * The default view is the session list, exactly like Bilibili's own 消息 page;
 * tapping a shortcut switches to that notification feed, and tapping it again
 * returns to the sessions. When the session call fails the default view falls
 * back to the merged notification list with a one-line note, because the
 * notification feeds are unaffected by a session outage.
 *
 * [onOpenVideo] is invoked with the BV id when a row carries one; a session row
 * without a video opens [onOpenUser], and items with neither target are inert.
 *
 * Known limitation: `x/msgfeed/sys_msg` is answered with HTTP 404 by the live
 * API even for a logged-in session (checked 2026-11), so 系统通知 can only show
 * that error while the other three feeds fill in normally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onBack: () -> Unit,
    onOpenVideo: (String) -> Unit = {},
    /** (talkerId, sessionType, peerName) — opens that 私信会话's history. */
    onOpenSession: (Long, Int, String) -> Unit = { _, _, _ -> },
    /**
     * Opens one notification category as its own page. The four shortcuts used
     * to filter the list in place; a separate page is what the user asked for,
     * and it keeps each category's paging independent.
     */
    onOpenCategory: (MessageCategory) -> Unit = {},
) {
    val viewModel: MessagesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background),
    ) {
        SimpleTopBar("消息", onBack)
        // The shortcuts navigate rather than filter: each category is a page of
        // its own, with its own paging.
        ShortcutRow(state = state, onSelect = { category -> category?.let(onOpenCategory) })
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(AppTheme.colors.divider),
        )
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            MessagesContent(
                state = state,
                onLoadMore = viewModel::loadMore,
                onLoadMoreAll = viewModel::loadMoreAll,
                onRetry = viewModel::refresh,
                onOpenVideo = onOpenVideo,
                onOpenSession = onOpenSession,
            )
        }
    }
}

/** What the area under the shortcuts is currently showing. */
private sealed interface MessageStatus {
    data object Content : MessageStatus
    data object Loading : MessageStatus
    data object LoggedOut : MessageStatus
    data object Empty : MessageStatus
    data class Error(val message: String) : MessageStatus
}

/**
 * Chooses what the area under the shortcuts shows.
 *
 * The default view is the 私信会话 list once it has rows; until then — and
 * whenever the session call failed — it is the merged notification feed the
 * screen showed before, so a session outage costs a note, not the page.
 */
@Composable
private fun MessagesContent(
    state: MessagesUiState,
    onLoadMore: (MessageCategory) -> Unit,
    onLoadMoreAll: () -> Unit,
    onRetry: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenSession: (Long, Int, String) -> Unit,
) {
    if (state.selected == null && state.sessions.isNotEmpty()) {
        SessionList(
            sessions = state.sessions,
            onOpenVideo = onOpenVideo,
            onOpenSession = onOpenSession,
        )
    } else {
        NotificationFeed(
            state = state,
            onLoadMore = onLoadMore,
            onLoadMoreAll = onLoadMoreAll,
            onRetry = onRetry,
            onOpenVideo = onOpenVideo,
            // Only the default view can fall back here *because* sessions
            // failed; a selected category never explains itself with a session
            // error.
            sessionsError = if (state.selected == null) state.sessionsError else null,
        )
    }
}

/** The per-category and merged notification feed. */
@Composable
private fun NotificationFeed(
    state: MessagesUiState,
    onLoadMore: (MessageCategory) -> Unit,
    onLoadMoreAll: () -> Unit,
    onRetry: () -> Unit,
    onOpenVideo: (String) -> Unit,
    sessionsError: String?,
) {
    val merged = remember(state.sections) {
        state.sections.values
            .flatMap { it.entries }
            .sortedByDescending { it.timeSeconds }
            .distinctBy { "${it.category.name}:${it.id}" }
    }
    val visible = remember(state.selected, state.sections, merged) {
        val selected = state.selected
        if (selected == null) merged else state.section(selected).entries
    }
    val selectedSection = state.selected?.let { state.section(it) }
    val status = when {
        state.loggedOut -> MessageStatus.LoggedOut
        visible.isNotEmpty() -> MessageStatus.Content
        selectedSection != null -> when {
            selectedSection.isLoading -> MessageStatus.Loading
            selectedSection.error != null -> MessageStatus.Error(selectedSection.error.orEmpty())
            else -> MessageStatus.Empty
        }
        state.isRefreshing || !state.hasLoaded || state.sections.values.any { it.isLoading } ->
            MessageStatus.Loading
        else -> {
            val failure = state.sections.values.firstOrNull { it.error != null }
            val message = failure?.error
            if (message != null) MessageStatus.Error(message) else MessageStatus.Empty
        }
    }

    val loadingCategories = state.selected?.let { listOf(it) } ?: MessageCategory.entries.toList()
    val canLoadMore = visible.isNotEmpty() && loadingCategories.any { !state.section(it).isEnd }
    val isLoadingMore = loadingCategories.any { state.section(it).isLoadingMore }
    // 系统通知 is excluded from the merged-view warning: its feed endpoint is
    // currently answered with HTTP 404 even when logged in (see the screen's
    // KDoc), so warning about it on every load would just be noise. Selecting
    // the category still surfaces the real error.
    val failedCategories = if (state.selected == null) {
        MessageCategory.entries.filter {
            it != MessageCategory.SYSTEM &&
                state.section(it).error != null &&
                state.section(it).entries.isEmpty()
        }
    } else {
        emptyList()
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (sessionsError != null) {
            item(key = "sessions-note") {
                Text(
                    text = "私信加载失败：$sessionsError",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        when (status) {
            is MessageStatus.Content -> {
                items(items = visible, key = { entry -> "${entry.category.name}:${entry.id}" }) { entry ->
                    MessageRow(entry = entry, onOpenVideo = onOpenVideo)
                }
                if (failedCategories.isNotEmpty()) {
                    item(key = "partial-failure") {
                        Text(
                            text = "部分消息加载失败：" +
                                failedCategories.joinToString("、") { it.label } +
                                "（下拉重试）",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.onBackgroundSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                if (canLoadMore) {
                    item(key = "load-more") {
                        LoadMoreButton(
                            loading = isLoadingMore,
                            onClick = {
                                val selected = state.selected
                                if (selected != null) onLoadMore(selected) else onLoadMoreAll()
                            },
                        )
                    }
                }
            }

            is MessageStatus.Loading -> item(key = "loading") { LoadingBlock() }

            is MessageStatus.LoggedOut -> item(key = "logged-out") {
                StateBlock(
                    title = "未登录",
                    subtitle = "登录后即可查看回复、@ 和系统通知",
                )
            }

            is MessageStatus.Empty -> item(key = "empty") {
                StateBlock(title = "暂无消息", subtitle = emptyHint(state.selected))
            }

            is MessageStatus.Error -> item(key = "error") {
                StateBlock(
                    title = "加载失败",
                    subtitle = status.message,
                    actionLabel = "重试",
                    onAction = onRetry,
                )
            }
        }
    }
}

/**
 * The default view: 私信会话, newest first.
 *
 * [DirectMessageSession.id] is unique by construction (see `parseSessions`),
 * which matters because a duplicate `LazyColumn` key is a crash, not a
 * cosmetic glitch. The sort is defensive: the request already asks for
 * `sort_rule = 2`, but the payload's order is not guaranteed by the docs.
 */
@Composable
private fun SessionList(
    sessions: List<DirectMessageSession>,
    onOpenVideo: (String) -> Unit,
    onOpenSession: (Long, Int, String) -> Unit,
) {
    val sorted = remember(sessions) { sessions.sortedByDescending { it.timeSeconds } }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = sorted, key = { it.id }) { session ->
            SessionRow(
                session = session,
                onClick = {
                    // The row's own `session_type` is 1 for a user and 2 for a
                    // fan club, which is exactly what the history endpoint
                    // wants — unlike the list's `session_type` filter.
                    if (session.talkerId > 0) {
                        onOpenSession(
                            session.talkerId,
                            if (session.isGroup) 2 else 1,
                            session.name,
                        )
                    }
                },
            )
        }
    }
}

/** One session row: 48dp avatar, name + time, then the last message. */
@Composable
private fun SessionRow(session: DirectMessageSession, onClick: () -> Unit) {
    val title = session.displayTitle()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(48.dp)) {
                MessageAvatar(
                    name = session.name,
                    url = session.face,
                    size = 48.dp,
                    fallback = title.take(1),
                )
                if (session.unreadCount > 0) {
                    UnreadBadge(
                        count = session.unreadCount,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-2).dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (session.timeSeconds > 0) {
                            relativeTime(session.timeSeconds)
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = session.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 72.dp)
                .height(0.5.dp)
                .background(AppTheme.colors.divider),
        )
    }
}

/**
 * A session's row title.
 *
 * A session whose card lookup failed keeps a blank name in the model; the row
 * shows a neutral placeholder instead of pretending to know who it is. The
 * kinds are ordered so a 粉丝团 row is never mislabelled a plain 私信.
 */
private fun DirectMessageSession.displayTitle(): String = name.ifBlank {
    when {
        isGroup -> "粉丝团"
        isSystem -> "系统消息"
        else -> "私信"
    }
}

/** The four round shortcuts; the badge only appears when the count is positive. */
@Composable
private fun ShortcutRow(state: MessagesUiState, onSelect: (MessageCategory?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        MessageCategory.entries.forEach { category ->
            ShortcutItem(
                category = category,
                count = state.unread.countFor(category),
                selected = state.selected == category,
                onClick = { onSelect(category) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ShortcutItem(
    category: MessageCategory,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // The circle itself stays neutral in both states; selection is
            // carried by the icon tint, like the reference page.
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.chipBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = category.label,
                    tint = if (selected) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.size(26.dp),
                )
            }
            if (count > 0) {
                UnreadBadge(
                    count = count,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-2).dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = category.label,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onBackground,
            maxLines = 1,
        )
    }
}

@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(CircleShape)
            .background(YtColors.Red)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** One feed row: avatar, sender, summary, relative time. */
@Composable
internal fun MessageRow(entry: MessageEntry, onOpenVideo: (String) -> Unit) {
    val bvid = entry.bvid
    val rowModifier = if (bvid != null) {
        Modifier
            .fillMaxWidth()
            .clickable { onOpenVideo(bvid) }
    } else {
        Modifier.fillMaxWidth()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = rowModifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            MessageAvatar(name = entry.senderName, url = entry.avatar)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.senderName.ifBlank { entry.category.label },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.timeText(),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onBackgroundSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 72.dp)
                .height(0.5.dp)
                .background(AppTheme.colors.divider),
        )
    }
}

/**
 * A round avatar with a first-character fallback.
 *
 * The fallback matters for session rows: their names come from a second,
 * optional request, and the row must still look deliberate when that request
 * fails ([name] is blank) rather than showing a broken empty circle.
 */
@Composable
internal fun MessageAvatar(
    name: String,
    url: String,
    size: Dp = 44.dp,
    fallback: String = "消",
) {
    val secure = url.toSecureUrl()
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(AppTheme.colors.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (secure.isNotBlank()) {
            AsyncImage(
                model = secure,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.take(1).ifBlank { fallback.take(1).ifBlank { "消" } },
                style = MaterialTheme.typography.titleSmall,
                color = AppTheme.colors.onBackgroundSecondary,
            )
        }
    }
}

@Composable
private fun LoadMoreButton(loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onClick, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = YtColors.Red,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "加载中…", color = YtColors.Red)
            } else {
                Text(text = "加载更多", color = YtColors.Red)
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = YtColors.Red)
    }
}

@Composable
private fun StateBlock(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AppTheme.colors.onBackground,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onBackgroundSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAction) {
                Text(text = actionLabel, color = YtColors.Red)
            }
        }
    }
}

private val MessageCategory.icon: ImageVector
    get() = when (this) {
        MessageCategory.REPLY -> Icons.Filled.Chat
        MessageCategory.AT -> Icons.Filled.AlternateEmail
        MessageCategory.LIKE -> Icons.Filled.ThumbUp
        MessageCategory.SYSTEM -> Icons.Filled.Notifications
    }

private fun MessageEntry.timeText(): String = when {
    timeSeconds > 0 -> relativeTime(timeSeconds)
    timeLabel.isNotBlank() -> timeLabel
    else -> ""
}

private fun emptyHint(category: MessageCategory?): String = when (category) {
    MessageCategory.REPLY -> "这里会显示别人对你评论的回复"
    MessageCategory.AT -> "这里会显示有人 @ 你的消息"
    MessageCategory.LIKE -> "这里会显示别人给你的点赞"
    MessageCategory.SYSTEM -> "这里会显示系统通知"
    null -> "还没有收到任何消息"
}
