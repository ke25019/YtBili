package com.ytstyle.bili.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of GitHub's `repos/{owner}/{repo}/releases` list.
 *
 * Only the fields the update dialog needs are declared; the response carries a
 * great deal more (author, reactions, tarballs) and `ignoreUnknownKeys` drops it.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    /** The release notes, in Markdown — shown verbatim as plain text. */
    val body: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
) {
    /**
     * The APK to install: the one matching the **installed build type**.
     *
     * Each release is published with both, and they are signed with different
     * keys, so a debug build has to take the debug APK and a release build the
     * release one — offering the other is an install that Android refuses. When
     * only one is attached, whichever exists is used rather than nothing.
     */
    fun apkUrl(forDebugBuild: Boolean): String? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val wanted = apks.filter { it.name.contains("debug", ignoreCase = true) == forDebugBuild }
        return (wanted.ifEmpty { apks }).maxByOrNull { it.size }?.browserDownloadUrl
    }

    /** Convenience for the "查看详情" button. */
    val pageUrl: String
        get() = htmlUrl.ifBlank { "https://github.com/${UpdateChecker.REPO}/releases" }

    /** `2026-11-08T06:35:18Z` → `2026-11-08`. */
    val publishedDate: String
        get() = publishedAt.take(10)
}

@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)

/**
 * The numeric part of a version, parsed out of a tag like `v1.13.0` or
 * `1.12.4-beta`.
 *
 * Only the leading `major.minor.patch` is compared. A suffix is what makes a tag
 * a pre-release, and that is a separate question — see [isPreReleaseTag].
 */
data class VersionTag(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<VersionTag> {

    override fun compareTo(other: VersionTag): Int = when {
        major != other.major -> major.compareTo(other.major)
        minor != other.minor -> minor.compareTo(other.minor)
        else -> patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)""")

        /** Null when the string carries no `x.y.z`, so a malformed tag is skipped. */
        fun parse(raw: String): VersionTag? {
            val match = PATTERN.find(raw) ?: return null
            val (a, b, c) = match.destructured
            return VersionTag(
                major = a.toIntOrNull() ?: return null,
                minor = b.toIntOrNull() ?: return null,
                patch = c.toIntOrNull() ?: return null,
            )
        }
    }
}

/**
 * True for `v1.13.0-beta`, `v1.13.0-rc1`, `v1.13.0-alpha.2` and the like.
 *
 * Kept for the release-body/report side: the app no longer has update channels,
 * so a beta tag is no longer filtered out — it is simply reported as such.
 */
fun isPreReleaseTag(tag: String): Boolean =
    PRE_RELEASE_PATTERN.containsMatchIn(tag)

private val PRE_RELEASE_PATTERN = Regex("""-(beta|alpha|rc|nightly|dev)""", RegexOption.IGNORE_CASE)
