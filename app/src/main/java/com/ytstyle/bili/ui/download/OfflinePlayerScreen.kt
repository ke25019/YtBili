package com.ytstyle.bili.ui.download

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.ui.platform.LocalConfiguration
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.ytstyle.bili.ui.components.SystemBarsState
import com.ytstyle.bili.ui.player.OrientationUprightEffect
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ytstyle.bili.data.download.DownloadItem
import com.ytstyle.bili.ui.components.CompactSlider
import com.ytstyle.bili.ui.history.SimpleTopBar
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * Plays the two cached files with no network at all.
 *
 * Route argument: `id` (the bvid). Both tracks are plain progressive files, so
 * they are joined with [MergingMediaSource] exactly like the streaming player
 * joins two DASH URLs — the difference is only the URI scheme: `file:` here,
 * `https:` there. No headers are attached, because nothing leaves the device.
 *
 * Media3's own controller is disabled; play/pause, a seek bar and back are
 * drawn in Compose so the screen needs no XML theme or Material toolbar.
 */
@Composable
fun OfflinePlayerScreen(
    onBack: () -> Unit,
    viewModel: OfflinePlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.item
    // Checked once per item: the first frame must not block on file IO.
    val fileExists = remember(item?.videoPath) {
        item != null && item.videoPath.isNotBlank() && File(item.videoPath).exists()
    }
    // Landscape fills the frame, so the title bar would only steal height from
    // the picture; the landscape control bar carries the back arrow instead.
    val windowLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        if (!windowLandscape) {
            SimpleTopBar(title = "离线播放", onBack = onBack)
        }

        when {
            !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            item == null -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "找不到该缓存记录", color = AppTheme.colors.onBackgroundSecondary)
            }

            !fileExists -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "缓存文件已丢失，请重新下载", color = AppTheme.colors.onBackgroundSecondary)
            }

            // A new id re-enters the player instead of reusing the old position.
            else -> key(item.id) { OfflinePlayerContent(item, onBack, state.others, viewModel::select) }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun OfflinePlayerContent(
    item: DownloadItem,
    onBack: () -> Unit,
    others: List<DownloadItem>,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var aspect by remember { mutableStateOf(16f / 9f) }
    /** Set by the fullscreen button; a physically rotated window counts too. */
    var isFullscreen by remember { mutableStateOf(false) }
    /**
     * Set by the landscape exit arrow: keep the window portrait until the device
     * is upright again. Same rule as the streaming player, and for the same
     * reason — releasing it as soon as the window is portrait lets auto-rotate
     * turn it straight back.
     */
    var exitLandscapeRequested by remember { mutableStateOf(false) }
    /** The landscape 选集 panel: the other cached videos to jump to. */
    var pickerOpen by remember { mutableStateOf(false) }
    val windowLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val landscape = !exitLandscapeRequested && (isFullscreen || windowLandscape)

    // Leaving landscape is a layout change, not leaving the video.
    BackHandler(enabled = landscape) {
        isFullscreen = false
        exitLandscapeRequested = true
    }

    OrientationUprightEffect(enabled = exitLandscapeRequested) {
        exitLandscapeRequested = false
    }

    // Rotation is this screen's business; the bars themselves belong to
    // MainActivity. Publishing `hidden` there is what restores them on the way
    // out — the previous version wrote the window directly and left the
    // navigation bar hidden if a different path disposed it first.
    DisposableEffect(isFullscreen, exitLandscapeRequested) {
        when {
            isFullscreen -> {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            exitLandscapeRequested -> {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }

            else -> {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    DisposableEffect(landscape) {
        SystemBarsState.hidden = landscape
        onDispose { SystemBarsState.hidden = false }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = videoSize.width
                val h = videoSize.height
                if (w > 0 && h > 0) aspect = w.toFloat() / h
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    /** Builds the merged source from the two files. Runs once per item. */
    LaunchedEffect(item.id, item.videoPath, item.audioPath) {
        val videoFile = File(item.videoPath)
        if (!videoFile.exists()) return@LaunchedEffect

        // DefaultDataSource resolves `file:` URIs locally; using it (rather than
        // a raw FileDataSource) keeps the door open for a network fallback.
        val factory = DefaultDataSource.Factory(context)
        val videoSource = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(videoFile.toURI().toString()))

        val audioFile = File(item.audioPath)
        val source = if (audioFile.exists()) {
            val audioSource = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(audioFile.toURI().toString()))
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }

        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            isPlaying = exoPlayer.isPlaying
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Chrome state, matching the streaming player: the controls float over
        // the picture, a tap toggles them, and they fade out on their own while
        // playback runs. Previously they sat in a separate panel *below* the
        // video, which is why the offline screen did not look like the real one.
        var chromeVisible by remember { mutableStateOf(true) }
        LaunchedEffect(chromeVisible, isPlaying) {
            if (chromeVisible && isPlaying) {
                delay(3000)
                chromeVisible = false
            }
        }

        Box(
            modifier = if (landscape) {
                // Landscape: the picture owns the whole window.
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures { chromeVisible = !chromeVisible }
                    }
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures { chromeVisible = !chromeVisible }
                    }
            },
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        // Compose draws the controls below.
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Landscape 选集: the same panel idea as the streaming player's episode
            // list, listing the other cached videos by name. Portrait has the list
            // below the picture instead, so this only exists in landscape.
            if (landscape && pickerOpen && others.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(250.dp)
                        .background(Color(0xE6101014))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "选集",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    others.forEach { other ->
                        Text(
                            text = other.title.ifBlank { other.bvid },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(other.id)
                                    pickerOpen = false
                                }
                                .padding(vertical = 7.dp),
                        )
                    }
                }
            }

            // Landscape exit sits at the top-left, like the streaming player's.
            // It leaves landscape, not the video — the title bar's back is what
            // leaves the video, and that only exists in portrait.
            if (landscape && chromeVisible) {
                IconButton(
                    onClick = {
                        isFullscreen = false
                        exitLandscapeRequested = true
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "退出横屏",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (chromeVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // Same soft scrim as the streaming player's bar, so the
                        // two screens read as one player rather than two.
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    CompactSlider(
                        value = dragFraction ?: if (durationMs > 0) {
                            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        onValueChange = { dragFraction = it },
                        onValueChangeFinished = {
                            val target = dragFraction
                            if (target != null && durationMs > 0) {
                                exoPlayer.seekTo((target * durationMs).toLong())
                            }
                            dragFraction = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = YtColors.Red,
                            activeTrackColor = YtColors.Red,
                            inactiveTrackColor = Color(0x66FFFFFF),
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                        ) {
                            Icon(
                                imageVector = if (isPlaying) {
                                    Icons.Filled.Pause
                                } else {
                                    Icons.Filled.PlayArrow
                                },
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = formatTime(positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "/ ${formatTime(durationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xB3FFFFFF),
                        )
                        Spacer(Modifier.weight(1f))
                        // 选集 lives in landscape only: the portrait screen lists
                        // the rest of the cache below the video instead.
                        if (landscape && others.isNotEmpty()) {
                            IconButton(onClick = { pickerOpen = !pickerOpen }) {
                                // A text label rather than an icon: the core icon
                                // set this app ships has no list glyph.
                                Text(
                                    text = "选集",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (pickerOpen) YtColors.Red else Color.White,
                                )
                            }
                        }
                        // Landscape has neither of these any more: the way out
                        // is the top-left arrow, and the fullscreen toggle only
                        // exists in portrait, where it is the way in.
                        if (!landscape) {
                            IconButton(onClick = { isFullscreen = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Fullscreen,
                                    contentDescription = "横屏播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // The info block is portrait furniture: in landscape the picture is the
        // point, and the bar above already carries everything that is actionable.
        if (!landscape) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = item.title.ifBlank { item.bvid },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOf(item.videoLabel, item.audioLabel, formatBytes(item.totalBytes))
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.onBackgroundSecondary,
                )
            }

            // Portrait: the rest of the cache, so watching one does not mean
            // going back to the list to start the next. Landscape shows the same
            // set as an overlay panel instead (there is no room below the video).
            if (others.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "其他缓存 ${others.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.onBackground,
                    )
                    others.forEach { other ->
                        Text(
                            text = other.title.ifBlank { other.bvid },
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.colors.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(other.id) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Unwraps the Activity from a Compose context. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** mm:ss, or h:mm:ss past the hour. */
private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
