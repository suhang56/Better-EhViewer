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
package com.hippo.ehviewer.verify

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.ehviewer.dao.CloudFavoriteInfo
import com.hippo.ehviewer.dao.CloudFavoritesDatabase
import com.hippo.ehviewer.gallerylist.CloudFavoriteCache
import com.hippo.ehviewer.gallerylist.CloudFavoriteSyncer
import com.hippo.ehviewer.gallerylist.CloudSyncState
import java.io.IOException
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trips the cloud favourites cache through the real Room DAO
 * (in-memory database), with the Persistence wired exactly like GalleryListFeatures.
 */
@RunWith(AndroidJUnit4::class)
class CloudCacheRoomTest {
    @get:Rule
    val watcher = VerifyWatcher()

    private lateinit var db: CloudFavoritesDatabase
    private var savedState = CloudSyncState()

    /** Same wiring as GalleryListFeatures.persistence, with the sync state kept in memory instead of Settings. */
    private val persistence = object : CloudFavoriteCache.Persistence {
        private val dao get() = db.cloudFavoritesDao()
        override fun loadAll(): Map<Long, CloudFavoriteCache.Entry> = dao.list().associate { it.gid to CloudFavoriteCache.Entry(it.gid, it.slot, it.updatedAt) }
        override fun upsert(entries: Collection<CloudFavoriteCache.Entry>) = dao.upsert(entries.map { CloudFavoriteInfo(it.gid, it.slot, it.updatedAt) })
        override fun delete(gids: Collection<Long>) {
            gids.chunked(500).forEach { dao.delete(it) }
        }
        override fun replaceAll(entries: Collection<CloudFavoriteCache.Entry>) = dao.replaceAll(entries.map { CloudFavoriteInfo(it.gid, it.slot, it.updatedAt) })
        override fun loadSyncState() = savedState
        override fun saveSyncState(state: CloudSyncState) {
            savedState = state
        }
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CloudFavoritesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun dbRows(): Map<Long, Int> = db.cloudFavoritesDao().list().associate { it.gid to it.slot }

    @Test
    fun cacheRoundTripsThroughRoom() {
        val report = VerifyReport("CloudCacheRoomTest.cacheRoundTripsThroughRoom")
        var now = 1_000L
        val cache = CloudFavoriteCache(persistence, clock = { now })

        cache.markFavorited(mapOf(1L to 0, 2L to 3, 3L to -1))
        report.check(dbRows() == mapOf(1L to 0, 2L to 3, 3L to -1), "upsert persisted: ${dbRows()}")
        cache.markFavorited(mapOf(2L to 5))
        report.check(dbRows()[2L] == 5, "upsert replaced slot of gid 2: ${dbRows()[2L]}")

        cache.markRemoved(listOf(1L))
        report.check(dbRows().keys == setOf(2L, 3L), "delete persisted: ${dbRows().keys}")

        // A fresh cache instance must load the same state from Room.
        val reloaded = CloudFavoriteCache(persistence, clock = { now })
        report.check(reloaded.slotOf(2L) == 5 && reloaded.slotOf(3L) == -1 && reloaded.slotOf(1L) == null, "reload from Room: 2->${reloaded.slotOf(2L)} 3->${reloaded.slotOf(3L)} 1->${reloaded.slotOf(1L)}")

        // replaceAll (@Transaction): the table ends up with exactly the new rows.
        persistence.replaceAll(listOf(CloudFavoriteCache.Entry(10, 1, now), CloudFavoriteCache.Entry(11, 2, now)))
        report.check(dbRows() == mapOf(10L to 1, 11L to 2), "replaceAll result: ${dbRows()}")

        // Complete sync: absent rows are deleted, changes made during the sync survive.
        val cache2 = CloudFavoriteCache(persistence, clock = { now })
        now = 2_000
        val started = cache2.markSyncStarted()
        now = 2_500
        cache2.markFavorited(mapOf(99L to 7)) // user added a favourite while syncing
        now = 3_000
        cache2.applySync(CloudFavoriteSyncer.Result.Complete(mapOf(11L to 4, 12L to 0)), started)
        report.check(dbRows() == mapOf(11L to 4, 12L to 0, 99L to 7), "Complete sync result in Room: ${dbRows()}")
        report.check(savedState.lastFullSyncAt == 3_000L && savedState.lastError == null, "Complete sync state: $savedState")

        // Partial sync: only adds, never deletes.
        now = 4_000
        val started2 = cache2.markSyncStarted()
        cache2.applySync(CloudFavoriteSyncer.Result.Partial(mapOf(13L to 1), "page limit"), started2)
        report.check(dbRows() == mapOf(11L to 4, 12L to 0, 99L to 7, 13L to 1), "Partial sync only added: ${dbRows()}")
        // The reason is recorded (as lastError or lastPartial, depending on the app version); the full-sync time is kept.
        report.check(savedState.lastFullSyncAt == 3_000L && savedState.toString().contains("page limit"), "Partial sync state: $savedState")

        // Failed sync: only adds, never deletes, records the error.
        now = 5_000
        val started3 = cache2.markSyncStarted()
        cache2.applySync(CloudFavoriteSyncer.Result.Failed(mapOf(14L to 2), IOException("boom")), started3)
        report.check(dbRows() == mapOf(11L to 4, 12L to 0, 99L to 7, 13L to 1, 14L to 2), "Failed sync only added: ${dbRows()}")
        report.check(savedState.lastFullSyncAt == 3_000L && savedState.lastError == "boom", "Failed sync state: $savedState")

        cache2.clear()
        report.check(dbRows().isEmpty() && savedState == CloudSyncState(), "clear() emptied Room and the sync state")
        report.assertAll()
    }

    @Test
    fun largeBatchesStayBelowSqliteVariableLimit() {
        val report = VerifyReport("CloudCacheRoomTest.largeBatches")
        val n = 40_000L // above both the old (999) and the current (32766) SQLite variable limit
        val cache = CloudFavoriteCache(persistence, clock = { 1L })
        val all = (1L..n).associateWith { (it % 10).toInt() }
        val t0 = System.currentTimeMillis()
        cache.markFavorited(all)
        report.check(db.cloudFavoritesDao().list().size.toLong() == n, "upsert of $n rows (${System.currentTimeMillis() - t0} ms)")

        val t1 = System.currentTimeMillis()
        try {
            cache.markRemoved((1L..n - 10).toList())
            report.check(dbRows().keys == ((n - 9)..n).toSet(), "chunked delete of ${n - 10} gids left ${dbRows().size} rows (${System.currentTimeMillis() - t1} ms)")
        } catch (e: Throwable) {
            report.check(false, "chunked delete of ${n - 10} gids threw $e")
        }

        // Informational: does the raw DAO cope with a single huge IN (...) list on this device?
        val raw = (1L..1500L).toList()
        persistence.upsert(raw.map { CloudFavoriteCache.Entry(it, 0, 1) })
        val rawResult = runCatching { db.cloudFavoritesDao().delete(raw) }
        report.fact("raw dao.delete(1500 gids) without chunking on API ${android.os.Build.VERSION.SDK_INT}: ${rawResult.exceptionOrNull() ?: "ok"}")

        persistence.replaceAll((1L..n).map { CloudFavoriteCache.Entry(it, 1, 2) })
        report.check(db.cloudFavoritesDao().list().size.toLong() == n, "replaceAll with $n rows")
        persistence.replaceAll(emptyList())
        report.check(db.cloudFavoritesDao().list().isEmpty(), "replaceAll(empty) cleared the table")
        report.assertAll()
    }
}
