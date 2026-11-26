package com.ytstyle.bili.data.player

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.ytstyle.bili.data.net.DashTrack

/**
 * What this device can actually decode, and how large its screen is.
 *
 * Bilibili's own client chooses a stream by device capability: it will not hand
 * a 4K AV1 track to a decoder that cannot handle it, and it keeps the quality
 * near the screen size unless the user explicitly picks something higher. The
 * player used to take the highest-bandwidth variant of the requested tier, which
 * meant an HEVC (or AV1) variant was often chosen even when the device only
 * reliably decoded H.264 — the "某些视频播不了、切一下画质就好" family of reports.
 */
object VideoCapabilities {

    /** Bilibili's `codecs` string prefix → Android MIME type. */
    private fun mimeOf(codecs: String): String? {
        val value = codecs.trim().lowercase()
        return when {
            value.startsWith("avc") || value.startsWith("h264") -> "video/avc"
            value.startsWith("hvc") || value.startsWith("hev") -> "video/hevc"
            value.startsWith("av01") -> "video/av01"
            value.startsWith("vp09") || value.startsWith("vp9") -> "video/x-vnd.on2.vp9"
            value.startsWith("mp4v") -> "video/mp4v-es"
            else -> null
        }
    }

    /**
     * Lower is preferred. H.264 first because it is the one decoder every
     * Android device has; HEVC/AV1 are only worth it when they are known to
     * work, and a broken hardware decoder is much more common than a broken
     * software H.264 one.
     */
    fun rank(codecs: String): Int = when (mimeOf(codecs)) {
        "video/avc" -> 0
        "video/hevc" -> 1
        "video/av01" -> 2
        else -> 3
    }

    private val codecInfos: List<MediaCodecInfo> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        }.getOrDefault(emptyList())
    }

    private val supportCache = HashMap<String, Boolean>()

    /**
     * Whether a hardware/software decoder claims to handle this track.
     *
     * Unknown codecs return `true`: refusing a stream the platform might play is
     * worse than letting ExoPlayer try and report the real error. The answer is
     * cached because the same handful of variants repeats for every video.
     */
    fun canDecode(track: DashTrack): Boolean {
        val mime = mimeOf(track.codecs) ?: return true
        if (track.width <= 0 || track.height <= 0) return true
        val key = "$mime:${track.width}x${track.height}"
        synchronized(supportCache) { supportCache[key] }?.let { return it }

        val decoders = codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        val supported = decoders.any { info ->
            runCatching {
                info.getCapabilitiesForType(mime).videoCapabilities
                    ?.isSizeSupported(track.width, track.height) == true
            }.getOrDefault(false)
        }
        synchronized(supportCache) { supportCache[key] = supported }
        return supported
    }

    /**
     * The screen's short side in pixels.
     *
     * A 1080×2400 phone shows a landscape video at 2400×1080, so the number that
     * matters when comparing against a track's height is the short side, not the
     * portrait height.
     */
    fun screenShortSide(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val short = minOf(metrics.widthPixels, metrics.heightPixels)
        return if (short > 0) short else 1080
    }
}
