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
import com.hippo.ehviewer.dao.GalleryStatsInfo
import com.hippo.ehviewer.dao.buildCloudFavoritesDB
import com.hippo.ehviewer.dao.buildGalleryStatsDB
import com.hippo.util.launchIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /** A favourites page loaded within this window proves the session cookies work. */
    private const val SESSION_VERIFIED_TTL_MS = 6L * 60 * 60 * 1000

    /** Minimum time between two automatic session checks. */
    private const val SESSION_CHECK_RETRY_MS = 30L * 60 * 1000

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

    @Volatile
    private var lastSyncResult: CloudFavoriteSyncer.Result? = null

    @Volatile
    private var syncJob: Job? = null

    @Volatile
    private var sessionVerifiedAt = 0L

    @Volatile
    private var lastSessionCheckAt = 0L

    @Volatile
    var isVerifyingSession = false
        private set

    /** True if a favourites page loaded recently with the current cookies (they are not silently logged out). */
    val isSessionVerified: Boolean
        get() = sessionVerifiedAt > 0 && System.currentTimeMillis() - sessionVerifiedAt in 0..SESSION_VERIFIED_TTL_MS

    private fun markSessionVerified() {
        sessionVerifiedAt = System.currentTimeMillis()
    }

    private val cloudDb by lazy { buildCloudFavoritesDB(EhApplication.application) }

    private val persistence = object : CloudFavoriteCache.Persistence {
        private val dao get() = cloudDb.cloudFavoritesDao()
        override fun loadAll(): Map<Long, CloudFavoriteCache.Entry> = dao.list().associate { it.gid to CloudFavoriteCache.Entry(it.gid, it.slot, it.updatedAt) }
        override fun upsert(entries: Collection<CloudFavoriteCache.Entry>) = dao.upsert(entries.map { CloudFavoriteInfo(it.gid, it.slot, it.updatedAt) })
        override fun delete(gids: Collection<Long>) {
            gids.chunked(500).forEach { dao.delete(it) }
        }
        override fun replaceAll(entries: Collection<CloudFavoriteCache.Entry>) = dao.replaceAll(entries.map { CloudFavoriteInfo(it.gid, it.slot, it.updatedAt) })
        override fun loadSyncState() = CloudSyncState(Settings.cloudFavLastFullSync, Settings.cloudFavLastAttempt, Settings.cloudFavLastError, Settings.cloudFavLastPartial)
        override fun saveSyncState(state: CloudSyncState) {
            Settings.cloudFavLastFullSync = state.lastFullSyncAt
            Settings.cloudFavLastAttempt = state.lastAttemptAt
            Settings.cloudFavLastError = state.lastError
            Settings.cloudFavLastPartial = state.lastPartial
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
        if (loginProven) markSessionVerified()
        runCatching { cloudCache.recordListPage(observed, loginProven) }
            .onFailure { Log.e(TAG, "recordListPage", it) }
    }

    /** Galleries listed on a cloud favourites page are favourited. */
    fun onFavoritesPage(list: List<GalleryInfo>) {
        // FavoritesParser throws NotLoggedInException for a guest, so reaching here proves the session.
        markSessionVerified()
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
        // A running sync belongs to the old session: stop it; its result is dropped by generation anyway.
        syncJob?.cancel()
        syncJob = null
        sessionVerifiedAt = 0
        lastSessionCheckAt = 0
        runCatching { cloudCache.clear() }.onFailure { Log.e(TAG, "clear", it) }
        notifyChanged()
    }

    // ---------------------------------------------------------------- resolution

    fun newResolver(): FavoriteResolver {
        cloudCache.ensureLoaded()
        return FavoriteResolver(
            isLocalFavorite = ::isLocalFavorite,
            cloudCacheEntry = cloudCache::entryOf,
            inlineObservation = cloudCache::inlineObservation,
            syncState = cloudCache.syncState,
            signedIn = EhCookieStore.hasSignedIn(),
            cloudCacheEmpty = cloudCache.isEmpty,
            now = System.currentTimeMillis(),
            sessionVerified = isSessionVerified,
        )
    }

    fun options(): GalleryListProjector.Options = GalleryListProjector.Options(
        hideFavorites = Settings.hideFavoritedGalleries,
        foldVersions = Settings.foldGalleryVersions,
        preferredLanguage = Settings.foldPreferredLanguage,
        minRatingCount = Settings.minRatingCount,
        minFavoriteCount = Settings.minFavoriteCount,
        sortByFavorites = Settings.sortByFavorites,
    )

    /** What the enabled count filters / sorting need from detail pages. */
    private fun statsNeed() = GalleryStatsCache.Need(Settings.minRatingCount, Settings.minFavoriteCount, Settings.sortByFavorites)

    // ---------------------------------------------------------------- rating / favourite counts

    private val statsDb by lazy { buildGalleryStatsDB(EhApplication.application) }

    /** Permanent cache of rating / favourite counts (only the detail page has them). */
    val galleryStats = GalleryStatsCache(
        object : GalleryStatsCache.Persistence {
            override fun loadAll(): Map<Long, GalleryStatsCache.Entry> = statsDb.galleryStatsDao().list()
                .associate { it.gid to GalleryStatsCache.Entry(it.gid, it.ratingCount, it.favoriteCount, it.fetchedAt) }
            override fun upsert(entries: Collection<GalleryStatsCache.Entry>) = statsDb.galleryStatsDao()
                .upsert(entries.map { GalleryStatsInfo(it.gid, it.ratingCount, it.favoriteCount, it.fetchedAt) })
        },
    )

    private val lookupThrottle = LookupThrottle(
        saved = runCatching {
            LookupThrottle.SavedState(
                Settings.ratingLookupRecent.orEmpty().split(',').mapNotNull { it.toLongOrNull() },
                Settings.ratingLookupBlockedUntil,
            )
        }.getOrNull(),
    )

    @Volatile
    private var galleryStatsLoading = false

    /** Call with [lookupThrottle] locked: keeps the hourly cap and a ban pause across restarts. */
    private fun saveThrottle() {
        val state = lookupThrottle.savedState()
        Settings.ratingLookupRecent = state.recent.joinToString(",")
        Settings.ratingLookupBlockedUntil = state.blockedUntil
    }

    /** Loads the count cache off the main thread, then refreshes the lists. */
    fun ensureGalleryStatsLoaded() {
        if (galleryStats.isLoaded || galleryStatsLoading) return
        galleryStatsLoading = true
        launchIO {
            runCatching { galleryStats.ensureLoaded() }.onFailure { Log.e(TAG, "load gallery stats", it) }
            galleryStatsLoading = false
            notifyChanged()
        }
    }
    private val lookupLock = Any()

    /** Galleries the visible list wants a rating count for, nearest to the screen first. */
    private var wantedStats: List<Pair<Long, String?>> = emptyList()

    /** Galleries whose lookup failed recently (gid -> time), skipped for [LOOKUP_RETRY_MS]. */
    private val failedLookups = HashMap<Long, Long>()

    @Volatile
    private var lookupJob: Job? = null

    private const val LOOKUP_RETRY_MS = 10L * 60 * 1000
    private const val MAX_SINGLE_WAIT_MS = 60_000L

    /** Counts seen for free (detail page opened, gallery rated); null = not known. */
    fun onGalleryStatsKnown(gid: Long, ratingCount: Int?, favoriteCount: Int?) {
        if (ratingCount == null && favoriteCount == null) return
        runCatching { galleryStats.record(gid, ratingCount, favoriteCount) }.onFailure { Log.e(TAG, "record gallery stats", it) }
        if (statsNeed().any) notifyChanged()
    }

    /**
     * Replaces the set of galleries whose rating count the list wants, in priority order
     * (on screen first). Only galleries actually shown are ever looked up, one at a time
     * and throttled by [LookupThrottle].
     */
    fun requestGalleryStats(items: List<Pair<Long, String?>>) {
        // Filtering by cache happens in the worker (IO thread): no database access on the main thread.
        val needed = if (statsNeed().any) items else emptyList()
        synchronized(lookupLock) {
            wantedStats = needed
            // Start/exit decisions are made under the same lock, so new work is never stranded
            // between a worker deciding to stop and this check.
            if (needed.isNotEmpty() && lookupJob?.isActive != true) {
                lookupJob = launchIO { runGalleryStatsLookups() }
            }
        }
    }

    /** The list is no longer visible: stop looking up. */
    fun clearGalleryStatsRequests() {
        synchronized(lookupLock) { wantedStats = emptyList() }
    }

    /** Next gallery to look up; null means the worker stops (and is then marked as gone, under the lock). */
    private fun nextLookup(now: Long): Pair<Long, String?>? = synchronized(lookupLock) {
        val need = statsNeed()
        failedLookups.entries.removeAll { now - it.value !in 0..LOOKUP_RETRY_MS }
        val next = if (!need.any) {
            null
        } else {
            wantedStats.firstOrNull { (gid, _) -> gid !in failedLookups && galleryStats.needsLookup(gid, need) }
        }
        if (next == null) lookupJob = null
        next
    }

    private suspend fun runGalleryStatsLookups() {
        while (true) {
            val (gid, token) = nextLookup(System.currentTimeMillis()) ?: return
            val wait = synchronized(lookupThrottle) { lookupThrottle.delayBeforeNext() }
            if (wait > 0) {
                // Re-check what is wanted after (at most a minute of) waiting: the user may have scrolled or left.
                delay(wait.coerceAtMost(MAX_SINGLE_WAIT_MS))
                continue
            }
            synchronized(lookupThrottle) {
                lookupThrottle.onRequest()
                saveThrottle()
            }
            try {
                val stats = EhEngine.getGalleryStats(gid, token)
                if (stats != null) {
                    synchronized(lookupThrottle) { lookupThrottle.onSuccess() }
                    galleryStats.record(gid, stats.ratingCount, stats.favoriteCount)
                    notifyChanged()
                } else {
                    // Not a gallery page (removed gallery, unexpected page): slow down, skip this one.
                    synchronized(lookupThrottle) { lookupThrottle.onFailure() }
                    synchronized(lookupLock) { failedLookups[gid] = System.currentTimeMillis() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GalleryStatsParser.BannedException) {
                Log.w(TAG, "E-Hentai asks to stop (ban / too fast), pausing rating count lookups for ${e.pauseMs / 60000} min", e)
                synchronized(lookupThrottle) {
                    lookupThrottle.onBanned(e.pauseMs)
                    saveThrottle()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Rating count lookup of $gid failed", e)
                synchronized(lookupThrottle) { lookupThrottle.onFailure() }
                synchronized(lookupLock) { failedLookups[gid] = System.currentTimeMillis() }
            }
        }
    }

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

    /**
     * Called when a list shows galleries whose favourite state is unknown. Resolves them with
     * as few requests as possible:
     * 1. if the session was not verified recently, load ONE favourites page (proves the cookies,
     *    so list pages without markers become trustworthy, and seeds the cache);
     * 2. otherwise start a full sync, at most once per [CloudSyncState.DEFAULT_TTL]
     *    (failures retried after [CloudSyncState.DEFAULT_RETRY]).
     */
    fun onUnknownFavoritesShown() {
        if (!Settings.hideFavoritedGalleries || !EhCookieStore.hasSignedIn() || isSyncing || isVerifyingSession) return
        val now = System.currentTimeMillis()
        if (!isSessionVerified) {
            if (now - lastSessionCheckAt in 0..SESSION_CHECK_RETRY_MS) return
            lastSessionCheckAt = now
            isVerifyingSession = true
            launchIO {
                try {
                    val builder = FavListUrlBuilder(favCat = FavListUrlBuilder.FAV_CAT_ALL)
                    val result = EhEngine.getFavoritesForSync(builder.build())
                    onFavoritesPage(result.galleryInfoList)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Session check failed", e)
                } finally {
                    isVerifyingSession = false
                    notifyChanged()
                }
            }
            return
        }
        if (!cloudCache.syncState.shouldAutoSync(now)) return
        syncJob = launchIO { sync() }
    }

    /** True while an automatic check or sync may still resolve unknown favourite states. */
    val isResolvingUnknown: Boolean
        get() = isSyncing || isVerifyingSession

    /**
     * Full sync of the cloud favourites. Single flight: concurrent calls wait for
     * the running sync and return its outcome instead of hitting the server again.
     */
    suspend fun sync(): CloudFavoriteSyncer.Result? {
        if (!EhCookieStore.hasSignedIn()) return null
        if (syncMutex.isLocked) {
            syncMutex.withLock { }
            return lastSyncResult
        }
        return syncMutex.withLock {
            isSyncing = true
            notifyChanged()
            try {
                val generation = cloudCache.generation
                val startedAt = cloudCache.markSyncStarted()
                val syncer = CloudFavoriteSyncer(
                    fetchPage = { cursor ->
                        // Signed out meanwhile: stop requesting pages for the old session.
                        check(cloudCache.generation == generation) { "Signed out during sync" }
                        val builder = FavListUrlBuilder(favCat = FavListUrlBuilder.FAV_CAT_ALL)
                        builder.setIndex(cursor, true)
                        val result = EhEngine.getFavoritesForSync(builder.build())
                        CloudFavoriteSyncer.Page(
                            entries = result.galleryInfoList.filter { it.gid > 0 }.associate {
                                it.gid to if (it.favoriteSlot in 0..9) it.favoriteSlot else InlineObservation.SLOT_UNKNOWN_COLOR
                            },
                            next = result.next,
                            totalCount = result.countArray.sum().takeIf { it > 0 },
                        ).also { markSessionVerified() }
                    },
                    delayBetweenPages = { delay(SYNC_PAGE_DELAY_MS) },
                )
                val result = syncer.run()
                lastSyncResult = result
                if (result is CloudFavoriteSyncer.Result.Failed) Log.w(TAG, "Cloud favourites sync failed", result.error)
                cloudCache.applySync(result, startedAt, generation)
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
