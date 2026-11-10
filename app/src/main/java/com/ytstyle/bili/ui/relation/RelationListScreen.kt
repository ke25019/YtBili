package com.ytstyle.bili.ui.relation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.data.net.RelationUser
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * 粉丝 / 关注 as a second-level list.
 *
 * Opened from the counters on a space page. Both tabs share one view model, so
 * switching between them does not queue two requests.
 */
@Composable
fun RelationListScreen(
    mid: Long,
    initialTab: RelationTab,
    title: String,
    onBack: () -> Unit,
    onUserClick: (Long) -> Unit,
    viewModel: RelationListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(mid, initialTab) { viewModel.start(mid, initialTab) }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        SimpleTopBar(title = title, onBack = onBack)

        TabStrip(selected = state.tab, onSelect = viewModel::selectTab)

        state.notice?.let { message ->
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(3_000)
                viewModel.dismissNotice()
            }
            Text(
                text = message,
                color = if (state.noticeIsError) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.surfaceElevated)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            // A hidden list is a statement about privacy, not a failure and not
            // an empty result — so it gets its own wording.
            state.hidden -> CenteredHint("对方隐私设置未公开，无法查看")

            state.error != null -> CenteredHint(state.error.orEmpty())

            state.items.isEmpty() -> CenteredHint(
                if (state.tab == RelationTab.Fans) "还没有粉丝" else "还没有关注",
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (state.total > 0) {
                    item {
                        Text(
                            text = "共 ${formatCount(state.total)} 人",
                            color = AppTheme.colors.onBackgroundSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                }

                items(state.items, key = { it.mid }) { user ->
                    UserRow(
                        user = user,
                        // 取关 only makes sense in the 关注 tab, and only for
                        // somebody this account actually follows.
                        showUnfollow = state.tab == RelationTab.Following && user.followed,
                        onUnfollow = { viewModel.unfollow(user) },
                        onClick = { onUserClick(user.mid) },
                    )
                }

                // Same sentinel idea as the comment list: the row below the last
                // user is only composed once the reader has scrolled there.
                if (!state.reachedEnd) {
                    item(key = "more") {
                        LaunchedEffect(Unit) { viewModel.loadMore() }
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = YtColors.Red,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun TabStrip(selected: RelationTab, onSelect: (RelationTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RelationTab.entries.forEach { tab ->
            val active = tab == selected
            Text(
                text = tab.label,
                color = if (active) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (active) YtColors.Red.copy(alpha = 0.12f) else AppTheme.colors.chipBackground,
                    )
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun UserRow(
    user: RelationUser,
    showUnfollow: Boolean,
    onUnfollow: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = user.face.toSecureUrl(),
            contentDescription = user.uname,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceElevated),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.uname.ifBlank { "用户 ${user.mid}" },
                    color = AppTheme.colors.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (user.special == 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "特别关注",
                        color = YtColors.Red,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(YtColors.Red.copy(alpha = 0.12f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            if (user.sign.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = user.sign,
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (showUnfollow) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = "取消关注",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppTheme.colors.chipBackground)
                    .clickable(onClick = onUnfollow)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}
