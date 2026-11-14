package com.ytstyle.bili.data.download

import kotlinx.serialization.Serializable

/**
 * The three states a cached item can be in.
 *
 * These are the exact strings persisted in the index and rendered by the UI.
 * "暂停" is deliberately *not* a state: pausing cancels the running download
 * (which lands in [FAILED] with a note) and 继续 restarts it from zero. A real
 * paused/resumable downloader needs per-request range bookkeeping that this
 * deliberately simple implementation does not do.
 */
object DownloadState {
    /** A transfer is (or was last known to be) running. */
    const val DOWNLOADING = "下载中"

    /** Both files are on disk and the metadata says so. */
    const val DONE = "已完成"

    /** Cancelled, interrupted, or the server refused a request. */
    const val FAILED = "失败"
}

/**
 * One cached video: a DASH video track plus a DASH audio track, both written
 * into the app's private storage.
 *
 * Every field has a default so a missing or renamed JSON field can never break
 * decoding — the index is user data, and losing the whole library because one
 * key changed shape would be the worst possible failure mode.
 *
 * `videoUrl` / `audioUrl` are extra (not part of the visible model): keeping the
 * signed stream URLs makes 继续 possible without a fresh `playUrl` round trip.
 * They expire after a while, which is why a failed resume is retried from the
 * picker.
 */
@Serializable
data class DownloadItem(
    /** Stable key: the video's bvid. */
    val id: String = "",
    val title: String = "",
    val cover: String = "",
    val bvid: String = "",
    val cid: Long = 0,
    val videoId: Int = 0,
    val videoLabel: String = "",
    val audioId: Int = 0,
    val audioLabel: String = "",
    /** Absolute paths of the two local files. */
    val videoPath: String = "",
    val audioPath: String = "",
    val videoBytes: Long = 0,
    val audioBytes: Long = 0,
    /** One of [DownloadState]; stored as the literal Chinese label. */
    val state: String = DownloadState.DONE,
    /** Last reported 0..1 fraction; only persisted at start and finish. */
    val progress: Float = 0f,
    val createdAt: Long = 0,
    /** Human-readable reason when [state] is [DownloadState.FAILED]. */
    val error: String = "",
    /** Signed source URLs, kept so 继续 can restart without re-resolving. */
    val videoUrl: String = "",
    val audioUrl: String = "",
    /**
     * True when the files were found in another client's cache rather than
     * downloaded here. Such an entry has no source URLs and cannot be resumed;
     * it exists to be listed and played, and it is removed again when the
     * experimental toggle is turned off.
     */
    val external: Boolean = false,
) {
    /** Combined on-disk size, used by the manager screen. */
    val totalBytes: Long get() = videoBytes + audioBytes
}
