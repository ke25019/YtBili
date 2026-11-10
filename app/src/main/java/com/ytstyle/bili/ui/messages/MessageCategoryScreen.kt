package com.ytstyle.bili.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * One notification category (回复我的 / @我 / 收到的赞 / 系统通知) as its own page.
 *
 * 系统通知 is a special case rather than an error: its feed endpoint
 * (`x/msgfeed/sys_msg`) answers **HTTP 404** on both `api.bilibili.com` and
 * `api.vc.bilibili.com`, for every path variant that was tried, so the page
 * says the category is no longer served instead of showing "加载失败" every time
 * it is opened.
 */
@Composable
fun MessageCategoryScreen(
    onBack: () -> Unit,
    onOpenVideo: (String) -> Unit = {},
    viewModel: MessageCategoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val category = viewModel.category

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        SimpleTopBar(title = category.label, onBack = onBack)

        when {
            state.unavailable -> UnavailableBlock()

            state.loggedOut -> StateBlock(
                title = "未登录",
                subtitle = "登录后即可查看${category.label}",
            )

            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            state.error != null && state.entries.isEmpty() -> StateBlock(
                title = "加载失败",
                subtitle = state.error,
                actionLabel = "重试",
                onAction = viewModel::retry,
            )

            state.entries.isEmpty() -> StateBlock(
                title = "暂无消息",
                subtitle = emptyHintFor(category),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(items = state.entries, key = { "${it.category.name}:${it.id}" }) { entry ->
                    MessageRow(entry = entry, onOpenVideo = onOpenVideo)
                }
                if (!state.isEnd) {
                    item(key = "load-more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(onClick = viewModel::loadMore, enabled = !state.isLoadingMore) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(
                                        color = YtColors.Red,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(text = "加载中…", color = YtColors.Red)
                                } else {
                                    Text(text = "加载更多", color = YtColors.Red)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The 系统通知 explanation.
 *
 * Deliberately not an error state: nothing failed, the server simply stopped
 * offering this list, and Bilibili's own web page is the only remaining way to
 * read it.
 */
@Composable
private fun UnavailableBlock() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "系统通知暂不可用",
            style = MaterialTheme.typography.titleSmall,
            color = AppTheme.colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "B 站已停止向第三方客户端提供系统通知列表，" +
                "该分类在网页版仍可查看。其余三个分类不受影响。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onBackgroundSecondary,
            textAlign = TextAlign.Center,
        )
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
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
            TextButton(onClick = onAction) { Text(text = actionLabel, color = YtColors.Red) }
        }
    }
}

private fun emptyHintFor(category: MessageCategory): String = when (category) {
    MessageCategory.REPLY -> "这里会显示别人对你评论的回复"
    MessageCategory.AT -> "这里会显示有人 @ 你的消息"
    MessageCategory.LIKE -> "这里会显示别人给你的点赞"
    MessageCategory.SYSTEM -> "这里会显示系统通知"
}
