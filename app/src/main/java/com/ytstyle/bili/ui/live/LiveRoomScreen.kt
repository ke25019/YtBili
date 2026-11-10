package com.ytstyle.bili.ui.live

import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ytstyle.bili.data.live.LiveDanmakuProtocol
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.player.MEDIA_USER_AGENT
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * A live room: stream player plus room metadata.
 *
 * Live streams need no session cookie (they are public), but the CDN still
 * expects a desktop UA and a Referer on some edges, so the same header set as
 * on-demand playback is used.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveRoomScreen(
    onBack: () -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // Rebuild the source when the resolved URL changes (or after a retry).
    LaunchedEffect(state.playUrl, state.isHls) {
        if (state.playUrl.isEmpty()) return@LaunchedEffect

        val headers = buildMap {
            put("User-Agent", MEDIA_USER_AGENT)
            put("Referer", "https://live.bilibili.com/")
            put("Origin", "https://live.bilibili.com")
        }
        val factory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        val source = if (state.isHls) {
            HlsMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(state.playUrl))
        } else {
            ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(state.playUrl))
        }

        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // The app draws edge to edge and this screen owns its own back arrow, so the
    // whole screen has to clear the status bar.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .statusBarsPadding(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // A live stream has no timeline, so Media3's control row
                        // draws a seek bar that is permanently empty — and, worse,
                        // still draggable. Removing the three timeline views keeps
                        // the buttons that do make sense (play/pause, mute) and
                        // leaves nothing to drag.
                        setControllerVisibilityListener(
                            object : PlayerView.ControllerVisibilityListener {
                                override fun onVisibilityChanged(visibility: Int) {
                                    if (visibility == View.VISIBLE) hideTimelineViews(this@apply)
                                }
                            },
                        )
                        // The controller is inflated lazily, so also cover the
                        // case where it is on screen before the listener fires.
                        post { hideTimelineViews(this) }
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )

            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }

            // 直播弹幕 from the message queue, over the picture. There is no
            // timeline to place them on (unlike a video's danmaku), so they are
            // listed in the corner instead of flying across.
            if (state.danmakuEnabled && state.danmaku.isNotEmpty()) {
                DanmakuOverlay(
                    messages = state.danmaku.takeLast(DANMAKU_ON_SCREEN),
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
            DanmakuToggle(
                enabled = state.danmakuEnabled,
                connected = state.danmakuConnected,
                onClick = viewModel::toggleDanmaku,
                modifier = Modifier.align(Alignment.TopEnd),
            )

            if (state.isLoading) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = YtColors.Red)
                }
            }
        }

        RoomInfo(state = state)

        // The stream is unaffected by the danmaku socket, so its failure is a
        // note with a retry rather than an error panel.
        state.danmakuNotice?.let { notice ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "弹幕未连接：$notice",
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::reconnectDanmaku) {
                    Text(text = "重试", color = YtColors.Red)
                }
            }
        }

        state.error?.let { message ->
            Text(
                text = message,
                color = YtColors.Red,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** How many comments the overlay keeps on screen at once. */
private const val DANMAKU_ON_SCREEN = 7

/**
 * Removes the seek bar, elapsed time and duration from Media3's control row.
 *
 * Ids come from `androidx.media3.ui.R`; they are the views the library's own
 * `exo_player_control_view` declares, so hiding them needs no custom layout.
 */
private fun hideTimelineViews(view: PlayerView) {
    intArrayOf(
        androidx.media3.ui.R.id.exo_progress,
        androidx.media3.ui.R.id.exo_position,
        androidx.media3.ui.R.id.exo_duration,
    ).forEach { id ->
        view.findViewById<View?>(id)?.visibility = View.GONE
    }
}

/**
 * The chat overlay.
 *
 * Each line gets its own translucent plate: the stream behind it can be any
 * colour, and white-on-white is unreadable on a bright scene.
 */
@Composable
private fun DanmakuOverlay(
    messages: List<LiveDanmakuProtocol.LiveChatMessage>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(0.78f).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        messages.forEach { message ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.userName.isNotEmpty()) {
                    Text(
                        text = message.userName,
                        color = Color(0xFF9FD8FF),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = message.text,
                    color = danmakuColor(message.colorRgb, message.kind),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * `color_rgb` is a decimal 0xRRGGBB. Black is remapped: it is the default the
 * payload sends for "no colour", and black text on a dark stream is invisible.
 */
private fun danmakuColor(rgb: Int, kind: LiveDanmakuProtocol.LiveChatKind): Color = when (kind) {
    LiveDanmakuProtocol.LiveChatKind.SUPER_CHAT -> Color(0xFFFFD54F)
    LiveDanmakuProtocol.LiveChatKind.GIFT -> Color(0xFFFFAB91)
    LiveDanmakuProtocol.LiveChatKind.DANMAKU ->
        if (rgb == 0) Color.White else Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))
}

/** 弹幕开/关, with a dot showing whether the socket is actually up. */
@Composable
private fun DanmakuToggle(
    enabled: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x66000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (connected) Color(0xFF4CAF50) else Color(0xFF9E9E9E)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (enabled) "弹幕开" else "弹幕关",
            color = Color.White,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun RoomInfo(state: LiveUiState) {
    val room = state.room ?: return

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(YtColors.Red)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (room.liveStatus == 1) "直播中" else "未开播",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(8.dp))
            // The heartbeat's 人气值 is fresher than the room payload's.
            val popularity = if (state.popularity > 0) state.popularity.toLong() else room.online
            Text(
                text = formatCount(popularity) + "人气",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = room.title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = listOf(room.parentAreaName, room.areaName)
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.surfaceElevated),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = room.tags.ifEmpty { room.description.take(40) },
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
