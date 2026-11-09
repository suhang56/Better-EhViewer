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

import android.annotation.SuppressLint
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.ui.scene.GalleryAdapter

/**
 * Sits between a gallery list's raw paged data and its RecyclerView adapter.
 *
 * When both features are disabled it is a strict pass-through (positions are
 * identical, notifications are forwarded unchanged), so the list behaves
 * exactly like before.
 */
internal class ProjectedListController(
    private val data: () -> List<GalleryInfo?>,
    private val adapter: () -> RecyclerView.Adapter<*>?,
    /** Gids of expanded groups. Owned by the scene so it survives view recreation and is saved in its state. */
    private val expandedGids: MutableSet<Long> = HashSet(),
    /** Favourites sort order. Owned by the scene so returning from a gallery keeps the order. */
    private val sortState: SortState = SortState(),
) {
    class SortState {
        /** Favourite counts the current order is based on (see [SortSnapshotPolicy]). */
        var snapshot: Map<Long, Int> = emptyMap()
    }

    var options: GalleryListProjector.Options = GalleryListFeatures.options()
        private set

    val isActive: Boolean get() = !options.isIdentity

    val autoLoadPolicy = AutoLoadPolicy()

    private var projection: GalleryListProjector.Projection? = null

    val visibleCount: Int get() = if (isActive) current().size else data().size
    val unknownFavorites: Int get() = if (isActive) current().unknownFavorites else 0
    val hiddenCount: Int get() = if (isActive) current().let { it.hiddenFavorites + it.foldedAway + it.duplicates + it.hiddenLowRatingCount } else 0

    /** Favourites-sort progress: galleries still to count (call after the rows were computed). */
    val sortPendingCount: Int get() = if (isActive && options.sortByFavorites) current().let { sortPending } else 0

    private fun current(): GalleryListProjector.Projection = projection ?: compute().also { projection = it }

    /** Listed galleries whose favourite count is still awaited while sorting (0 = sorted). */
    var sortPending: Int = 0
        private set

    /** The last compute() replaced the sort order: rows moved as a whole, not one by one. */
    private var sortOrderChanged = false

    private fun compute(): GalleryListProjector.Projection {
        val raw = data()
        val entries = raw.map { it?.let(GalleryListFeatures::toEntry) }
        val resolver = if (options.hideFavorites) GalleryListFeatures.newResolver() else null
        val chains = GalleryListFeatures.versionChains
        val stats = GalleryListFeatures.galleryStats
        // Never read the database on the main thread: counts are unknown until loaded in the background.
        val statsLoaded = !options.needsGalleryStats || stats.isLoaded || false.also { GalleryListFeatures.ensureGalleryStatsLoaded() }
        fun project(opts: GalleryListProjector.Options) = GalleryListProjector.project(
            entries = entries,
            options = opts,
            favoriteState = { gid -> resolver?.resolve(gid) ?: FavoriteState.NOT_FAVORITED },
            chainRootOf = chains::rootOf,
            newestOf = chains::newestOf,
            expandedGids = expandedGids,
            // Stale low counts count as unknown, so the gallery is shown and re-checked.
            ratingCountOf = { gid -> if (opts.minRatingCount > 0 && statsLoaded) stats.knownRatingCount(gid, opts.minRatingCount) else null },
            favoriteCountOf = { gid -> if (opts.minFavoriteCount > 0 && statsLoaded) stats.knownFavoriteCount(gid, opts.minFavoriteCount) else null },
            sortKeyOf = { gid -> sortState.snapshot[gid] },
        )
        sortOrderChanged = false
        if (!options.sortByFavorites) {
            sortPending = 0
            return project(options)
        }
        // Only top-level rows count: a folded group sorts by its versions' best known count.
        val unsorted = project(options.copy(sortByFavorites = false))
        val sortNeed = GalleryStatsCache.Need(sortByFavorites = true)
        val heads = unsorted.rows.filter { it.role != GalleryListProjector.Role.MEMBER }.map { row ->
            val count = if (statsLoaded) stats.favoriteCountOf(row.gid) else null
            SortSnapshotPolicy.Head(
                gid = row.gid,
                count = count,
                fresh = count != null && !stats.needsLookup(row.gid, sortNeed),
                blocked = GalleryListFeatures.isLookupBlocked(row.gid),
            )
        }
        val others = HashMap<Long, Int>()
        if (statsLoaded) {
            unsorted.groupMembers.values.flatten().forEach { gid -> stats.favoriteCountOf(gid)?.let { others[gid] = it } }
        }
        val decision = SortSnapshotPolicy.decide(heads, others, sortState.snapshot)
        sortPending = decision.pending
        decision.snapshot?.let {
            sortState.snapshot = it
            sortOrderChanged = true
        }
        return project(options)
    }

    fun isMemberRow(adapterPosition: Int): Boolean = isActive && current().rows.getOrNull(adapterPosition)?.role == GalleryListProjector.Role.MEMBER

    /**
     * With rows sorted, the "position in the pages" of the screen is the earliest page position
     * among the rows from [adapterPosition] on (what the user has not scrolled past yet), so
     * quick-search progress never skips galleries that were sorted further down.
     */
    fun earliestAnchorFrom(adapterPosition: Int): Int {
        if (!isActive) return adapterPosition
        val rows = current().rows
        if (adapterPosition !in rows.indices) return current().rowToAnchor(adapterPosition)
        return (adapterPosition until rows.size).minOf { rows[it].anchorIndex }
    }

    /** A new list replaces the data: forget the previous list's sort order. */
    fun resetSort() {
        sortState.snapshot = emptyMap()
    }

    /** Re-reads the settings. Returns true if they changed (the caller should then [refreshAll]). */
    fun reloadOptions(): Boolean {
        val new = GalleryListFeatures.options()
        if (new == options) return false
        options = new
        expandedGids.clear()
        sortState.snapshot = emptyMap()
        projection = null
        return true
    }

    // ------------------------------------------------------------------ positions

    fun size(): Int = visibleCount

    /** Raw data index of the gallery DISPLAYED at [adapterPosition] (what is bound, opened, long-pressed). */
    fun dataIndex(adapterPosition: Int): Int = if (isActive) current().rowToRaw(adapterPosition) else adapterPosition

    /** Raw data index anchoring [adapterPosition] in the pages (where its group first appeared). */
    fun anchorIndex(adapterPosition: Int): Int = if (isActive) current().rowToAnchor(adapterPosition) else adapterPosition

    fun adapterIndex(dataPosition: Int): Int = if (isActive) current().rawToRow(dataPosition) else dataPosition

    fun rowState(adapterPosition: Int): GalleryAdapter.RowState? {
        if (!isActive) return null
        val row = current().rows.getOrNull(adapterPosition) ?: return null
        return GalleryAdapter.RowState(
            otherVersions = if (row.role == GalleryListProjector.Role.SINGLE) 0 else row.groupSize - 1,
            expanded = row.expanded,
            isOtherVersion = row.role == GalleryListProjector.Role.MEMBER,
            favoriteUnknown = row.favoriteState == FavoriteState.UNKNOWN,
        )
    }

    // ------------------------------------------------------------------ notifications

    @SuppressLint("NotifyDataSetChanged")
    fun refreshAll() {
        projection = null
        adapter()?.notifyDataSetChanged()
    }

    fun onRangeInserted(positionStart: Int, itemCount: Int) {
        if (!isActive) {
            adapter()?.notifyItemRangeInserted(positionStart, itemCount)
        } else {
            refreshWithDiff()
        }
    }

    /** Recomputes the rows and dispatches fine-grained changes, keeping the scroll position. */
    fun refreshWithDiff() {
        if (!isActive) {
            refreshAll()
            return
        }
        val old = projection
        if (old == null) {
            refreshAll()
            return
        }
        val new = compute()
        projection = new
        if (sortOrderChanged) {
            // The whole list was re-sorted at once. Item moves would make RecyclerView follow the old
            // top row to its new rank (often the bottom); a full rebind keeps the scroll position.
            refreshAll()
            return
        }
        val adapter = adapter() ?: return
        DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = new.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = old.rows[oldItemPosition].stableId == new.rows[newItemPosition].stableId
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val a = old.rows[oldItemPosition]
                    val b = new.rows[newItemPosition]
                    return a.gid == b.gid && a.rawIndex == b.rawIndex && a.role == b.role && a.groupSize == b.groupSize &&
                        a.expanded == b.expanded && a.favoriteState == b.favoriteState
                }
            },
            // Sorting moves rows: detect moves so they animate instead of being removed and re-added.
            options.sortByFavorites,
        ).dispatchUpdatesTo(adapter)
    }

    /** Expands or collapses the group shown at [adapterPosition]. */
    fun toggle(adapterPosition: Int) {
        if (!isActive) return
        val projection = current()
        val row = projection.rows.getOrNull(adapterPosition) ?: return
        val members = projection.groupMembers[row.groupKey] ?: return
        if (row.expanded) expandedGids -= members.toSet() else expandedGids += members
        refreshWithDiff()
    }
}
