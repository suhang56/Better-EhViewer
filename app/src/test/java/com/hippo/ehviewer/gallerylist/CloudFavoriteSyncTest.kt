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

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudFavoriteSyncTest {
    private class MemoryPersistence : CloudFavoriteCache.Persistence {
        val rows = HashMap<Long, CloudFavoriteCache.Entry>()
        var state = CloudSyncState()
        override fun loadAll() = HashMap(rows)
        override fun upsert(entries: Collection<CloudFavoriteCache.Entry>) {
            entries.forEach { rows[it.gid] = it }
        }
        override fun delete(gids: Collection<Long>) {
            gids.forEach { rows.remove(it) }
        }
        override fun replaceAll(entries: Collection<CloudFavoriteCache.Entry>) {
            rows.clear()
            upsert(entries)
        }
        override fun loadSyncState() = state
        override fun saveSyncState(state: CloudSyncState) {
            this.state = state
        }
    }

    private fun pages(vararg p: CloudFavoriteSyncer.Page): suspend (String?) -> CloudFavoriteSyncer.Page {
        val byCursor = HashMap<String?, CloudFavoriteSyncer.Page>()
        var cursor: String? = null
        p.forEach {
            byCursor[cursor] = it
            cursor = it.next
        }
        return { c -> byCursor[c] ?: throw IOException("no page $c") }
    }

    @Test
    fun completeSyncReplacesCache() = runBlocking {
        val syncer = CloudFavoriteSyncer(
            pages(
                CloudFavoriteSyncer.Page(mapOf(1L to 0, 2L to 1), "c1", 3),
                CloudFavoriteSyncer.Page(mapOf(3L to 2), null, 3),
            ),
        )
        val result = syncer.run()
        assertTrue(result is CloudFavoriteSyncer.Result.Complete)
        val persistence = MemoryPersistence()
        persistence.rows[99] = CloudFavoriteCache.Entry(99, 0, 0) // stale, removed on the website
        var time = 1000L
        val cache = CloudFavoriteCache(persistence, clock = { time })
        val started = cache.markSyncStarted()
        time = 2000
        cache.applySync(result, started)
        assertEquals(setOf(1L, 2L, 3L), persistence.rows.keys)
        assertEquals(2000L, cache.syncState.lastFullSyncAt)
        assertNull(cache.syncState.lastError)
    }

    @Test
    fun failedSyncKeepsCacheAndDoesNotClaimAbsence() = runBlocking {
        var calls = 0
        val syncer = CloudFavoriteSyncer({ cursor ->
            calls++
            if (cursor == null) CloudFavoriteSyncer.Page(mapOf(5L to 0), "next", 100) else throw IOException("network down")
        })
        val result = syncer.run()
        assertTrue(result is CloudFavoriteSyncer.Result.Failed)
        assertEquals(2, calls)

        val persistence = MemoryPersistence()
        persistence.rows[1] = CloudFavoriteCache.Entry(1, 3, 0)
        val cache = CloudFavoriteCache(persistence, clock = { 5000L })
        val started = cache.markSyncStarted()
        cache.applySync(result, started)
        // Old entries are kept, newly seen ones added.
        assertEquals(setOf(1L, 5L), persistence.rows.keys)
        assertEquals(0L, cache.syncState.lastFullSyncAt)
        assertEquals("network down", cache.syncState.lastError)

        val resolver = FavoriteResolver(
            isLocalFavorite = { false },
            cloudCacheSlot = cache::slotOf,
            inlineObservation = cache::inlineObservation,
            syncState = cache.syncState,
            signedIn = false,
            cloudCacheEmpty = cache.isEmpty,
            now = 6000L,
        )
        assertEquals(FavoriteState.CLOUD, resolver.resolve(1))
        assertEquals(FavoriteState.UNKNOWN, resolver.resolve(42))
    }

    @Test
    fun pageCapStopsSyncAsPartial() = runBlocking {
        var calls = 0
        val syncer = CloudFavoriteSyncer({ cursor ->
            calls++
            val n = cursor?.toInt() ?: 0
            CloudFavoriteSyncer.Page(mapOf(n.toLong() + 1 to 0), (n + 1).toString(), null)
        }, maxPages = 5)
        val result = syncer.run()
        assertTrue(result is CloudFavoriteSyncer.Result.Partial)
        assertEquals(5, calls)
        assertEquals(5, result.entries.size)
    }

    @Test
    fun repeatingCursorStopsSync() = runBlocking {
        var calls = 0
        val syncer = CloudFavoriteSyncer({
            calls++
            CloudFavoriteSyncer.Page(mapOf(calls.toLong() to 0), "same", null)
        })
        val result = syncer.run()
        assertTrue(result is CloudFavoriteSyncer.Result.Partial)
        assertEquals(2, calls)
    }

    @Test
    fun missingItemsMakeSyncPartial() = runBlocking {
        val syncer = CloudFavoriteSyncer({ CloudFavoriteSyncer.Page(mapOf(1L to 0), null, 2) })
        assertTrue(syncer.run() is CloudFavoriteSyncer.Result.Partial)
    }

    @Test
    fun partialSyncDoesNotDeleteOrMarkFresh() {
        val persistence = MemoryPersistence()
        persistence.rows[1] = CloudFavoriteCache.Entry(1, 0, 0)
        val cache = CloudFavoriteCache(persistence, clock = { 100L })
        cache.applySync(CloudFavoriteSyncer.Result.Partial(mapOf(2L to 0), "cap"), cache.markSyncStarted())
        assertEquals(setOf(1L, 2L), persistence.rows.keys)
        assertFalse(cache.syncState.isFresh(100L))
    }

    @Test
    fun changesDuringSyncArePreserved() {
        val persistence = MemoryPersistence()
        var time = 100L
        val cache = CloudFavoriteCache(persistence, clock = { time })
        val started = cache.markSyncStarted()
        time = 150
        cache.markFavorited(mapOf(7L to 1)) // added by the user while syncing
        cache.markRemoved(listOf(8L)) // removed by the user while syncing
        time = 200
        cache.applySync(CloudFavoriteSyncer.Result.Complete(mapOf(8L to 0, 9L to 0)), started)
        assertEquals(setOf(7L, 9L), persistence.rows.keys)
    }

    @Test
    fun listPageObservationsUpdateCache() {
        val persistence = MemoryPersistence()
        persistence.rows[1] = CloudFavoriteCache.Entry(1, 0, 0)
        persistence.rows[2] = CloudFavoriteCache.Entry(2, 0, 0)
        val cache = CloudFavoriteCache(persistence, clock = { 100L })
        // Not proven: do not delete entry 1.
        cache.recordListPage(mapOf(1L to InlineObservation.SLOT_NONE, 3L to 4), loginProven = false)
        assertEquals(setOf(1L, 2L, 3L), persistence.rows.keys)
        // Proven: entry 2 was removed on the website.
        cache.recordListPage(mapOf(2L to InlineObservation.SLOT_NONE, 3L to 4), loginProven = true)
        assertEquals(setOf(1L, 3L), persistence.rows.keys)
    }
}
