package com.ytstyle.bili.data.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PGC (番剧 / 影视) payloads, pinned against **real captured responses**.
 *
 * These endpoints are the reason this test exists at all: unlike every other
 * endpoint in the app they answer with `result`, not `data`, so a model that
 * looks right still silently yields `null` at runtime. The fixtures were
 * captured live with
 *
 * ```
 * pgc/view/web/season?season_id=46585
 * pgc/player/web/playurl?ep_id=1633643&cid=30593386847&qn=80&fnval=4048&fourk=1
 * ```
 *
 * (see `H:\bilibili\diag_pgc.py`), and the assertions below pin the two facts
 * that are easy to get wrong: the envelope key, and the unit of `duration`.
 */
class PgcPayloadTest {

    /** The exact configuration used at runtime, not a test-local copy. */
    private val json: Json = NetworkModule.provideJson()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture: $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `season parses out of the result envelope`() {
        val response = json.decodeFromString<PgcResponse<PgcSeason>>(fixture("pgc_season.json"))

        assertEquals(0, response.code)
        // The whole point: the payload is under `result`, and `data` is absent.
        assertTrue("season payload missing from `result`", response.result != null)

        val season = response.result!!
        assertTrue("missing season id", season.seasonId > 0)
        assertTrue("missing media id", season.mediaId > 0)
        assertTrue("missing title", season.title.isNotEmpty())
        assertTrue("missing cover", season.cover.isNotEmpty())
        assertTrue("no episodes", season.episodes.isNotEmpty())
    }

    @Test
    fun `every episode carries what the player needs`() {
        val season = json
            .decodeFromString<PgcResponse<PgcSeason>>(fixture("pgc_season.json"))
            .result!!

        season.episodes.forEach { episode ->
            assertTrue("episode has no play id", episode.playId > 0)
            assertTrue("episode has no cid", episode.cid > 0)
            assertTrue("episode has no title", episode.title.isNotEmpty())
            assertTrue("episode has no cover", episode.cover.isNotEmpty())
        }
    }

    /**
     * An episode's `duration` is in **milliseconds** on this endpoint, while the
     * archive endpoints report seconds. Reading it as seconds would print a
     * 40-minute episode as 2458 minutes, so the unit is asserted rather than
     * assumed.
     */
    @Test
    fun `episode duration is milliseconds and converts to seconds`() {
        val season = json
            .decodeFromString<PgcResponse<PgcSeason>>(fixture("pgc_season.json"))
            .result!!

        val episode = season.episodes.first()
        assertTrue(
            "episode.duration looks like seconds, not milliseconds: ${episode.duration}",
            episode.duration > 100_000L,
        )

        val seconds = episode.durationSeconds
        // A bangumi episode is minutes long, never hours.
        assertTrue("implausible episode length: $seconds s", seconds in 60..7_200)
    }

    /**
     * The season's stat block uses `likes`/`coins`/`danmakus`, not the archive's
     * `like`/`coin`/`danmaku`, and `favorite` is the favourite count while
     * `favorites` is the 追番 count. Both were read off the live response.
     */
    @Test
    fun `season stat maps onto the archive-shaped counter card`() {
        val season = json
            .decodeFromString<PgcResponse<PgcSeason>>(fixture("pgc_season.json"))
            .result!!

        val stat = requireNotNull(season.stat) { "stat missing from season" }
        assertTrue("views must be positive", stat.views > 0)
        assertTrue("likes must be positive", stat.likes > 0)
        assertTrue("danmakus must be positive", stat.danmakus > 0)
        assertTrue("reply count must be positive", stat.reply > 0)
        // Followers (追番) and favourites are different numbers; a model that
        // swapped them would still parse, so they are compared.
        assertTrue("favorites (追番) should exceed favorite (收藏)", stat.favorites > stat.favorite)
    }

    @Test
    fun `up info provides the name the player shows`() {
        val season = json
            .decodeFromString<PgcResponse<PgcSeason>>(fixture("pgc_season.json"))
            .result!!

        val up = requireNotNull(season.upInfo) { "up_info missing" }
        assertTrue("up_info has no mid", up.mid > 0)
        assertTrue("up_info has no uname", up.uname.isNotEmpty())
        assertTrue("up_info has no avatar", up.avatar.startsWith("http"))
    }

    /**
     * The bangumi player returns the same DASH shape as the archive player, in
     * the same `result` envelope, and degrades the quality instead of failing —
     * an anonymous caller asking for 4K is answered with 480P. The player reads
     * `quality` back rather than trusting what it asked for, so this pins that
     * `quality` is always a member of `accept_quality`.
     */
    @Test
    fun `pgc playurl parses into usable dash tracks`() {
        val response = json.decodeFromString<PgcResponse<PlayUrlData>>(
            fixture("pgc_playurl.json"),
        )

        assertEquals(0, response.code)
        val data = requireNotNull(response.result) { "playurl payload missing from `result`" }

        assertTrue("accept_quality empty", data.accept_quality.isNotEmpty())
        assertTrue(
            "returned quality ${data.quality} is not in accept_quality ${data.accept_quality}",
            data.accept_quality.contains(data.quality),
        )

        val dash = requireNotNull(data.dash) { "dash payload missing" }
        assertTrue("no video tracks", dash.video.isNotEmpty())
        assertTrue("no audio tracks", dash.audio.isNotEmpty())
        dash.video.forEach { track ->
            assertTrue("video track has no baseUrl", track.baseUrl.isNotEmpty())
            assertTrue("video track has no bandwidth", track.bandwidth > 0)
        }

        // The granted tier must actually be present in the manifest, which is
        // what lets the player select by `id` instead of taking the highest
        // bandwidth track and mislabelling it. Note that the manifest may also
        // contain other tiers (this fixture carries 16 as well as 32), so the
        // assertion is "the tier exists", not "it is the only one".
        assertTrue(
            "no track for the granted quality ${data.quality}",
            dash.video.any { it.id == data.quality },
        )
        val chosen = dash.video.filter { it.id == data.quality }.maxByOrNull { it.bandwidth }
        assertNotNull("player's tier-filtered pick must resolve", chosen)
    }

    /**
     * This response carries an empty `durls`, and the nested shape of that list
     * is therefore **not** pinned here — the point of the test is that an empty
     * or differently-shaped fallback cannot break parsing, because DASH is what
     * actually plays.
     */
    @Test
    fun `pgc playurl tolerates a missing progressive fallback`() {
        val raw = fixture("pgc_playurl.json")
        assertTrue("fixture is stale: no durls key", raw.contains("\"durls\""))

        val data = json.decodeFromString<PgcResponse<PlayUrlData>>(raw).result!!
        assertTrue("durls should be empty in this fixture", data.durls.isEmpty())
        assertTrue("durl should be empty in this fixture", data.durl.isEmpty())
        // Null is the right answer: there is nothing to fall back to, and the
        // player only consults this when `dash` is absent.
        assertTrue("progressiveUrl should be null", data.progressiveUrl == null)
        assertNotNull("dash must be present instead", data.dash)
    }
}
