package com.ytstyle.bili.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two update axes, exercised on constructed releases.
 *
 * These exist because the old flow had exactly one axis (the installed build
 * type) and no way to test it: the decision lived inside a network call. It now
 * lives in [UpdateChecker.select], which is why this file can drive beta
 * markers, drafts, channel gaps and APK matching without a network or a device.
 */
class UpdateSelectionTest {

    private val checker = UpdateChecker()

    private fun apk(name: String, size: Long = 1_000L) = GitHubAsset(
        name = name,
        browserDownloadUrl = "https://example.invalid/$name",
        size = size,
    )

    /** A release carrying both variants, which is how this repository publishes. */
    private fun release(
        tag: String,
        name: String = "",
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: List<GitHubAsset> = listOf(
            apk("YtBili-$tag-release.apk", size = 3_400_000L),
            apk("YtBili-$tag-debug.apk", size = 24_400_000L),
        ),
    ) = GitHubRelease(
        tagName = tag,
        name = name,
        prerelease = prerelease,
        draft = draft,
        assets = assets,
    )

    // ---- channel classification ----

    @Test
    fun `tag markers make a release beta`() {
        assertEquals(UpdateChannel.Beta, release("v1.13.4-beta").channel)
        assertEquals(UpdateChannel.Beta, release("v1.13.4-alpha").channel)
        assertEquals(UpdateChannel.Beta, release("v1.13.4-rc1").channel)
        assertEquals(UpdateChannel.Beta, release("v1.13.4-nightly").channel)
    }

    @Test
    fun `title marker alone makes a release beta`() {
        // 1.13.0 is published exactly like this: clean tag, "beta" in the title.
        assertEquals(UpdateChannel.Beta, release("v1.13.0", name = "YtBili v1.13.0-beta").channel)
    }

    @Test
    fun `a clean release with no flag is stable`() {
        assertEquals(UpdateChannel.Stable, release("v1.13.4").channel)
        assertEquals(UpdateChannel.Stable, release("v1.13.4", name = "YtBili v1.13.4").channel)
    }

    @Test
    fun `prerelease flag alone still means beta and is flagged as anomalous`() {
        val flagged = release("v1.13.4", prerelease = true)
        assertEquals(UpdateChannel.Beta, flagged.channel)
        assertTrue(flagged.prereleaseFlagOnly)
    }

    @Test
    fun `a marker without the flag is normal, not an anomaly`() {
        // Every release in this repository looks like this, so it must not be
        // reported as inconsistent.
        val marked = release("v1.13.4-beta")
        assertEquals(UpdateChannel.Beta, marked.channel)
        assertFalse(marked.prereleaseFlagOnly)
    }

    // ---- selection ----

    @Test
    fun `drafts never win`() {
        val result = checker.select(
            listOf(release("v1.13.5", draft = true)),
            currentVersion = "1.13.4",
            channel = UpdateChannel.Stable,
        )
        assertEquals(UpdateResult.NoReleaseInChannel(UpdateChannel.Stable), result)
    }

    @Test
    fun `stable channel skips beta releases`() {
        val result = checker.select(
            listOf(release("v1.13.5-beta"), release("v1.13.4")),
            currentVersion = "1.13.3",
            channel = UpdateChannel.Stable,
        )
        assertEquals("v1.13.4", (result as UpdateResult.Available).release.tagName)
    }

    @Test
    fun `beta channel ignores a newer stable release`() {
        val result = checker.select(
            listOf(release("v1.13.6"), release("v1.13.5-beta")),
            currentVersion = "1.13.4",
            channel = UpdateChannel.Beta,
        )
        assertEquals("v1.13.5-beta", (result as UpdateResult.Available).release.tagName)
    }

    @Test
    fun `a channel with nothing published says so instead of failing`() {
        val result = checker.select(
            listOf(release("v1.13.5-beta")),
            currentVersion = "1.13.4",
            channel = UpdateChannel.Stable,
        )
        assertEquals(UpdateResult.NoReleaseInChannel(UpdateChannel.Stable), result)
    }

    @Test
    fun `newest is chosen by version, not by list order`() {
        val result = checker.select(
            listOf(release("v1.13.9"), release("v1.13.10")),
            currentVersion = "1.13.8",
            channel = UpdateChannel.Stable,
        )
        assertEquals("1.13.10", (result as UpdateResult.Available).version.toString())
    }

    @Test
    fun `unparseable tags are skipped rather than crashing the check`() {
        val result = checker.select(
            listOf(release("nightly"), release("v1.13.4")),
            currentVersion = "1.13.3",
            channel = UpdateChannel.Stable,
        )
        assertEquals("v1.13.4", (result as UpdateResult.Available).release.tagName)
    }

    @Test
    fun `an identical version is up to date even with a debug suffix locally`() {
        val result = checker.select(
            listOf(release("v1.13.4")),
            currentVersion = "1.13.4-debug",
            channel = UpdateChannel.Stable,
        )
        assertEquals(UpdateResult.UpToDate(VersionTag(1, 13, 4)), result)
    }

    @Test
    fun `a newer published version wins over the installed one`() {
        val result = checker.select(
            listOf(release("v1.13.4")),
            currentVersion = "1.13.3",
            channel = UpdateChannel.Stable,
        )
        assertEquals("1.13.4", (result as UpdateResult.Available).version.toString())
    }

    // ---- APK matching: build type decides the file, channel decides the release ----

    @Test
    fun `debug builds take the debug apk and release builds the release apk`() {
        val only = release("v1.13.4")
        assertEquals(
            "https://example.invalid/YtBili-v1.13.4-debug.apk",
            only.apkUrl(forDebugBuild = true),
        )
        assertEquals(
            "https://example.invalid/YtBili-v1.13.4-release.apk",
            only.apkUrl(forDebugBuild = false),
        )
    }

    @Test
    fun `a missing variant is refused, never substituted`() {
        val releaseOnly = release("v1.13.4", assets = listOf(apk("YtBili-v1.13.4-release.apk")))
        assertNull(releaseOnly.apkUrl(forDebugBuild = true))

        val debugOnly = release("v1.13.4", assets = listOf(apk("YtBili-v1.13.4-debug.apk")))
        assertNull(debugOnly.apkUrl(forDebugBuild = false))
    }

    @Test
    fun `non-apk assets are ignored`() {
        val mixed = release(
            "v1.13.4",
            assets = listOf(
                GitHubAsset(name = "SHA256SUMS.txt"),
                apk("YtBili-v1.13.4-debug.apk"),
            ),
        )
        assertNull(mixed.apkUrl(forDebugBuild = false))
        assertEquals(
            "https://example.invalid/YtBili-v1.13.4-debug.apk",
            mixed.apkUrl(forDebugBuild = true),
        )
    }

    @Test
    fun `the largest matching asset is preferred`() {
        val doubled = release(
            "v1.13.4",
            assets = listOf(
                apk("YtBili-v1.13.4-debug.apk", size = 10L),
                apk("YtBili-v1.13.4-debug-arm64.apk", size = 20L),
            ),
        )
        assertEquals(
            "https://example.invalid/YtBili-v1.13.4-debug-arm64.apk",
            doubled.apkUrl(forDebugBuild = true),
        )
    }
}
