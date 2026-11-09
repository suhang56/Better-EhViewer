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
import org.junit.Test

class FavoriteResolverTest {
    private val now = 10_000_000_000L

    private fun resolver(
        local: Set<Long> = emptySet(),
        cache: Map<Long, Int> = emptyMap(),
        inline: Map<Long, InlineObservation> = emptyMap(),
        state: CloudSyncState = CloudSyncState(),
        signedIn: Boolean = true,
        cacheUpdatedAt: Long = 0,
        sessionVerified: Boolean = false,
    ) = FavoriteResolver(
        isLocalFavorite = { it in local },
        cloudCacheEntry = { gid -> cache[gid]?.let { CloudFavoriteCache.Entry(gid, it, cacheUpdatedAt) } },
        inlineObservation = { inline[it] },
        syncState = state,
        signedIn = signedIn,
        cloudCacheEmpty = cache.isEmpty(),
        now = now,
        sessionVerified = sessionVerified,
    )

    private fun obs(slot: Int, proven: Boolean = true) = InlineObservation(slot, proven, now)

    @Test
    fun localFavoriteMatchedByGidOnly() {
        val r = resolver(local = setOf(1))
        assertEquals(FavoriteState.LOCAL, r.resolve(1))
        // A different gid (e.g. same title, different upload) is not favourited by name.
        assertEquals(FavoriteState.NOT_FAVORITED, resolver(local = setOf(1), inline = mapOf(2L to obs(InlineObservation.SLOT_NONE))).resolve(2))
    }

    @Test
    fun inlineMarkerIsAuthoritativeWhenSignedIn() {
        val r = resolver(inline = mapOf(1L to obs(3), 2L to obs(InlineObservation.SLOT_NONE)))
        assertEquals(FavoriteState.CLOUD, r.resolve(1))
        assertEquals(FavoriteState.NOT_FAVORITED, r.resolve(2))
    }

    @Test
    fun conflictWithoutLoginProofIsUnknown() {
        val r = resolver(cache = mapOf(1L to 0), inline = mapOf(1L to obs(InlineObservation.SLOT_NONE, proven = false)))
        assertEquals(FavoriteState.UNKNOWN, r.resolve(1))
        val proven = resolver(cache = mapOf(1L to 0), inline = mapOf(1L to obs(InlineObservation.SLOT_NONE, proven = true)))
        assertEquals(FavoriteState.NOT_FAVORITED, proven.resolve(1))
    }

    @Test
    fun unprovenNoMarkerIsUnknownUnlessSessionVerified() {
        // Cookies present but the session may be silently logged out: a page without markers proves nothing.
        val unproven = mapOf(5L to obs(InlineObservation.SLOT_NONE, proven = false))
        assertEquals(FavoriteState.UNKNOWN, resolver(inline = unproven).resolve(5))
        // A favourites page loaded in this session proves the cookies work.
        assertEquals(FavoriteState.NOT_FAVORITED, resolver(inline = unproven, sessionVerified = true).resolve(5))
    }

    @Test
    fun newerCacheEntryBeatsOlderNoMarkerObservation() {
        val old = mapOf(5L to InlineObservation(InlineObservation.SLOT_NONE, true, now - 1000))
        // Favourited elsewhere, then found by a sync after the list page was seen.
        assertEquals(FavoriteState.CLOUD, resolver(cache = mapOf(5L to 2), inline = old, cacheUpdatedAt = now - 10).resolve(5))
        // The page is newer than the cache entry: it was removed on the website.
        assertEquals(FavoriteState.NOT_FAVORITED, resolver(cache = mapOf(5L to 2), inline = old, cacheUpdatedAt = now - 5000).resolve(5))
    }

    @Test
    fun partialSyncIsNotRetriedBeforeTtl() {
        val partial = CloudSyncState(lastAttemptAt = now - CloudSyncState.DEFAULT_RETRY - 1, lastPartial = "page_limit:100")
        assertEquals(false, partial.shouldAutoSync(now))
        assertEquals(true, partial.copy(lastAttemptAt = now - CloudSyncState.DEFAULT_TTL - 1).shouldAutoSync(now))
    }

    @Test
    fun offlineCacheIsUsedWhenNoInlineData() {
        // Signed out / offline: only the cache is available.
        val r = resolver(cache = mapOf(1L to 2), signedIn = false)
        assertEquals(FavoriteState.CLOUD, r.resolve(1))
        assertEquals(FavoriteState.UNKNOWN, r.resolve(2))
    }

    @Test
    fun freshCompleteSyncProvesAbsence() {
        val fresh = CloudSyncState(lastFullSyncAt = now - 1000, lastAttemptAt = now - 1000)
        assertEquals(FavoriteState.NOT_FAVORITED, resolver(cache = mapOf(1L to 0), state = fresh).resolve(2))
        val stale = CloudSyncState(lastFullSyncAt = now - CloudSyncState.DEFAULT_TTL - 1, lastAttemptAt = now - 1000)
        assertEquals(FavoriteState.UNKNOWN, resolver(cache = mapOf(1L to 0), state = stale).resolve(2))
    }

    @Test
    fun failedSyncNeverMeansNotFavorited() {
        val failed = CloudSyncState(lastFullSyncAt = 0, lastAttemptAt = now - 10, lastError = "timeout")
        assertEquals(FavoriteState.UNKNOWN, resolver(state = failed).resolve(5))
        assertEquals(FavoriteState.UNKNOWN, resolver(state = failed, signedIn = false, cache = mapOf(1L to 0)).resolve(5))
    }

    @Test
    fun neverSignedInMeansNoCloudFavorites() {
        assertEquals(FavoriteState.NOT_FAVORITED, resolver(signedIn = false).resolve(5))
    }

    @Test
    fun autoSyncRespectsTtlAndRetryBackoff() {
        assertEquals(true, CloudSyncState().shouldAutoSync(now))
        assertEquals(false, CloudSyncState(lastFullSyncAt = now - 1000, lastAttemptAt = now - 1000).shouldAutoSync(now))
        assertEquals(false, CloudSyncState(lastAttemptAt = now - 1000, lastError = "x").shouldAutoSync(now))
        assertEquals(true, CloudSyncState(lastAttemptAt = now - CloudSyncState.DEFAULT_RETRY - 1, lastError = "x").shouldAutoSync(now))
    }
}
