package com.ytstyle.bili.ui.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.data.download.DownloadState
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * Chooses the video/audio pair to cache for one bvid.
 *
 * Route argument: `bvid`. The list of offered tiers is whatever the DASH
 * manifest actually contains — a 480P-only upload will not pretend to have
 * 1080P.
 */
@Composable
fun DownloadPickerScreen(
    onBack: () -> Unit,
    viewModel: DownloadPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        SimpleTopBar(title = "缓存视频", onBack = onBack)

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            state.videoTiers.isEmpty() || state.audioTiers.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: "没有可缓存的流",
                    color = AppTheme.colors.onBackgroundSecondary,
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.Top) {
                    AsyncImage(
                        model = state.cover.toSecureUrl(),
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
                            text = state.title.ifBlank { state.bvid },
                            style = MaterialTheme.typography.titleSmall,
                            color = AppTheme.colors.onBackground,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "视频 ${state.videoTiers.size} 档 · 音频 ${state.audioTiers.size} 档",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTheme.colors.onBackgroundSecondary,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                SectionTitle("画质")
                state.videoTiers.forEach { tier ->
                    TierRow(
                        label = tier.label,
                        selected = state.selectedVideo?.id == tier.id,
                        onClick = { viewModel.selectVideo(tier) },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(12.dp))
                SectionTitle("音质")
                state.audioTiers.forEach { tier ->
                    TierRow(
                        label = tier.label,
                        selected = state.selectedAudio?.id == tier.id,
                        onClick = { viewModel.selectAudio(tier) },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = viewModel::start,
                    enabled = !state.running &&
                        state.selectedVideo != null &&
                        state.selectedAudio != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = YtColors.Red),
                ) {
                    Text(text = if (state.running) "缓存中…" else "开始缓存")
                }

                Spacer(Modifier.height(8.dp))
                StatusLine(state)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = AppTheme.colors.onBackground,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** A full-width selectable row; a chip row would clip at large font scales. */
@Composable
private fun TierRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) YtColors.Red.copy(alpha = 0.12f) else AppTheme.colors.surfaceElevated,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) YtColors.Red else AppTheme.colors.onBackground,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选择",
                tint = YtColors.Red,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Explains what happened to the last tap, so the button never looks inert. */
@Composable
private fun StatusLine(state: DownloadPickerUiState) {
    val existing = state.existing
    val text = when {
        state.running -> "正在后台缓存，离开本页不会中断"
        existing != null && existing.state == DownloadState.DONE -> "已缓存：${existing.videoLabel} · ${existing.audioLabel}"
        existing != null && existing.state == DownloadState.DOWNLOADING -> "缓存中…"
        existing != null && existing.state == DownloadState.FAILED ->
            "上次未完成（${existing.error.ifBlank { "失败" }}），可重新开始"
        else -> state.message.orEmpty()
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = AppTheme.colors.onBackgroundSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
}
