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

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.data.FavListUrlBuilder
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.CloudFavoriteInfo
import com.hippo.ehviewer.dao.buildCloudFavoritesDB
import com.hippo.util.launchIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Glue between the Android app and the pure list post-processing logic:
 * owns the cloud favourites cache, the local favourites snapshot, the version
 * chain registry and the cloud favourites synchronisation.
 */
object GalleryListFeatures {
    private const val TAG = "GalleryListFeatures"
    private const val SYNC_PAGE_DELAY_MS = 1500L

    fun interface Listener {
        /** Favourite data changed; visible lists should be re-projected. Called on the main thread. */
        fun onFavoriteDataChanged()
    }

    val versionChains = VersionChainRegistry()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = ArrayList<Listener>()
    private val syncMutex = Mutex()

    @Volatile
    var isSyncing = false
        private set

    private val cloudDb by lazy { buildCloudFavoritesDB(EhApplication.application) }

    private val persistence = object : CloudFavoriteCache.Persistence {
        private val dao get() = cloudDb.cloudFavoritesDao()
        override fun loadAll(): Map<Long, CloudFavoriteCache.Entry> = dao.list().associate { it.gid to CloudFavoriteCache.Entry(it.gid, it.slot, it.updatedAt) }
        override fun upsert(entries: Collection<CloudFavoriteCache.Entry>) = dao.upsert(entries.map { CloudFavoriteInfo(it.gid, it.slot, it.updatedAt) })
        override fun delete(gids: Collection<Long>) {
            gids.chunked(500).forEach { dao.delete(it) }
        }
        override fun replaceAll(entries: Collection<CloudFavoriteCache.Entry>) = dao.replaceAll(entries.map { CloudFavoriteInfo(it.gid, it.slot, it.updatedAt) })
        override fun loadSyncState() = CloudSyncState(Settings.cloudFavLastFullSync, Settings.cloudFavLastAttempt, Settings.cloudFavLastError)
        override fun saveSyncState(state: CloudSyncState) {
            Settings.cloudFavLastFullSync = state.lastFullSyncAt
            Settings.cloudFavLastAttempt = state.lastAttemptAt
            Settings.cloudFavLastError = state.lastError
        }
    }

    val cloudCache = CloudFavoriteCache(persistence)

    @Volatile
    private var localGids: Set<Long>? = null

    fun addListener(listener: Listener) {
        synchronized(listeners) { listeners += listener }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners -= listener }
    }

    private fun notifyChanged() {
        mainHandler.post {
            val copy = synchronized(listeners) { listeners.toList() }
            copy.forEach { it.onFavoriteDataChanged() }
        }
    }

    // ---------------------------------------------------------------- local favourites

    /** Reloads the local favourites gid set. Returns true if it changed. */
    fun reloadLocalFavorites(): Boolean {
        val new = runCatching { EhDB.localFavoriteGids }.getOrElse {
            Log.e(TAG, "Can't load local favourites", it)
            return false
        }
        val changed = new != localGids
        localGids = new
        return changed
    }

    private fun isLocalFavorite(gid: Long): Boolean {
        val set = localGids ?: run {
            reloadLocalFavorites()
            localGids
        }
        return set?.contains(gid) == true
    }

    /**
     * Called whenever a favourite changes through the app (FavouriteStatusRouter):
     * slot -1 local, 0..9 cloud, -2 removed from both.
     */
    fun onFavoriteModified(gid: Long, slot: Int) {
        reloadLocalFavorites()
        when (slot) {
            in 0..9 -> cloudCache.markFavorited(mapOf(gid to slot))
            -2 -> cloudCache.markRemoved(listOf(gid))
        }
        notifyChanged()
    }

    // ---------------------------------------------------------------- network hooks (IO thread)

    /** Favourite markers parsed from a gallery list page. */
    fun onGalleryListPage(observed: Map<Long, Int>, isExHentai: Boolean) {
        if (observed.isEmpty() || !EhCookieStore.hasSignedIn()) return
        val loginProven = isExHentai || observed.values.any { it != InlineObservation.SLOT_NONE }
        runCatching { cloudCache.recordListPage(observed, loginProven) }
            .onFailure { Log.e(TAG, "recordListPage", it) }
    }

    /** Galleries listed on a cloud favourites page are favourited. */
    fun onFavoritesPage(list: List<GalleryInfo>) {
        val map = list.filter { it.gid > 0 }.associate { it.gid to if (it.favoriteSlot in 0..9) it.favoriteSlot else InlineObservation.SLOT_UNKNOWN_COLOR }
        runCatching { cloudCache.markFavorited(map) }.onFailure { Log.e(TAG, "onFavoritesPage", it) }
    }

    fun onCloudFavoriteAdded(gid: Long, slot: Int) {
        runCatching { cloudCache.markFavorited(mapOf(gid to slot)) }.onFailure { Log.e(TAG, "add", it) }
    }

    fun onCloudFavoritesRemoved(gids: Collection<Long>) {
        runCatching { cloudCache.markRemoved(gids) }.onFailure { Log.e(TAG, "remove", it) }
    }

    fun onSignedOut() {
        runCatching { cloudCache.clear() }.onFailure { Log.e(TAG, "clear", it) }
        notifyChanged()
    }

    // ---------------------------------------------------------------- resolution

    fun newResolver(): FavoriteResolver {
        cloudCache.ensureLoaded()
        return FavoriteResolver(
            isLocalFavorite = ::isLocalFavorite,
            cloudCacheSlot = cloudCache::slotOf,
            inlineObservation = cloudCache::inlineObservation,
            syncState = cloudCache.syncState,
            signedIn = EhCookieStore.hasSignedIn(),
            cloudCacheEmpty = cloudCache.isEmpty,
            now = System.currentTimeMillis(),
        )
    }

    fun options(): GalleryListProjector.Options = GalleryListProjector.Options(
        hideFavorites = Settings.hideFavoritedGalleries,
        foldVersions = Settings.foldGalleryVersions,
        preferredLanguage = Settings.foldPreferredLanguage,
    )

    fun toEntry(gi: GalleryInfo): GalleryEntry = GalleryEntry(
        gid = gi.gid,
        title = gi.title,
        titleJpn = gi.titleJpn,
        category = gi.category,
        uploader = gi.uploader,
        tags = gi.simpleTags?.toList(),
        pages = gi.pages,
        rating = gi.rating,
        simpleLanguage = gi.simpleLanguage,
    )

    // ---------------------------------------------------------------- sync

    /** Starts a background sync if hiding is enabled, the user is signed in and the cache is stale. */
    fun maybeAutoSync() {
        if (!Settings.hideFavoritedGalleries || !EhCookieStore.hasSignedIn() || isSyncing) return
        if (!cloudCache.syncState.shouldAutoSync(System.currentTimeMillis())) return
        launchIO { sync() }
    }

    /**
     * Full sync of the cloud favourites. Single flight: concurrent calls wait for
     * the running sync and return its outcome instead of hitting the server again.
     */
    suspend fun sync(): CloudFavoriteSyncer.Result? {
        if (!EhCookieStore.hasSignedIn()) return null
        if (syncMutex.isLocked) {
            syncMutex.withLock { }
            return null
        }
        return syncMutex.withLock {
            isSyncing = true
            notifyChanged()
            try {
                val startedAt = cloudCache.markSyncStarted()
                val syncer = CloudFavoriteSyncer(
                    fetchPage = { cursor ->
                        val builder = FavListUrlBuilder(favCat = FavListUrlBuilder.FAV_CAT_ALL)
                        builder.setIndex(cursor, true)
                        val result = EhEngine.getFavoritesForSync(builder.build())
                        CloudFavoriteSyncer.Page(
                            entries = result.galleryInfoList.filter { it.gid > 0 }.associate {
                                it.gid to if (it.favoriteSlot in 0..9) it.favoriteSlot else InlineObservation.SLOT_UNKNOWN_COLOR
                            },
                            next = result.next,
                            totalCount = result.countArray.sum().takeIf { it > 0 },
                        )
                    },
                    delayBetweenPages = { delay(SYNC_PAGE_DELAY_MS) },
                )
                val result = syncer.run()
                if (result is CloudFavoriteSyncer.Result.Failed) Log.w(TAG, "Cloud favourites sync failed", result.error)
                cloudCache.applySync(result, startedAt)
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "sync", e)
                null
            } finally {
                isSyncing = false
                notifyChanged()
            }
        }
    }
}
