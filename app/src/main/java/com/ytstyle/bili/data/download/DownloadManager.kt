package com.ytstyle.bili.data.download

import com.ytstyle.bili.data.session.SessionStore
import com.ytstyle.bili.ui.player.MEDIA_USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped owner of the actual byte transfers.
 *
 * Downloads run on this singleton's own [CoroutineScope], not on a screen's
 * `viewModelScope`, so navigating away (or rotating) does not cancel them.
 *
 * This is *not* a background-download system: nothing survives the process
 * being killed, and there is no ongoing notification. Doing that properly
 * requires a `DownloadService` declared in `AndroidManifest.xml`, which this
 * module is not allowed to touch. The scope only guarantees "keeps running
 * while the app is alive".
 *
 * Cancellation and resumption are deliberately crude: 暂停 cancels the job,
 * which lands the entry in [DownloadState.FAILED]; 继续 starts the same pair of
 * files over from byte zero. `OkHttp` gets no Range headers, so this is a
 * restart, not a resume.
 */
@Singleton
class DownloadManager @Inject constructor(
    private val client: OkHttpClient,
    private val session: SessionStore,
    private val store: DownloadStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One running job per bvid. Re-downloading the same video replaces it. */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Live 0..1 fractions, keyed by bvid. Never written to disk on every tick. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** bvids with a job currently executing. */
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    init {
        // A previous process may have died mid-transfer, leaving an entry stuck
        // at 下载中. Reconcile those once, on first use. Anything already running
        // (none this early, but cheap to check) is left alone.
        scope.launch {
            runCatching {
                store.snapshot()
                    .filter { it.state == DownloadState.DOWNLOADING }
                    .forEach { stale ->
                        if (!jobs.containsKey(stale.id)) {
                            store.upsert(
                                stale.copy(
                                    state = DownloadState.FAILED,
                                    error = "上次下载未完成",
                                ),
                            )
                        }
                    }
            }
        }
    }

    /**
     * Starts downloading [item]'s pair of streams.
     *
     * The caller supplies the two signed URLs; [item] already points at the
     * destination files (see [DownloadStore.videoFile] and
     * [DownloadStore.audioFile]).
     */
    fun start(item: DownloadItem, videoUrl: String, audioUrl: String) {
        if (item.bvid.isBlank()) return
        if (jobs[item.id]?.isActive == true) return
        if (videoUrl.isBlank() || audioUrl.isBlank()) return

        val queued = item.copy(
            state = DownloadState.DOWNLOADING,
            progress = 0f,
            error = "",
            videoUrl = videoUrl,
            audioUrl = audioUrl,
        )
        _running.update { it + item.id }
        _progress.update { it + (item.id to 0f) }
        jobs[item.id] = scope.launch { run(queued, videoUrl, audioUrl) }
    }

    /**
     * Cancels a running transfer. The entry is marked 失败 with "已取消"; the UI
     * offers 继续 for those, which restarts from zero.
     */
    fun cancel(id: String) {
        val job = jobs[id] ?: return
        job.cancel()
        // Cleared here as well as in the job's own finally: a cancel that lands
        // before the coroutine is first dispatched must not leave the row
        // stuck on "下载中" with no live job behind it.
        _running.update { it - id }
        _progress.update { it - id }
    }

    /**
     * Cancels the job, waits for it to unwind (so it cannot write the index
     * again), then removes the files and the metadata.
     */
    suspend fun remove(id: String) {
        jobs[id]?.let { job ->
            job.cancel()
            // join() may return only after the blocking read times out, which is
            // acceptable here: the row already shows 删除 as done in the UI.
            runCatching { job.join() }
        }
        store.deleteFiles(id)
        store.remove(id)
        _progress.update { it - id }
        _running.update { it - id }
    }

    // ---- transfer ----

    private suspend fun run(item: DownloadItem, videoUrl: String, audioUrl: String) {
        val id = item.id
        val cookie = session.requestCookie()
        val videoFile = File(item.videoPath)
        val audioFile = File(item.audioPath)

        try {
            store.upsert(item)
            // A restart must not mix old bytes with new ones.
            videoFile.parentFile?.mkdirs()
            deleteQuietly(videoFile)
            deleteQuietly(audioFile)
            deleteQuietly(partOf(videoFile))
            deleteQuietly(partOf(audioFile))

            fetch(videoUrl, videoFile, cookie) { done, total ->
                report(id, videoBase = 0f, span = VIDEO_SHARE, done = done, total = total)
            }
            fetch(audioUrl, audioFile, cookie) { done, total ->
                report(id, videoBase = VIDEO_SHARE, span = AUDIO_SHARE, done = done, total = total)
            }

            store.upsert(
                item.copy(
                    state = DownloadState.DONE,
                    progress = 1f,
                    error = "",
                    videoBytes = videoFile.length(),
                    audioBytes = audioFile.length(),
                    videoPath = videoFile.absolutePath,
                    audioPath = audioFile.absolutePath,
                ),
            )
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                deleteQuietly(partOf(videoFile))
                deleteQuietly(partOf(audioFile))
                store.upsert(
                    item.copy(
                        state = DownloadState.FAILED,
                        progress = 0f,
                        error = "已取消",
                    ),
                )
            }
            throw cancel
        } catch (error: Exception) {
            withContext(NonCancellable) {
                deleteQuietly(partOf(videoFile))
                deleteQuietly(partOf(audioFile))
                store.upsert(
                    item.copy(
                        state = DownloadState.FAILED,
                        progress = 0f,
                        error = error.message ?: "下载失败",
                    ),
                )
            }
        } finally {
            jobs.remove(id)
            _running.update { it - id }
            _progress.update { it - id }
        }
    }

    /**
     * Streams [url] into `<target>.part`, then renames it into place.
     *
     * The rename is what makes a half-written file distinguishable from a
     * finished one: only a fully transferred file ever carries the final name,
     * so the offline player can never open a truncated stream.
     */
    private suspend fun fetch(
        url: String,
        target: File,
        cookie: String,
        onBytes: (done: Long, total: Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MEDIA_USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("服务器返回 HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")

            val part = partOf(target)
            target.parentFile?.mkdirs()
            deleteQuietly(part)

            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        // Cooperative cancellation: OkHttp's blocking read is not
                        // interruptible, so the loop checks between chunks.
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onBytes(done, body.contentLength())
                    }
                }
            }

            if (target.exists()) deleteQuietly(target)
            if (!part.renameTo(target)) {
                deleteQuietly(part)
                throw IOException("无法写入缓存文件")
            }
        }
    }

    /**
     * Weighted two-phase progress: the video track is the bulk of the bytes, so
     * it owns 85% of the bar and audio the remaining 15%.
     *
     * `Content-Length` is not always present; when it is missing the phase
     * reports 50% while it runs so the bar still moves, then completes at 100%.
     */
    private fun report(id: String, videoBase: Float, span: Float, done: Long, total: Long) {
        val within = when {
            total > 0 -> (done.toFloat() / total).coerceIn(0f, 1f)
            done > 0 -> 0.5f
            else -> 0f
        }
        val value = (videoBase + span * within).coerceIn(0f, 1f)
        _progress.update { it + (id to value) }
    }

    private fun partOf(target: File): File = File(target.parentFile, target.name + ".part")

    private fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    private companion object {
        /** The video track's share of the progress bar. */
        const val VIDEO_SHARE = 0.85f

        /** The audio track's share. Together they add up to 1. */
        const val AUDIO_SHARE = 0.15f
    }
}
