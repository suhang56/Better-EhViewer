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

import kotlinx.coroutines.CancellationException

/**
 * Walks the cloud favourites pages (all categories) to rebuild the local cache.
 * The walk is bounded by [maxPages] and throttled by [delayBetweenPages] so that
 * a huge favourites list can never cause an unbounded number of requests.
 */
class CloudFavoriteSyncer(
    private val fetchPage: suspend (cursor: String?) -> Page,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val delayBetweenPages: suspend () -> Unit = {},
) {
    /**
     * @param entries gid to favourite slot (0..9, or -1 if the colour is unknown)
     * @param next cursor of the next page, null if this is the last page
     * @param totalCount total favourites reported by the site, null if unknown
     */
    class Page(val entries: Map<Long, Int>, val next: String?, val totalCount: Int?)

    sealed class Result {
        abstract val entries: Map<Long, Int>

        /** Every favourite has been seen: absence from [entries] means "not favourited". */
        class Complete(override val entries: Map<Long, Int>) : Result()

        /** Stopped early (page cap or missing items): [entries] are favourited, nothing else is known. */
        class Partial(override val entries: Map<Long, Int>, val reason: String) : Result()

        /** Request failed: [entries] seen before the failure are still valid favourites. */
        class Failed(override val entries: Map<Long, Int>, val error: Throwable) : Result()
    }

    suspend fun run(): Result {
        val seen = LinkedHashMap<Long, Int>()
        var cursor: String? = null
        var total: Int? = null
        var pages = 0
        val visitedCursors = HashSet<String>()
        while (true) {
            if (pages >= maxPages) return Result.Partial(seen, "page limit $maxPages reached")
            if (pages > 0) delayBetweenPages()
            val page = try {
                fetchPage(cursor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return Result.Failed(seen, e)
            }
            pages++
            if (total == null) total = page.totalCount
            seen.putAll(page.entries)
            val next = page.next
            if (next.isNullOrEmpty()) break
            // Protect against a server returning the same cursor forever.
            if (!visitedCursors.add(next) || page.entries.isEmpty()) {
                return Result.Partial(seen, "pagination did not advance")
            }
            cursor = next
        }
        val expected = total
        if (expected != null && seen.size < expected) {
            return Result.Partial(seen, "saw ${seen.size} of $expected favourites")
        }
        return Result.Complete(seen)
    }

    companion object {
        const val DEFAULT_MAX_PAGES = 100
    }
}
