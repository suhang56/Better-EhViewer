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
 * Decides whether the list should fetch the next page by itself because hiding
 * / folding left too few visible results.
 *
 * Automatic requests are capped: at most [maxConsecutiveAutoPages] pages are
 * fetched without the user scrolling, refreshing or tapping. The budget is
 * restored only by a user action, so the list can never loop on the server.
 */
class AutoLoadPolicy(
    val maxConsecutiveAutoPages: Int = DEFAULT_MAX_AUTO_PAGES,
    private val minVisibleRows: Int = DEFAULT_MIN_VISIBLE_ROWS,
    private val minVisiblePerPage: Int = DEFAULT_MIN_VISIBLE_PER_PAGE,
) {
    var autoPagesUsed: Int = 0
        private set

    val isExhausted: Boolean get() = autoPagesUsed >= maxConsecutiveAutoPages

    /** A user action (scroll, refresh, tap) restores the budget. */
    fun onUserAction() {
        autoPagesUsed = 0
    }

    /**
     * @param hasMore the site has another page
     * @param visibleRows rows currently displayed
     * @param visibleAddedByLastPage rows the last page added after hiding/folding
     */
    fun shouldAutoLoad(hasMore: Boolean, visibleRows: Int, visibleAddedByLastPage: Int): Boolean {
        if (!hasMore || isExhausted) return false
        return visibleRows < minVisibleRows || visibleAddedByLastPage < minVisiblePerPage
    }

    /** Must be called when an automatic request is actually sent. */
    fun onAutoLoad() {
        autoPagesUsed++
    }

    companion object {
        const val DEFAULT_MAX_AUTO_PAGES = 3
        const val DEFAULT_MIN_VISIBLE_ROWS = 10
        const val DEFAULT_MIN_VISIBLE_PER_PAGE = 5
    }
}
