package com.ytstyle.bili.data.update

import android.content.Context
import android.os.Environment
import com.ytstyle.bili.BuildConfig
import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.repo.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an update APK into the app's own external files directory.
 *
 * That directory rather than the shared `Downloads` folder: it needs no storage
 * permission on any supported API level, the file is removed with the app, and
 * the FileProvider declares exactly this subdirectory (`external-files-path` with
 * `Download/`), so the installer can be handed a URI for it.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Generous: the release APK is ~3.4 MB but a slow link still needs time.
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * @param onProgress null while the total size is unknown (no
     *   `Content-Length`), then 0f..1f.
     */
    suspend fun download(
        url: String,
        fileName: String,
        onProgress: (Float?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return@withContext Result.Err("无法访问下载目录")
            dir.mkdirs()
            val target = File(dir, fileName)
            if (target.exists()) target.delete()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "YtBili/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.Err("下载失败（HTTP ${response.code}）")
                }
                val body = response.body ?: return@withContext Result.Err("下载失败：响应为空")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(
                                if (total > 0) {
                                    (written.toFloat() / total).coerceIn(0f, 1f)
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
            AppLog.d("update", "apk downloaded bytes=${target.length()}")
            Result.Ok(target)
        } catch (e: IOException) {
            AppLog.w("update", "download failed ${e.javaClass.simpleName}")
            Result.Err("下载中断（${e.javaClass.simpleName}），请重试")
        } catch (e: Exception) {
            AppLog.w("update", "download failed ${e.javaClass.simpleName}")
            Result.Err("下载失败：${e.message ?: "未知错误"}")
        }
    }
}
