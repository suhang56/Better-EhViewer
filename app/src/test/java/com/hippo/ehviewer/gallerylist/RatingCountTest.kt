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

class RatingCountTest {
    private class MemoryPersistence : RatingCountCache.Persistence {
        val rows = HashMap<Long, RatingCountCache.Entry>()
        override fun loadAll() = HashMap(rows)
        override fun upsert(entries: Collection<RatingCountCache.Entry>) {
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
        assertEquals(116, RatingCountParser.parse(realDetailSnippet))
        assertEquals(12345, RatingCountParser.parse("""<span id="rating_count">12,345</span>"""))
        assertNull(RatingCountParser.parse("<html><body>Gallery Not Available - This gallery has been removed</body></html>"))
    }

    @Test(expected = RatingCountParser.BannedException::class)
    fun banPageIsReported() {
        RatingCountParser.parse(
            "Your IP address has been temporarily banned for excessive pageloads which indicates that you are " +
                "using automated mirroring/harvesting software. The ban expires in 59 minutes and 30 seconds",
        )
    }

    @Test(expected = RatingCountParser.BannedException::class)
    fun emptyBodyIsTreatedAsBan() {
        RatingCountParser.parse("")
    }

    // ------------------------------------------------------------------ cache

    @Test
    fun countsReachingThresholdAreNeverFetchedAgain() {
        var now = 1_000L
        val cache = RatingCountCache(MemoryPersistence(), clock = { now })
        assertTrue(cache.needsLookup(1, 50))
        cache.record(1, 80)
        now += 365L * 24 * 60 * 60 * 1000
        assertFalse("80 >= 50 stays true forever (counts only grow)", cache.needsLookup(1, 50))
        assertTrue("a higher threshold needs a new check once stale", cache.needsLookup(1, 100))
    }

    @Test
    fun belowThresholdIsRecheckedOnlyAfterAWeek() {
        var now = 1_000L
        val cache = RatingCountCache(MemoryPersistence(), clock = { now })
        cache.record(2, 3)
        assertFalse(cache.needsLookup(2, 50))
        now += RatingCountCache.RECHECK_AFTER_MS + 1
        assertTrue(cache.needsLookup(2, 50))
        assertFalse("filter off: nothing to look up", cache.needsLookup(3, 0))
    }

    @Test
    fun cacheSurvivesReload() {
        val persistence = MemoryPersistence()
        RatingCountCache(persistence, clock = { 5L }).record(7, 42)
        assertEquals(42, RatingCountCache(persistence).countOf(7))
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
}
