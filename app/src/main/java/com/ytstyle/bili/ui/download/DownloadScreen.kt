package com.ytstyle.bili.ui.download

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.data.download.DownloadItem
import com.ytstyle.bili.data.download.DownloadState
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors
import java.util.Locale

/**
 * The cache manager: poster, title, quality, size, state and progress per row.
 *
 * Tapping a finished row opens [OfflinePlayerScreen]. 暂停 cancels the transfer
 * and 继续 restarts it from zero (see [DownloadManager]); 删除 removes the two
 * files and the index entry.
 */
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onPlay: (String) -> Unit = {},
    viewModel: DownloadListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        SimpleTopBar(title = "我的缓存", onBack = onBack)

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            state.entries.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有缓存视频",
                    color = AppTheme.colors.onBackgroundSecondary,
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(state.entries, key = { it.item.id }) { entry ->
                    DownloadRow(
                        entry = entry,
                        onPlay = onPlay,
                        onPause = viewModel::pause,
                        onResume = viewModel::resume,
                        onDelete = viewModel::delete,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    onPlay: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (DownloadItem) -> Unit,
    onDelete: (String) -> Unit,
) {
    val item = entry.item
    // 下载中 without a live job means the process died (or the job was cancelled
    // before it ever ran); treat it as failed so 继续 is offered.
    val stale = item.state == DownloadState.DOWNLOADING && !entry.running
    val done = item.state == DownloadState.DONE && !entry.running
    val percent = (entry.progress.coerceIn(0f, 1f) * 100).toInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = done) { onPlay(item.id) }
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = item.cover.toSecureUrl(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 132.dp, height = 74.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppTheme.colors.surfaceElevated),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { item.bvid },
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // Imported rows say where they came from: they cannot be
                    // resumed or deleted here, the other client owns the files.
                    text = (
                        listOf(item.videoLabel, item.audioLabel).filter { it.isNotEmpty() } +
                            listOfNotNull("其他客户端".takeIf { item.external })
                        ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.onBackgroundSecondary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stateText(entry, percent, stale),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (done) AppTheme.colors.onBackgroundSecondary else YtColors.Red,
                )
            }
        }

        if (entry.running) {
            LinearProgressIndicator(
                progress = { entry.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(4.dp),
                color = YtColors.Red,
                trackColor = AppTheme.colors.surfaceElevated,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.running) {
                TextButton(onClick = { onPause(item.id) }) { Text("暂停") }
            } else if (!done) {
                // A failed entry keeps its source URLs, so resuming needs no
                // network round trip. They can expire, in which case the retry
                // fails and the picker is the way back.
                TextButton(
                    onClick = { onResume(item) },
                    enabled = item.videoUrl.isNotBlank() && item.audioUrl.isNotBlank(),
                ) { Text("继续") }
            }
            TextButton(onClick = { onDelete(item.id) }) {
                Text(text = "删除", color = YtColors.Red)
            }
        }
    }
}

/** "下载中 42%" / "已完成 · 82.4 MB" / the stored failure reason. */
private fun stateText(entry: DownloadEntry, percent: Int, stale: Boolean): String {
    val item = entry.item
    return when {
        entry.running -> "下载中 $percent%"
        stale -> "已中断，可点继续"
        item.state == DownloadState.DONE -> "已完成 · ${formatBytes(item.totalBytes)}"
        else -> "失败：${item.error.ifBlank { "未知原因" }}"
    }
}

/** Android-style binary units; the value is what the two files actually occupy. */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024f)
    bytes < 1024L * 1024 * 1024 ->
        String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
    else ->
        String.format(Locale.getDefault(), "%.2f GB", bytes / (1024f * 1024f * 1024f))
}
