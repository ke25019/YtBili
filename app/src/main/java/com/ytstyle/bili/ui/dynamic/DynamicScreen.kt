package com.ytstyle.bili.ui.dynamic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.ui.components.VideoRow
import com.ytstyle.bili.ui.components.parseDurationString
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * The middle tab. YouTube fills this slot with Subscriptions; here it shows the
 * logged-in user's own uploads, which is the closest thing the public API
 * exposes without extra auth scopes.
 */
@Composable
fun DynamicScreen(
    onVideoClick: (String) -> Unit,
    onLoginClick: () -> Unit,
    viewModel: DynamicViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        when {
            !state.isLoggedIn && !state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "登录后查看订阅更新",
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

            state.videos.isEmpty() && state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = YtColors.Red)
                }
            }

            else -> {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(state.videos, key = { it.bvid }) { video ->
                        VideoRow(
                            title = video.title,
                            thumbnailUrl = video.pic,
                            uploaderName = state.uploaderName,
                            viewCount = video.play,
                            durationSeconds = parseDurationString(video.length),
                            onClick = { onVideoClick(video.bvid) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}
