package com.ytstyle.bili.ui.follow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.data.net.FollowEntry
import com.ytstyle.bili.data.net.FollowKind
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * 追更: the account's followed 番剧, 影视 and 合集 in one place.
 *
 * 全部 merges the sources into a single list and each row remembers its own
 * [FollowKind], so a tap still lands on the PGC player (番剧/剧集) or the video
 * player (合集) depending on what the row actually is.
 */
@Composable
fun FollowScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit = {},
    onBangumiClick: (Long) -> Unit = {},
    viewModel: FollowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        SimpleTopBar(title = "追更", onBack = onBack)
        FollowTabRow(selected = state.tab, onSelect = viewModel::selectTab)

        when {
            state.notLoggedIn -> CenteredMessage(text = "请先登录")

            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            state.items.isEmpty() -> CenteredMessage(
                text = state.error ?: "还没有内容",
                onRetry = if (state.error != null) viewModel::refresh else null,
            )

            else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.items, key = { it.key }) { entry ->
                    FollowRow(
                        entry = entry,
                        onClick = { viewModel.open(entry, onVideoClick, onBangumiClick) },
                    )
                }
            }
        }
    }
}

/** Underlined category tabs, matching the player's tab row. */
@Composable
private fun FollowTabRow(selected: FollowTab, onSelect: (FollowTab) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FollowTab.entries.forEach { tab ->
                val active = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelect(tab) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = tab.label,
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
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.divider))
    }
}

@Composable
private fun FollowRow(entry: FollowEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
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
                model = entry.cover.toSecureUrl(),
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.progress.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    // 番剧/剧集 progress is the reason to open the row, so it is
                    // the accent colour; a 合集's item count is metadata.
                    text = entry.progress,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.kind == FollowKind.COLLECTION) {
                        AppTheme.colors.onBackgroundSecondary
                    } else {
                        YtColors.Red
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, onRetry: (() -> Unit)? = null) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = text, color = AppTheme.colors.onBackgroundSecondary)
            if (onRetry != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "点击重试",
                    color = YtColors.Red,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
