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
 * The number of ratings of a gallery is only shown on its detail page: neither the gallery
 * list (any display mode) nor the gdata API carries it. These classes keep the cost of the
 * "minimum number of ratings" filter as low as possible:
 * - [RatingCountCache] remembers counts permanently. Counts only grow, so a gallery that
 *   reached the threshold is never fetched again; one below it is re-checked after a week.
 * - [LookupThrottle] limits detail-page lookups (one at a time, spaced, hourly cap, back-off,
 *   and a long pause when E-Hentai reports an IP ban).
 */
class RatingCountCache(
    private val persistence: Persistence,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Persistence {
        fun loadAll(): Map<Long, Entry>
        fun upsert(entries: Collection<Entry>)
    }

    data class Entry(val gid: Long, val count: Int, val fetchedAt: Long)

    private var loaded: HashMap<Long, Entry>? = null

    @Synchronized
    private fun entries(): HashMap<Long, Entry> = loaded ?: HashMap(persistence.loadAll()).also { loaded = it }

    @Synchronized
    fun countOf(gid: Long): Int? = entries()[gid]?.count

    @Synchronized
    fun record(gid: Long, count: Int) {
        if (gid <= 0 || count < 0) return
        val entry = Entry(gid, count, clock())
        entries()[gid] = entry
        persistence.upsert(listOf(entry))
    }

    /** True if the count of [gid] is unknown, or below [minCount] and older than [recheckAfterMs]. */
    @Synchronized
    fun needsLookup(gid: Long, minCount: Int, recheckAfterMs: Long = RECHECK_AFTER_MS): Boolean {
        if (minCount <= 0) return false
        val entry = entries()[gid] ?: return true
        if (entry.count >= minCount) return false
        return clock() - entry.fetchedAt !in 0..recheckAfterMs
    }

    companion object {
        const val RECHECK_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/** Spacing, hourly cap, back-off and ban pause for detail-page lookups. Not thread safe. */
class LookupThrottle(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val maxPerHour: Int = DEFAULT_MAX_PER_HOUR,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val recent = ArrayDeque<Long>()
    private var lastRequestAt = Long.MIN_VALUE / 2
    private var blockedUntil = 0L
    private var failures = 0

    val consecutiveFailures: Int get() = failures

    /** Milliseconds to wait before the next lookup may be sent (0 = now). */
    fun delayBeforeNext(): Long {
        val now = clock()
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

/** Reads the rating count from a gallery detail page body without a full HTML parse. */
object RatingCountParser {
    private val RATING_COUNT = Regex("""id="rating_count"[^>]*>\s*([\d,]+)\s*<""")
    private val BAN = Regex("""temporarily banned|ban expires""", RegexOption.IGNORE_CASE)

    class BannedException(message: String) : Exception(message)

    /**
     * @return the rating count, or null if the page has none (removed gallery, error page)
     * @throws BannedException if E-Hentai reports an IP ban
     */
    fun parse(body: String): Int? {
        // An empty body is how E-Hentai answers a banned IP (see EhEngine.rethrowExactly).
        if (body.isBlank()) throw BannedException("empty response")
        if (BAN.containsMatchIn(body) && !body.contains("rating_count")) throw BannedException(body.take(200))
        return RATING_COUNT.find(body)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    }
}
