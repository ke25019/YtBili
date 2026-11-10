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

    /**
     * The selected channel has no published release at all.
     *
     * A real state, not a failure: while every published version is a Beta, a
     * user on the Stable channel genuinely has nothing to update to, and saying
     * so is better than an error message.
     */
    data class NoReleaseInChannel(val channel: UpdateChannel) : UpdateResult

    /** The call itself failed. [message] is the sentence to show the user. */
    data class Failed(val message: String) : UpdateResult
}

/**
 * Checks GitHub Releases for a newer build on one channel.
 *
 * Two axes, kept apart: which releases are considered (the channel) and which
 * file inside the chosen release is downloaded (the build type, see
 * [GitHubRelease.apkUrl]). The channel comes from preferences; the build type
 * from the installed package and cannot be switched.
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
     * @param channel the stream the user selected in settings.
     */
    suspend fun check(
        currentVersion: String = BuildConfig.VERSION_NAME,
        channel: UpdateChannel = UpdateChannel.Stable,
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

        // A release flagged pre-release without any marker in its tag or title is
        // how a mis-ticked publish looks. It still lands in Beta, but the log
        // line makes the metadata inconsistency findable later.
        releases.filter { it.prereleaseFlagOnly }.forEach {
            AppLog.w("update", "prerelease flag without marker: tag=${it.tagName} name=${it.name}")
        }

        val result = select(releases, currentVersion, channel)
        // Logged here rather than inside [select]: the decision has to stay pure
        // so the JVM unit tests can drive it without Android's Log stub.
        when (result) {
            is UpdateResult.Available -> AppLog.d(
                "update",
                "channel=${channel.id} newest=${result.release.tagName} local=$currentVersion " +
                    "assets=${result.release.assets.size}",
            )

            is UpdateResult.UpToDate -> AppLog.d(
                "update",
                "channel=${channel.id} up to date newest=${result.newest} local=$currentVersion",
            )

            is UpdateResult.NoReleaseInChannel ->
                AppLog.d("update", "channel=${channel.id} has no published release")

            is UpdateResult.Failed -> Unit
        }
        result
    }

    /**
     * The decision itself, split out from IO so it can be tested with fixtures.
     *
     * Drafts are dropped entirely, then the channel filters, then the highest
     * parseable version wins. List order is never trusted: GitHub returns
     * releases by creation time, which is not the same as by version.
     */
    fun select(
        releases: List<GitHubRelease>,
        currentVersion: String,
        channel: UpdateChannel,
    ): UpdateResult {
        val local = VersionTag.parse(currentVersion)
            ?: return UpdateResult.Failed("本机版本号无法解析（$currentVersion）")

        val candidates = releases.filter { !it.draft && it.channel == channel }
        if (candidates.isEmpty()) return UpdateResult.NoReleaseInChannel(channel)

        val newest = candidates
            .mapNotNull { release -> VersionTag.parse(release.tagName)?.let { it to release } }
            .maxByOrNull { it.first }
            ?: return UpdateResult.Failed("${channel.label}渠道的版本号无法解析")

        return if (newest.first > local) {
            UpdateResult.Available(newest.second, newest.first)
        } else {
            UpdateResult.UpToDate(newest.first)
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
