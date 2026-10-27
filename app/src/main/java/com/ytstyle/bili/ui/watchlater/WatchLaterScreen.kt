package com.ytstyle.bili.ui.watchlater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.ui.components.VideoRow
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * 稍后再看, mirroring Bilibili's own list: the same entries, with progress shown
 * for partially watched items.
 */
@Composable
fun WatchLaterScreen(
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: WatchLaterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        SimpleTopBar(
            title = if (state.count > 0) "稍后再看 ${state.count}" else "稍后再看",
            onBack = onBack,
        )

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            state.items.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: "稍后再看是空的",
                    color = AppTheme.colors.onBackgroundSecondary,
                )
            }

            else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.items, key = { it.bvid }) { item ->
                    VideoRow(
                        title = item.title,
                        thumbnailUrl = item.pic,
                        uploaderName = item.owner?.name.orEmpty(),
                        viewCount = item.stat?.view ?: 0,
                        durationSeconds = item.duration,
                        onClick = { onVideoClick(item.bvid) },
                    )
                }
            }
        }
    }
}
