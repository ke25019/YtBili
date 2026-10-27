package com.ytstyle.bili.ui.favorites

import com.ytstyle.bili.ui.theme.AppTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.ui.components.VideoRow
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.YtColors

@Composable
fun FavoritesScreen(
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        SimpleTopBar(title = "我的收藏", onBack = onBack)

        // Folder selector, YouTube-playlist style.
        if (state.folders.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.folders, key = { it.id }) { folder ->
                    val selected = folder.id == state.selectedFolderId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) AppTheme.colors.chipSelected else AppTheme.colors.chipBackground,
                            )
                            .clickable { viewModel.selectFolder(folder.id) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = "${folder.title} ${folder.mediaCount}",
                            color = if (selected) AppTheme.colors.chipSelectedContent else AppTheme.colors.onBackground,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            state.items.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: "收藏夹是空的",
                    color = AppTheme.colors.onBackgroundSecondary,
                )
            }

            else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.items, key = { it.id }) { media ->
                    VideoRow(
                        title = media.title,
                        thumbnailUrl = media.cover,
                        uploaderName = media.upper?.name.orEmpty(),
                        viewCount = 0,
                        durationSeconds = media.duration,
                        // Media rows store `id` as aid or bvid depending on type;
                        // only type 2 (video) rows are openable here.
                        onClick = { if (media.type == 2) viewModel.openMedia(media, onVideoClick) },
                    )
                }
            }
        }
    }
}
