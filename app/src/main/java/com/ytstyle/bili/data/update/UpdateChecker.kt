package com.ytstyle.bili.data.update

import com.ytstyle.bili.BuildConfig
import com.ytstyle.bili.data.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What one update check found. */
sealed interface UpdateResult {
    /** A build newer than the installed one exists. */
    data class Available(val release: GitHubRelease, val version: VersionTag) : UpdateResult

    /** The newest build is what is already installed. */
    data class UpToDate(val newest: VersionTag?) : UpdateResult

    /** The call itself failed. [message] is the sentence to show the user. */
    data class Failed(val message: String) : UpdateResult
}

/**
 * Checks GitHub Releases for a newer build.
 *
 * One path, no channels: the newest published release is the candidate, and the
 * APK it offers is chosen to match the installed build type (see
 * [GitHubRelease.apkUrl]). Tags carry no meaning for the check any more — a
 * `-beta` tag is simply a version, which is what the repository actually uses.
 *
 * Timeouts are short and explicit: GitHub is routinely unreachable from mainland
 * China, and the user needs to be told that rather than left on a spinner.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param currentVersion the installed `BuildConfig.VERSION_NAME`.
     */
    suspend fun check(
        currentVersion: String = BuildConfig.VERSION_NAME,
    ): UpdateResult = withContext(Dispatchers.IO) {
        val releases = try {
            fetchReleases()
        } catch (e: SocketTimeoutException) {
            AppLog.w("update", "check timed out")
            return@withContext UpdateResult.Failed(
                "连接 GitHub 超时。国内直连经常失败，请稍后重试或使用代理。",
            )
        } catch (e: IOException) {
            AppLog.w("update", "check failed ${e.javaClass.simpleName}")
            return@withContext UpdateResult.Failed(
                "无法连接 GitHub（${e.javaClass.simpleName}）。请检查网络后重试。",
            )
        } catch (e: Exception) {
            AppLog.w("update", "check failed ${e.javaClass.simpleName}")
            return@withContext UpdateResult.Failed("检查更新失败：${e.message ?: "未知错误"}")
        }

        val newest = releases.firstOrNull { !it.draft }
            ?: return@withContext UpdateResult.Failed("仓库里还没有发布任何版本")

        val newestVersion = VersionTag.parse(newest.tagName)
        val local = VersionTag.parse(currentVersion)
        if (newestVersion == null || local == null) {
            return@withContext UpdateResult.Failed("版本号无法解析（远端 ${newest.tagName}）")
        }
        AppLog.d(
            "update",
            "newest=${newest.tagName} local=$currentVersion prerelease=${newest.prerelease} " +
                "assets=${newest.assets.size}",
        )
        return@withContext if (newestVersion > local) {
            UpdateResult.Available(newest, newestVersion)
        } else {
            UpdateResult.UpToDate(newestVersion)
        }
    }

    private fun fetchReleases(): List<GitHubRelease> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=$PAGE_SIZE")
            .header("Accept", "application/vnd.github+json")
            // GitHub rejects requests without a User-Agent.
            .header("User-Agent", "YtBili/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return json.decodeFromString<List<GitHubRelease>>(raw)
        }
    }

    companion object {
        /** `owner/repo` of this client's releases. */
        const val REPO = "ke25019/YtBili"

        private const val PAGE_SIZE = 30
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val CALL_TIMEOUT_SECONDS = 30L
    }
}
