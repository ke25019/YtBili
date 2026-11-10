package com.ytstyle.bili.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the activity is currently in picture-in-picture.
 *
 * PiP is a window mode of the *activity*, not something Compose can observe, so
 * the only component that can report it is
 * `MainActivity.onPictureInPictureModeChanged`. The player reads this to drop
 * every overlay: a PiP window is a few centimetres wide, and the control bar,
 * danmaku and top chrome that make sense full screen are exactly what "小窗
 * 播放窗口的 UI 遮挡视频内容" described.
 */
object PipState {
    var isInPip by mutableStateOf(false)
}
