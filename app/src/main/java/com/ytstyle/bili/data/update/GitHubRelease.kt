package com.ytstyle.bili.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which stream of releases this app follows.
 *
 * Deliberately independent of the build type. Debug and release are two package
 * names decided at build time and cannot be switched at runtime; the channel is
 * the user's choice, stored in preferences. The two dimensions never mix: the
 * channel decides *which release*, the build type decides *which APK inside it*.
 */
enum class UpdateChannel(val id: String, val label: String) {
    Stable("stable", "稳定版"),
    Beta("beta", "测试版");

    companion object {
        /** Unknown or missing ids fall back to [Stable], the shipped default. */
        fun fromId(id: String?): UpdateChannel = entries.firstOrNull { it.id == id } ?: Stable
    }
}

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
     * Null when the release does not carry that variant. There is deliberately
     * no "fall back to whatever APK is attached": the two variants have
     * different package names, so handing a debug build the release APK does not
     * update it, it installs a second app, and handing a release build the debug
     * APK does the same. Refusing is the only correct answer.
     */
    fun apkUrl(forDebugBuild: Boolean): String? =
        assets.asSequence()
            .filter { it.isApk }
            .filter { it.isDebugApk == forDebugBuild }
            .maxByOrNull { it.size }
            ?.browserDownloadUrl

    /**
     * True when either the tag or the release title marks this as a pre-release.
     *
     * Both are checked because the repository's history is inconsistent: older
     * versions carry `-beta` only in the tag, 1.13.0 carries it only in the
     * title, and GitHub's own `prerelease` flag is false for every release ever
     * published here.
     */
    private val markedAsPrerelease: Boolean
        get() = PRERELEASE_MARKERS.containsMatchIn(tagName) ||
            PRERELEASE_MARKERS.containsMatchIn(name)

    /** The channel this release belongs to. */
    val channel: UpdateChannel
        get() = if (markedAsPrerelease || prerelease) UpdateChannel.Beta else UpdateChannel.Stable

    /**
     * GitHub says "pre-release" but neither the tag nor the title says so.
     *
     * That combination is what a mis-ticked publish produces; the release still
     * goes to Beta (the flag is the stronger signal), but it is worth a log line
     * because it means the metadata disagrees with itself.
     */
    val prereleaseFlagOnly: Boolean
        get() = prerelease && !markedAsPrerelease

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
    /** `sha256:…` when GitHub provides one; empty on older uploads. */
    val digest: String = "",
) {
    val isApk: Boolean get() = name.endsWith(".apk", ignoreCase = true)

    /** `YtBili-1.13.3-debug.apk` → true, `…-release.apk` → false. */
    val isDebugApk: Boolean get() = name.contains("debug", ignoreCase = true)
}

/**
 * The numeric part of a version, parsed out of a tag like `v1.13.0` or
 * `1.12.4-beta`.
 *
 * Only the leading `major.minor.patch` is compared. The channel is a separate
 * axis and is applied by filtering before any comparison happens, so a
 * pre-release never has to be ordered against a stable build here.
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
 * True for `v1.13.0-beta`, `v1.13.0-rc1`, `v1.13.3-alpha.2` and the like.
 *
 * Anchored on the dash rather than a word boundary: versions carry suffixes
 * like `-rc1`, where `rc` is followed by a digit and a `\b` would not match.
 */
fun isPreReleaseTag(tag: String): Boolean = PRERELEASE_MARKERS.containsMatchIn(tag)

private val PRERELEASE_MARKERS =
    Regex("""-(beta|alpha|rc|nightly|dev|preview)""", RegexOption.IGNORE_CASE)
