/*
 * Copyright 2026 Better-EhViewer contributors
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.ehviewer.gallerylist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryStatsTest {
    private class MemoryPersistence : GalleryStatsCache.Persistence {
        val rows = HashMap<Long, GalleryStatsCache.Entry>()
        override fun loadAll() = HashMap(rows)
        override fun upsert(entries: Collection<GalleryStatsCache.Entry>) {
            entries.forEach { rows[it.gid] = it }
        }
    }

    // ------------------------------------------------------------------ parser

    /** Snippet of a real e-hentai.org detail page (gallery 4281003), captured by the CI probe. */
    private val realDetailSnippet = """<td class="grt1">Rating:</td><td id="grt2"><div id="rating_image" class="ir" """ +
        """style="background-position:0px -21px"><img src="https://ehgt.org/g/blank.gif" usemap="#rating" /></div></td>""" +
        """<td id="grt3"><span id="rating_count">116</span></td></tr><tr><td id="rating_label" colspan="3">Average: 4.57</td>"""

    @Test
    fun parsesRatingCountFromRealDetailPage() {
        assertEquals(116, GalleryStatsParser.parse(realDetailSnippet)?.ratingCount)
        assertEquals(12345, GalleryStatsParser.parse("""<span id="rating_count">12,345</span>""")?.ratingCount)
        assertNull(GalleryStatsParser.parse("<html><body>Gallery Not Available - This gallery has been removed</body></html>")?.ratingCount)
    }

    @Test(expected = GalleryStatsParser.BannedException::class)
    fun banPageIsReported() {
        GalleryStatsParser.parse(
            "Your IP address has been temporarily banned for excessive pageloads which indicates that you are " +
                "using automated mirroring/harvesting software. The ban expires in 59 minutes and 30 seconds",
        )
    }

    @Test(expected = GalleryStatsParser.BannedException::class)
    fun emptyBodyIsTreatedAsBan() {
        GalleryStatsParser.parse("")
    }

    // ------------------------------------------------------------------ cache

    @Test
    fun countsReachingThresholdAreNeverFetchedAgain() {
        var now = 1_000L
        val cache = GalleryStatsCache(MemoryPersistence(), clock = { now })
        assertTrue(cache.needsLookup(1, GalleryStatsCache.Need(minRatingCount = 50)))
        cache.record(1, 80, null)
        now += 365L * 24 * 60 * 60 * 1000
        assertFalse("80 >= 50 stays true forever (counts only grow)", cache.needsLookup(1, GalleryStatsCache.Need(minRatingCount = 50)))
        assertTrue("a higher threshold needs a new check once stale", cache.needsLookup(1, GalleryStatsCache.Need(minRatingCount = 100)))
    }

    @Test
    fun belowThresholdIsRecheckedOnlyAfterAWeek() {
        var now = 1_000L
        val cache = GalleryStatsCache(MemoryPersistence(), clock = { now })
        cache.record(2, 3, null)
        assertFalse(cache.needsLookup(2, GalleryStatsCache.Need(minRatingCount = 50)))
        now += GalleryStatsCache.RECHECK_AFTER_MS + 1
        assertTrue(cache.needsLookup(2, GalleryStatsCache.Need(minRatingCount = 50)))
        assertFalse("filter off: nothing to look up", cache.needsLookup(3, GalleryStatsCache.Need(minRatingCount = 0)))
    }

    @Test
    fun cacheSurvivesReload() {
        val persistence = MemoryPersistence()
        GalleryStatsCache(persistence, clock = { 5L }).record(7, 42, null)
        assertEquals(42, GalleryStatsCache(persistence).ratingCountOf(7))
    }

    // ------------------------------------------------------------------ throttle

    @Test
    fun lookupsAreSpacedAndCappedPerHour() {
        var now = 0L
        val t = LookupThrottle(minIntervalMs = 1200, maxPerHour = 3, clock = { now })
        assertEquals(0, t.delayBeforeNext())
        t.onRequest()
        assertEquals(1200, t.delayBeforeNext())
        now += 1200
        t.onRequest()
        now += 1200
        t.onRequest()
        now += 1200
        // 3 requests in the last hour: wait until the first one is an hour old.
        assertEquals(60L * 60 * 1000 - 3600, t.delayBeforeNext())
        now += 60L * 60 * 1000
        assertEquals(0, t.delayBeforeNext())
    }

    @Test
    fun failuresBackOffExponentiallyAndBanPausesForAnHour() {
        var now = 0L
        val t = LookupThrottle(minIntervalMs = 0, clock = { now })
        t.onFailure()
        assertEquals(5_000, t.delayBeforeNext())
        t.onFailure()
        assertEquals(10_000, t.delayBeforeNext())
        repeat(20) { t.onFailure() }
        assertEquals(LookupThrottle.MAX_BACKOFF_MS, t.delayBeforeNext())
        t.onSuccess()
        assertEquals(0, t.consecutiveFailures)
        now += LookupThrottle.MAX_BACKOFF_MS
        t.onBanned()
        assertEquals(LookupThrottle.BAN_PAUSE_MS, t.delayBeforeNext())
    }

    // ------------------------------------------------------------------ projection

    @Test
    fun knownLowRatingCountsAreHiddenUnknownStayVisible() {
        val list = listOf(entry(1, "[A] One"), entry(2, "[A] Two"), entry(3, "[A] Three"))
        val counts = mapOf(1L to 3, 2L to 120)
        val p = GalleryListProjector.project(
            list,
            GalleryListProjector.Options(hideFavorites = false, foldVersions = false, preferredLanguage = null, minRatingCount = 50),
            favoriteState = { FavoriteState.NOT_FAVORITED },
            ratingCountOf = { counts[it] },
        )
        // 1 is known to be below 50 -> hidden; 2 passes; 3 is not looked up yet -> shown.
        assertEquals(listOf(2L, 3L), p.rows.map { it.gid })
        assertEquals(1, p.hiddenLowRatingCount)
    }

    @Test
    fun hidingTheFirstListedVersionKeepsTheGroupInPlace() {
        val list = listOf(
            entry(1, "[Other] First Work"),
            entry(10, "[作者] タイトル"),
            entry(11, "[Other] Second Work"),
            entry(13, "[Artist] Title [Chinese]", titleJpn = "[作者] タイトル [中国翻訳]", lang = "ZH"),
            entry(14, "[Artist] Title [English]", titleJpn = "[作者] タイトル [英訳]", lang = "EN"),
        )
        val options = GalleryListProjector.Options(hideFavorites = false, foldVersions = true, preferredLanguage = "EN", minRatingCount = 10)
        val before = GalleryListProjector.project(list, options, { FavoriteState.NOT_FAVORITED }, ratingCountOf = { null })
        val after = GalleryListProjector.project(list, options, { FavoriteState.NOT_FAVORITED }, ratingCountOf = { if (it == 10L) 1 else null })
        // The group stays at row 1 with the same identity; only the hidden version is gone.
        assertEquals(1, before.rows.indexOfFirst { it.groupKey == 10L })
        assertEquals(1, after.rows.indexOfFirst { it.groupKey == 10L })
        assertEquals(before.rows[1].stableId, after.rows[1].stableId)
        assertEquals(2, after.rows[1].groupSize)
        assertFalse(after.rows.any { it.gid == 10L })
        assertEquals(1, after.hiddenLowRatingCount)
    }

    @Test
    fun staleLowCountIsTreatedAsUnknownAndRechecked() {
        var now = 1_000L
        val cache = GalleryStatsCache(MemoryPersistence(), clock = { now })
        cache.record(5, 2, null)
        assertEquals(2, cache.knownRatingCount(5, 50))
        now += GalleryStatsCache.RECHECK_AFTER_MS + 1
        // Stale and below the threshold: shown again (unknown) so it can be looked up when visible.
        assertNull(cache.knownRatingCount(5, 50))
        assertTrue(cache.needsLookup(5, GalleryStatsCache.Need(minRatingCount = 50)))
        cache.record(6, 900, null)
        now += 10L * GalleryStatsCache.RECHECK_AFTER_MS
        assertEquals(900, cache.knownRatingCount(6, 50))
    }

    @Test
    fun unknownFavoritesOnlyCountListedGalleries() {
        val list = listOf(entry(1, "[A] One"), entry(2, "[A] Two"))
        val p = GalleryListProjector.project(
            list,
            GalleryListProjector.Options(hideFavorites = true, foldVersions = false, preferredLanguage = null, minRatingCount = 10),
            favoriteState = { FavoriteState.UNKNOWN },
            ratingCountOf = { if (it == 1L) 0 else null },
        )
        assertEquals(1, p.unknownFavorites)
    }

    @Test
    fun throttleSurvivesRestartAndClampsClockJumps() {
        var now = 10L * 60 * 60 * 1000
        val first = LookupThrottle(minIntervalMs = 1200, maxPerHour = 2, clock = { now })
        first.onRequest()
        now += 1200
        first.onRequest()
        first.onBanned(5_000)
        // "Process restart": a new throttle from the saved state keeps the cap and the pause.
        val restored = LookupThrottle(minIntervalMs = 1200, maxPerHour = 2, clock = { now }, saved = first.savedState())
        assertTrue(restored.delayBeforeNext() > 5_000)
        // Clock moved back one day: no waiting for timestamps in the future.
        now -= 24L * 60 * 60 * 1000
        assertTrue(restored.delayBeforeNext() <= LookupThrottle.BAN_PAUSE_MS)
    }

    @Test
    fun slowDownPageIsReported() {
        try {
            GalleryStatsParser.parse("You are opening pages too fast, thus placing a heavy load on the server. Back down, or your IP address will be automatically banned.")
            throw AssertionError("expected BannedException")
        } catch (e: GalleryStatsParser.BannedException) {
            assertEquals(GalleryStatsParser.SLOW_DOWN_PAUSE_MS, e.pauseMs)
        }
    }

    @Test
    fun ratingCountFilterOffIsIdentity() {
        val options = GalleryListProjector.Options(hideFavorites = false, foldVersions = false, preferredLanguage = null, minRatingCount = 0)
        assertTrue(options.isIdentity)
        val p = GalleryListProjector.project(listOf(entry(1, "[A] One")), options, { FavoriteState.NOT_FAVORITED }, ratingCountOf = { 0 })
        assertEquals(listOf(1L), p.rows.map { it.gid })
    }

    @Test
    fun lowRatedRepresentativeIsReplacedByNextVersion() {
        val list = listOf(
            entry(10, "[作者] タイトル"),
            entry(13, "[Artist] Title [Chinese]", titleJpn = "[作者] タイトル [中国翻訳]", lang = "ZH"),
        )
        val p = GalleryListProjector.project(
            list,
            GalleryListProjector.Options(hideFavorites = false, foldVersions = true, preferredLanguage = "ZH", minRatingCount = 10),
            favoriteState = { FavoriteState.NOT_FAVORITED },
            ratingCountOf = { if (it == 13L) 2 else 500 },
        )
        assertEquals(listOf(10L), p.rows.map { it.gid })
    }

    // ------------------------------------------------------------------ favourites

    @Test
    fun parsesFavoriteCount() {
        val page = """<tr><td class="gdt1">Favorited:</td><td class="gdt2" id="favcount">1,234 times</td></tr>""" + realDetailSnippet
        assertEquals(GalleryStatsParser.Stats(116, 1234), GalleryStatsParser.parse(page))
        assertEquals(0, GalleryStatsParser.parse("""<td class="gdt1">Favorited:</td><td class="gdt2">Never</td>""")?.favoriteCount)
        assertEquals(1, GalleryStatsParser.parse("""<td class="gdt1">Favorited:</td><td class="gdt2">Once</td>""")?.favoriteCount)
        assertEquals(57, GalleryStatsParser.parse("""<td class="gdt1">Favorited:</td><td class="gdt2" id="favcount">57 times</td>""")?.favoriteCount)
    }

    @Test
    fun recordMergesAndKeepsKnownValues() {
        var now = 100L
        val cache = GalleryStatsCache(MemoryPersistence(), clock = { now })
        cache.record(1, 10, 200)
        now = 500
        cache.record(1, 12, null) // rating only (e.g. after rating the gallery)
        assertEquals(GalleryStatsCache.Entry(1, 12, 200, 100), cache.entryOf(1))
        assertFalse(cache.needsLookup(1, GalleryStatsCache.Need(minRatingCount = 5, minFavoriteCount = 50, sortByFavorites = true)))
        assertTrue("favourite count unknown for gid 2", cache.needsLookup(2, GalleryStatsCache.Need(sortByFavorites = true)))
        cache.record(3, 4, null)
        assertTrue("only the rating count is known", cache.needsLookup(3, GalleryStatsCache.Need(minFavoriteCount = 10)))
    }

    @Test
    fun sortCountsAreRefreshedAfterAMonth() {
        var now = 1L
        val cache = GalleryStatsCache(MemoryPersistence(), clock = { now })
        cache.record(1, 10, 300)
        now += GalleryStatsCache.RECHECK_AFTER_MS + 1
        assertFalse(cache.needsLookup(1, GalleryStatsCache.Need(sortByFavorites = true)))
        now += GalleryStatsCache.SORT_REFRESH_AFTER_MS
        assertTrue(cache.needsLookup(1, GalleryStatsCache.Need(sortByFavorites = true)))
    }

    private fun sortOptions(fold: Boolean = false, minFavorites: Int = 0) = GalleryListProjector.Options(hideFavorites = false, foldVersions = fold, preferredLanguage = "ZH", minFavoriteCount = minFavorites, sortByFavorites = true)

    @Test
    fun sortsByFavoritesUnknownLastInOriginalOrder() {
        val list = listOf(entry(1, "[A] One"), entry(2, "[A] Two"), entry(3, "[A] Three"), entry(4, "[A] Four"), entry(5, "[A] Five"))
        val keys = mapOf(2L to 10, 4L to 500, 5L to 10)
        val p = GalleryListProjector.project(list, sortOptions(), { FavoriteState.NOT_FAVORITED }, sortKeyOf = { keys[it] })
        assertEquals(listOf(4L, 2L, 5L, 1L, 3L), p.rows.map { it.gid })
        // Every row still displays its own gallery and raw positions map back to it.
        p.rows.forEachIndexed { r, row ->
            assertEquals(row.gid, list[p.rowToRaw(r)].gid)
            assertEquals(r, p.rawToRow(p.rowToRaw(r)))
        }
    }

    @Test
    fun expandedVersionsMoveWithTheirGroup() {
        val list = listOf(
            entry(1, "[Other] Popular Work"),
            entry(10, "[作者] タイトル"),
            entry(13, "[Artist] Title [Chinese]", titleJpn = "[作者] タイトル [中国翻訳]", lang = "ZH"),
        )
        val keys = mapOf(1L to 5, 13L to 900)
        val p = GalleryListProjector.project(
            list,
            sortOptions(fold = true),
            { FavoriteState.NOT_FAVORITED },
            expandedGids = setOf(10L, 13L),
            sortKeyOf = { keys[it] },
        )
        assertEquals(listOf(13L, 10L, 1L), p.rows.map { it.gid })
        assertEquals(GalleryListProjector.Role.MEMBER, p.rows[1].role)
    }

    @Test
    fun minFavoriteCountHidesKnownLowOnly() {
        val list = listOf(entry(1, "[A] One"), entry(2, "[A] Two"), entry(3, "[A] Three"))
        val favs = mapOf(1L to 3, 2L to 99)
        val p = GalleryListProjector.project(
            list,
            GalleryListProjector.Options(hideFavorites = false, foldVersions = false, preferredLanguage = null, minFavoriteCount = 50),
            { FavoriteState.NOT_FAVORITED },
            favoriteCountOf = { favs[it] },
        )
        assertEquals(listOf(2L, 3L), p.rows.map { it.gid })
        assertFalse(GalleryListProjector.Options(false, false, null, minFavoriteCount = 1).isIdentity)
        assertFalse(GalleryListProjector.Options(false, false, null, sortByFavorites = true).isIdentity)
    }
}
