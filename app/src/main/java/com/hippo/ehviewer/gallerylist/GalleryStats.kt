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

import kotlin.math.max
import kotlin.math.min

/**
 * The number of ratings and the number of favourites of a gallery are only shown on its
 * detail page: neither the gallery list (any display mode) nor the gdata API carries them.
 * One detail page gives both. These classes keep the cost as low as possible:
 * - [GalleryStatsCache] remembers counts permanently. A count that reached a filter threshold
 *   is not fetched again (counts grow); one below it is re-checked after a week; counts used
 *   for sorting are refreshed after a month.
 * - [LookupThrottle] limits detail-page lookups (one at a time, spaced, hourly cap, back-off,
 *   and a long pause when E-Hentai asks to stop).
 */
class GalleryStatsCache(
    private val persistence: Persistence,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Persistence {
        fun loadAll(): Map<Long, Entry>
        fun upsert(entries: Collection<Entry>)
    }

    /** Null fields are unknown. */
    data class Entry(val gid: Long, val ratingCount: Int?, val favoriteCount: Int?, val fetchedAt: Long)

    /** What the enabled features need: filter thresholds (0 = off) and sorting by favourites. */
    data class Need(val minRatingCount: Int = 0, val minFavoriteCount: Int = 0, val sortByFavorites: Boolean = false) {
        val any: Boolean get() = minRatingCount > 0 || minFavoriteCount > 0 || sortByFavorites
    }

    @Volatile
    private var loaded: HashMap<Long, Entry>? = null

    /** False until the database was read; then the main thread can query without disk access. */
    val isLoaded: Boolean get() = loaded != null

    @Synchronized
    private fun entries(): HashMap<Long, Entry> = loaded ?: HashMap(persistence.loadAll()).also { loaded = it }

    /** Reads the database (call on a background thread). */
    fun ensureLoaded() {
        entries()
    }

    @Synchronized
    fun entryOf(gid: Long): Entry? = entries()[gid]

    @Synchronized
    fun ratingCountOf(gid: Long): Int? = entries()[gid]?.ratingCount

    @Synchronized
    fun favoriteCountOf(gid: Long): Int? = entries()[gid]?.favoriteCount

    private fun isStale(entry: Entry, maxAgeMs: Long) = clock() - entry.fetchedAt !in 0..maxAgeMs

    /**
     * The count to filter with, or null if it must be treated as unknown (never fetched, or
     * below [minCount] and stale: it may have grown, so the gallery is shown and re-checked).
     */
    @Synchronized
    fun knownRatingCount(gid: Long, minCount: Int): Int? {
        val entry = entries()[gid] ?: return null
        val count = entry.ratingCount ?: return null
        return if (count < minCount && isStale(entry, RECHECK_AFTER_MS)) null else count
    }

    /** Like [knownRatingCount] for the number of favourites. */
    @Synchronized
    fun knownFavoriteCount(gid: Long, minCount: Int): Int? {
        val entry = entries()[gid] ?: return null
        val count = entry.favoriteCount ?: return null
        return if (count < minCount && isStale(entry, RECHECK_AFTER_MS)) null else count
    }

    /** Records counts; a null keeps the known value. The fetch time moves only when both are known. */
    fun record(gid: Long, ratingCount: Int?, favoriteCount: Int?) {
        if (gid <= 0 || (ratingCount ?: -1) < 0 && (favoriteCount ?: -1) < 0) return
        val entry = synchronized(this) {
            val old = entries()[gid]
            val complete = ratingCount != null && favoriteCount != null
            Entry(
                gid,
                ratingCount?.takeIf { it >= 0 } ?: old?.ratingCount,
                favoriteCount?.takeIf { it >= 0 } ?: old?.favoriteCount,
                if (complete || old == null) clock() else old.fetchedAt,
            ).also { entries()[gid] = it }
        }
        // Disk write outside the lock: the main thread may be reading counts meanwhile.
        persistence.upsert(listOf(entry))
    }

    /** True if the detail page of [gid] must be fetched for what [need] asks. */
    @Synchronized
    fun needsLookup(gid: Long, need: Need): Boolean {
        if (!need.any) return false
        val entry = entries()[gid] ?: return true
        if (need.minRatingCount > 0) {
            val count = entry.ratingCount ?: return true
            if (count < need.minRatingCount && isStale(entry, RECHECK_AFTER_MS)) return true
        }
        if (need.minFavoriteCount > 0) {
            val count = entry.favoriteCount ?: return true
            if (count < need.minFavoriteCount && isStale(entry, RECHECK_AFTER_MS)) return true
        }
        if (need.sortByFavorites) {
            if (entry.favoriteCount == null || isStale(entry, SORT_REFRESH_AFTER_MS)) return true
        }
        return false
    }

    companion object {
        const val RECHECK_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        const val SORT_REFRESH_AFTER_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/** Spacing, hourly cap, back-off and ban pause for detail-page lookups. Not thread safe. */
class LookupThrottle(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val maxPerHour: Int = DEFAULT_MAX_PER_HOUR,
    private val clock: () -> Long = System::currentTimeMillis,
    saved: SavedState? = null,
) {
    /**
     * Survives process restarts, so neither the hourly cap nor a ban pause is reset by
     * killing the app. Wall-clock based; [delayBeforeNext] clamps values from the "future".
     */
    data class SavedState(val recent: List<Long>, val blockedUntil: Long)

    private val recent = ArrayDeque<Long>()
    private var lastRequestAt = Long.MIN_VALUE / 2
    private var blockedUntil = 0L
    private var failures = 0

    init {
        if (saved != null) {
            recent.addAll(saved.recent.sorted())
            recent.lastOrNull()?.let { lastRequestAt = it }
            blockedUntil = saved.blockedUntil
        }
    }

    val consecutiveFailures: Int get() = failures

    fun savedState(): SavedState = SavedState(recent.toList(), blockedUntil)

    /** Milliseconds to wait before the next lookup may be sent (0 = now). */
    fun delayBeforeNext(): Long {
        val now = clock()
        // The clock went back (manual change, NTP): never wait for a timestamp in the future.
        if (lastRequestAt > now) lastRequestAt = now
        while (recent.isNotEmpty() && recent.last() > now) recent.removeLast()
        blockedUntil = min(blockedUntil, now + max(BAN_PAUSE_MS, MAX_BACKOFF_MS))
        while (recent.isNotEmpty() && now - recent.first() >= HOUR_MS) recent.removeFirst()
        var wait = max(0L, lastRequestAt + minIntervalMs - now)
        wait = max(wait, blockedUntil - now)
        if (recent.size >= maxPerHour) wait = max(wait, recent.first() + HOUR_MS - now)
        return wait
    }

    fun onRequest() {
        val now = clock()
        lastRequestAt = now
        recent.addLast(now)
    }

    fun onSuccess() {
        failures = 0
    }

    /** Exponential back-off: 5 s, 10 s, 20 s ... up to 10 min. */
    fun onFailure() {
        failures++
        val backoff = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl min(failures - 1, 16))
        blockedUntil = max(blockedUntil, clock() + backoff)
    }

    /** E-Hentai said the IP is banned for too many page loads: stop for a long time. */
    fun onBanned(pauseMs: Long = BAN_PAUSE_MS) {
        blockedUntil = max(blockedUntil, clock() + pauseMs)
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 1200L
        const val DEFAULT_MAX_PER_HOUR = 300
        const val BASE_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 10L * 60 * 1000
        const val BAN_PAUSE_MS = 60L * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
    }
}

/** Reads the rating and favourite counts from a gallery detail page body without a full HTML parse. */
object GalleryStatsParser {
    private val RATING_COUNT = Regex("""id="rating_count"[^>]*>\s*([\d,]+)\s*<""")
    private val FAVORITE_COUNT = Regex("""(?:id="favcount"[^>]*>|Favorited:</td>\s*<td[^>]*>)\s*([^<]+?)\s*<""")
    private val BAN = Regex("""temporarily banned|ban expires""", RegexOption.IGNORE_CASE)
    private val SLOW_DOWN = Regex("""opening pages too fast|back down""", RegexOption.IGNORE_CASE)

    data class Stats(val ratingCount: Int?, val favoriteCount: Int?)

    /** The site asked us to stop: an IP ban, or the "opening pages too fast" warning. */
    class BannedException(message: String, val pauseMs: Long = LookupThrottle.BAN_PAUSE_MS) : Exception(message)

    const val SLOW_DOWN_PAUSE_MS = 15L * 60 * 1000

    /** "Never" -> 0, "Once" -> 1, "1,234 times" -> 1234. */
    fun parseFavorited(value: String): Int? = when (val v = value.trim()) {
        "Never" -> 0
        "Once" -> 1
        else -> v.substringBefore(' ').replace(",", "").toIntOrNull()
    }

    /**
     * @return the counts (fields null if absent), or null if the page has neither (removed gallery, error page)
     * @throws BannedException if E-Hentai reports an IP ban or asks to slow down
     */
    fun parse(body: String): Stats? {
        // An empty body is how E-Hentai answers a banned IP (see EhEngine.rethrowExactly).
        if (body.isBlank()) throw BannedException("empty response")
        val rating = RATING_COUNT.find(body)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val favorite = FAVORITE_COUNT.find(body)?.groupValues?.get(1)?.let(::parseFavorited)
        if (rating == null && favorite == null) {
            if (BAN.containsMatchIn(body)) throw BannedException(body.take(200))
            if (SLOW_DOWN.containsMatchIn(body)) throw BannedException(body.take(200), SLOW_DOWN_PAUSE_MS)
            return null
        }
        return Stats(rating, favorite)
    }
}
