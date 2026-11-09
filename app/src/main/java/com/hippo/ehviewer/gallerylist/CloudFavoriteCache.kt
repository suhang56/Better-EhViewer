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

/**
 * In-memory view of the cloud favourites cache plus its sync state, backed by a
 * [Persistence] (a small Room database on Android, a map in tests).
 *
 * All mutations are by gallery id only.
 */
class CloudFavoriteCache(
    private val persistence: Persistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxInlineObservations: Int = 5000,
) {
    interface Persistence {
        fun loadAll(): Map<Long, Entry>
        fun upsert(entries: Collection<Entry>)
        fun delete(gids: Collection<Long>)
        fun replaceAll(entries: Collection<Entry>)
        fun loadSyncState(): CloudSyncState
        fun saveSyncState(state: CloudSyncState)
    }

    data class Entry(val gid: Long, val slot: Int, val updatedAt: Long)

    private var loaded: HashMap<Long, Entry>? = null
    private val inline = object : LinkedHashMap<Long, InlineObservation>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, InlineObservation>?): Boolean = size > maxInlineObservations
    }

    @get:Synchronized
    var syncState: CloudSyncState = CloudSyncState()
        private set

    private var stateLoaded = false

    @Synchronized
    private fun entries(): HashMap<Long, Entry> {
        loaded?.let { return it }
        val map = HashMap(persistence.loadAll())
        loaded = map
        if (!stateLoaded) {
            syncState = persistence.loadSyncState()
            stateLoaded = true
        }
        return map
    }

    @Synchronized
    fun ensureLoaded() {
        entries()
    }

    @Synchronized
    fun slotOf(gid: Long): Int? = entries()[gid]?.slot

    @get:Synchronized
    val isEmpty: Boolean get() = entries().isEmpty()

    @get:Synchronized
    val size: Int get() = entries().size

    @Synchronized
    fun inlineObservation(gid: Long): InlineObservation? = inline[gid]

    /** A favourite was added (slot 0..9) on this device or seen on a favourites page. */
    @Synchronized
    fun markFavorited(gidToSlot: Map<Long, Int>) {
        if (gidToSlot.isEmpty()) return
        val now = clock()
        val list = gidToSlot.map { (gid, slot) -> Entry(gid, slot, now) }
        val map = entries()
        list.forEach { map[it.gid] = it }
        list.forEach { inline.remove(it.gid) }
        persistence.upsert(list)
    }

    /** A favourite was removed on this device. */
    @Synchronized
    fun markRemoved(gids: Collection<Long>) {
        if (gids.isEmpty()) return
        val map = entries()
        gids.forEach { map.remove(it) }
        val now = clock()
        gids.forEach { inline[it] = InlineObservation(InlineObservation.SLOT_NONE, true, now) }
        persistence.delete(gids)
    }

    /**
     * Records the favourite markers of a gallery list page.
     *
     * @param observed gid to slot ([InlineObservation.SLOT_NONE] for "no marker")
     */
    @Synchronized
    fun recordListPage(observed: Map<Long, Int>, loginProven: Boolean) {
        if (observed.isEmpty()) return
        val now = clock()
        val favorited = HashMap<Long, Int>()
        val removed = ArrayList<Long>()
        val map = entries()
        observed.forEach { (gid, slot) ->
            inline[gid] = InlineObservation(slot, loginProven, now)
            if (slot != InlineObservation.SLOT_NONE) {
                if (map[gid]?.slot != slot) favorited[gid] = slot
            } else if (loginProven && map.containsKey(gid)) {
                removed += gid
            }
        }
        if (favorited.isNotEmpty()) {
            val list = favorited.map { (gid, slot) -> Entry(gid, slot, now) }
            list.forEach { map[it.gid] = it }
            persistence.upsert(list)
        }
        if (removed.isNotEmpty()) {
            removed.forEach { map.remove(it) }
            persistence.delete(removed)
        }
    }

    @Synchronized
    fun markSyncStarted(): Long {
        entries()
        val now = clock()
        syncState = syncState.copy(lastAttemptAt = now)
        persistence.saveSyncState(syncState)
        return now
    }

    /**
     * Applies a sync result. Only a [CloudFavoriteSyncer.Result.Complete] result may
     * delete entries; entries changed after [startedAt] (user actions during the
     * sync) are always preserved. A failure never makes anything "not favourited".
     */
    @Synchronized
    fun applySync(result: CloudFavoriteSyncer.Result, startedAt: Long) {
        val map = entries()
        val now = clock()
        when (result) {
            is CloudFavoriteSyncer.Result.Complete -> {
                val newMap = HashMap<Long, Entry>()
                result.entries.forEach { (gid, slot) -> newMap[gid] = Entry(gid, slot, now) }
                // Keep changes made while syncing.
                map.values.filter { it.updatedAt >= startedAt }.forEach { newMap[it.gid] = it }
                inline.forEach { (gid, obs) ->
                    if (!obs.favorited && obs.loginProven && obs.observedAt >= startedAt) newMap.remove(gid)
                }
                map.clear()
                map.putAll(newMap)
                persistence.replaceAll(newMap.values)
                syncState = CloudSyncState(lastFullSyncAt = now, lastAttemptAt = now, lastError = null)
            }
            is CloudFavoriteSyncer.Result.Partial -> {
                markFavorited(result.entries)
                syncState = syncState.copy(lastAttemptAt = now, lastError = result.reason)
            }
            is CloudFavoriteSyncer.Result.Failed -> {
                markFavorited(result.entries)
                syncState = syncState.copy(
                    lastAttemptAt = now,
                    lastError = result.error.message ?: result.error.javaClass.simpleName,
                )
            }
        }
        persistence.saveSyncState(syncState)
    }

    /** Forget everything, e.g. after signing out. */
    @Synchronized
    fun clear() {
        entries().clear()
        inline.clear()
        persistence.replaceAll(emptyList())
        syncState = CloudSyncState()
        persistence.saveSyncState(syncState)
    }
}
