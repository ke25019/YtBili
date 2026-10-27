package com.ytstyle.bili.ui.home

import com.ytstyle.bili.ui.theme.AppTheme

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.ui.components.VideoCard
import com.ytstyle.bili.ui.theme.YtColors

@Composable
fun HomeScreen(
    onVideoClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // YouTube prefetches the next page well before the user reaches the end.
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && state.items.isNotEmpty()) viewModel.load()
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        ChipRow(
            chips = HOME_CHIPS,
            selected = state.selectedChip,
            onSelect = viewModel::selectChip,
        )

        when {
            state.items.isEmpty() && state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = YtColors.Red)
                }
            }

            state.items.isEmpty() && state.error != null -> {
                ErrorState(message = state.error!!, onRetry = viewModel::refresh)
            }

            state.useGrid -> {
                // Experimental two-column layout.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = state.items,
                        key = { it.bvid.ifEmpty { it.id.toString() } },
                    ) { item ->
                        VideoCard(
                            title = item.title,
                            thumbnailUrl = item.pic,
                            uploaderName = item.owner?.name.orEmpty(),
                            uploaderAvatar = item.owner?.face.orEmpty(),
                            viewCount = item.stat?.view ?: 0,
                            danmakuCount = item.stat?.danmaku ?: 0,
                            durationSeconds = item.duration,
                            publishedAt = item.pubdate,
                            compact = true,
                            onClick = { onVideoClick(item.bvid) },
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(
                        items = state.items,
                        key = { it.bvid.ifEmpty { it.id.toString() } },
                    ) { item ->
                        VideoCard(
                            title = item.title,
                            thumbnailUrl = item.pic,
                            uploaderName = item.owner?.name.orEmpty(),
                            uploaderAvatar = item.owner?.face.orEmpty(),
                            viewCount = item.stat?.view ?: 0,
                            danmakuCount = item.stat?.danmaku ?: 0,
                            durationSeconds = item.duration,
                            publishedAt = item.pubdate,
                            onClick = { onVideoClick(item.bvid) },
                        )
                    }

                    if (state.isLoading) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = YtColors.Red,
                                    modifier = Modifier.height(24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * YouTube's chip row: filled white pill when selected, dark grey otherwise.
 * This single detail does more for the "looks like YouTube" impression than
 * almost anything else on the screen.
 */
@Composable
private fun ChipRow(
    chips: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips.size) { index ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) AppTheme.colors.chipSelected else AppTheme.colors.chipBackground,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = chips[index],
                    color = if (isSelected) AppTheme.colors.chipSelectedContent else AppTheme.colors.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "加载失败",
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.onBackgroundSecondary,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(AppTheme.colors.surfaceElevated)
                .clickable(onClick = onRetry)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("重试", color = AppTheme.colors.onBackground)
        }
    }
}
