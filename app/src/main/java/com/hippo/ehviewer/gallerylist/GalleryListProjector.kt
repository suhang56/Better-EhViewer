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
 * Turns the raw, page-ordered data of a gallery list into the rows that are
 * actually displayed: favourites hidden, versions folded, page duplicates removed.
 *
 * The raw data is never modified, so pagination bookkeeping keeps working on
 * raw positions; [Projection] maps between raw positions and adapter rows.
 */
object GalleryListProjector {
    data class Options(
        val hideFavorites: Boolean,
        val foldVersions: Boolean,
        /** Simple language code to prefer as group representative, null for none. */
        val preferredLanguage: String?,
        /** Hide galleries KNOWN to have fewer ratings than this; 0 = off. Unknown counts stay visible. */
        val minRatingCount: Int = 0,
        /** Hide galleries KNOWN to have been favourited fewer times than this; 0 = off. */
        val minFavoriteCount: Int = 0,
        /** Order rows by number of favourites (loaded results only); unknown counts go last. */
        val sortByFavorites: Boolean = false,
    ) {
        /** Rating / favourite counts from detail pages are needed. */
        val needsGalleryStats: Boolean get() = minRatingCount > 0 || minFavoriteCount > 0 || sortByFavorites
        val isIdentity: Boolean get() = !hideFavorites && !foldVersions && !needsGalleryStats
    }

    enum class Role {
        /** Gallery that is not folded with anything. */
        SINGLE,

        /** Representative of a group of versions. */
        REPRESENTATIVE,

        /** Other version, only shown while its group is expanded. */
        MEMBER,
    }

    data class Row(
        /** Raw index of the gallery this row DISPLAYS (and opens). */
        val rawIndex: Int,
        val gid: Long,
        val role: Role,
        /** Stable id of the group: smallest gid of its members. */
        val groupKey: Long,
        /** Number of galleries in the group (1 for SINGLE). */
        val groupSize: Int,
        val expanded: Boolean,
        val favoriteState: FavoriteState?,
        /**
         * Raw index used as this row's position in the paged data: where the group first
         * appeared. Used for "which page am I on" / quick-search progress, never for display.
         */
        val anchorIndex: Int = rawIndex,
    ) {
        /** Identity used for DiffUtil. A group row keeps its identity when its representative changes. */
        val stableId: String get() = if (role == Role.MEMBER) "m$gid" else "g$groupKey"
    }

    class Projection(
        val rows: List<Row>,
        /** For each raw index, the row that shows it (or the closest following row). */
        private val rawToRow: IntArray,
        val hiddenFavorites: Int,
        val unknownFavorites: Int,
        val duplicates: Int,
        val foldedAway: Int,
        /** Galleries hidden because a known rating / favourite count is below its minimum. */
        val hiddenLowRatingCount: Int = 0,
        /** Group key to the gids of all its (visible) versions, only for groups of 2 or more. */
        val groupMembers: Map<Long, List<Long>> = emptyMap(),
    ) {
        val size: Int get() = rows.size

        /** Raw index of the gallery displayed at [row], -1 if out of range. */
        fun rowToRaw(row: Int): Int = rows.getOrNull(row)?.rawIndex ?: -1

        /** Raw index anchoring [row] in the paged data (first appearance of its group), -1 if out of range. */
        fun rowToAnchor(row: Int): Int = rows.getOrNull(row)?.anchorIndex ?: -1
        fun rawToRow(raw: Int): Int = when {
            rows.isEmpty() -> 0
            raw < 0 -> 0
            raw >= rawToRow.size -> rows.size - 1
            else -> rawToRow[raw]
        }
        fun rowOfGroup(groupKey: Long): Int = rows.indexOfFirst { it.role != Role.MEMBER && it.groupKey == groupKey }
    }

    /**
     * @param entries raw data, in page order; nulls are skipped
     * @param favoriteState resolver; only consulted when hiding is enabled
     * @param expandedGids gids belonging to groups the user expanded
     */
    fun project(
        entries: List<GalleryEntry?>,
        options: Options,
        favoriteState: (Long) -> FavoriteState,
        chainRootOf: (Long) -> Long? = { null },
        newestOf: (Long) -> Long? = { null },
        expandedGids: Set<Long> = emptySet(),
        ratingCountOf: (Long) -> Int? = { null },
        favoriteCountOf: (Long) -> Int? = { null },
        /** Sort key (number of favourites) per gid, null = not known yet. Only used with [Options.sortByFavorites]. */
        sortKeyOf: (Long) -> Int? = { null },
    ): Projection {
        if (options.isIdentity) {
            val rows = ArrayList<Row>(entries.size)
            val map = IntArray(entries.size)
            entries.forEachIndexed { i, e ->
                if (e != null) rows += Row(i, e.gid, Role.SINGLE, e.gid, 1, false, null)
            }
            fixRawMap(map, entries, rows)
            return Projection(rows, map, 0, 0, 0, 0)
        }

        val seen = HashSet<Long>()
        var duplicates = 0
        var hidden = 0
        // Candidates: not duplicated, not favourited. Low-rated galleries stay candidates so that a
        // folded group keeps its place (anchor) and identity when one of its versions is hidden.
        val candidates = ArrayList<Int>(entries.size)
        val lowRatedGids = HashSet<Long>()
        val states = HashMap<Long, FavoriteState>()
        entries.forEachIndexed { i, e ->
            if (e == null) return@forEachIndexed
            if (!seen.add(e.gid)) {
                duplicates++
                return@forEachIndexed
            }
            if (options.hideFavorites) {
                val state = favoriteState(e.gid)
                states[e.gid] = state
                if (state.isFavorited) {
                    hidden++
                    return@forEachIndexed
                }
            }
            if (options.minRatingCount > 0) {
                val count = ratingCountOf(e.gid)
                if (count != null && count < options.minRatingCount) lowRatedGids += e.gid
            }
            if (options.minFavoriteCount > 0) {
                val count = favoriteCountOf(e.gid)
                if (count != null && count < options.minFavoriteCount) lowRatedGids += e.gid
            }
            candidates += i
        }
        val lowRated = lowRatedGids.size
        val visibleIndices = candidates.filter { entries[it]!!.gid !in lowRatedGids }
        // Unknown favourite state only matters for galleries that are actually listed.
        val unknown = visibleIndices.count { states[entries[it]!!.gid] == FavoriteState.UNKNOWN }

        val rows = ArrayList<Row>(visibleIndices.size)
        val groupMembers = HashMap<Long, List<Long>>()
        var foldedAway = 0
        var grouper: VersionGrouper? = null
        if (!options.foldVersions) {
            visibleIndices.forEach { i ->
                val e = entries[i]!!
                rows += Row(i, e.gid, Role.SINGLE, e.gid, 1, false, states[e.gid])
            }
        } else {
            val g = VersionGrouper(chainRootOf)
            grouper = g
            val groupOfIndex = HashMap<Int, VersionGrouper.Group>()
            candidates.forEach { i -> groupOfIndex[i] = g.add(entries[i]!!) }
            val indexOfGid = HashMap<Long, Int>()
            candidates.forEach { i -> indexOfGid[entries[i]!!.gid] = i }
            val emitted = HashSet<Int>()
            candidates.forEach { i ->
                val group = groupOfIndex[i]!!
                if (!emitted.add(group.id)) return@forEach
                // Identity and place come from ALL versions; display only from the remaining ones.
                val groupKey = group.members.minOf { it.gid }
                val members = group.members.filter { it.gid !in lowRatedGids }
                if (members.isEmpty()) return@forEach
                if (members.size == 1) {
                    val e = members[0]
                    rows += Row(indexOfGid[e.gid]!!, e.gid, Role.SINGLE, groupKey, 1, false, states[e.gid], anchorIndex = i)
                    return@forEach
                }
                val latest = members.mapNotNull { m -> newestOf(m.gid)?.takeIf { it == m.gid } }.toSet()
                val sorted = members.sortedWith(VersionQuality.comparator(options.preferredLanguage, latest))
                groupMembers[groupKey] = members.map { it.gid }
                val expanded = members.any { it.gid in expandedGids }
                val rep = sorted[0]
                // The group is shown where its first member appeared (anchor i), so rows never jump
                // around, but the row displays and opens the chosen representative.
                rows += Row(indexOfGid[rep.gid]!!, rep.gid, Role.REPRESENTATIVE, groupKey, members.size, expanded, states[rep.gid], anchorIndex = i)
                if (expanded) {
                    sorted.drop(1).forEach { m ->
                        rows += Row(indexOfGid[m.gid]!!, m.gid, Role.MEMBER, groupKey, members.size, true, states[m.gid], anchorIndex = i)
                    }
                } else {
                    foldedAway += members.size - 1
                }
            }
        }

        if (options.sortByFavorites) sortBlocks(rows, groupMembers, sortKeyOf)

        // Raw index -> row: a raw item maps to the row representing it, otherwise to the next shown row.
        val map = IntArray(entries.size) { -1 }
        rows.forEachIndexed { r, row -> map[row.rawIndex] = r }
        val finalGrouper = grouper
        if (finalGrouper != null) {
            // Collapsed members map to their group row.
            val rowOfGroup = HashMap<Long, Int>()
            rows.forEachIndexed { r, row -> if (row.role != Role.MEMBER) rowOfGroup[row.groupKey] = r }
            visibleIndices.forEach { i ->
                if (map[i] == -1) {
                    val members = finalGrouper.groupOf(entries[i]!!.gid)?.members ?: return@forEach
                    map[i] = rowOfGroup[members.minOf { it.gid }] ?: -1
                }
            }
        }
        fillForward(map, rows.size)
        return Projection(rows, map, hidden, unknown, duplicates, foldedAway, lowRated, groupMembers)
    }

    /**
     * Sorts top-level rows (with the expanded versions that follow them) by [sortKeyOf] descending.
     * A folded group uses the highest key among its versions, so a change of representative never
     * moves an already sorted group. Rows without a key keep their original order after all known
     * ones. Stable.
     */
    private fun sortBlocks(rows: MutableList<Row>, groupMembers: Map<Long, List<Long>>, sortKeyOf: (Long) -> Int?) {
        val blocks = ArrayList<List<Row>>()
        var current = ArrayList<Row>()
        rows.forEach { row ->
            if (row.role != Role.MEMBER && current.isNotEmpty()) {
                blocks += current
                current = ArrayList()
            }
            current += row
        }
        if (current.isNotEmpty()) blocks += current
        val keyed = blocks.map { block ->
            val head = block[0]
            val gids = groupMembers[head.groupKey] ?: listOf(head.gid)
            block to (gids + head.gid).mapNotNull(sortKeyOf).maxOrNull()
        }
        val sorted = keyed.filter { it.second != null }.sortedByDescending { it.second!! } + keyed.filter { it.second == null }
        rows.clear()
        sorted.forEach { rows += it.first }
    }

    private fun fixRawMap(map: IntArray, entries: List<GalleryEntry?>, rows: List<Row>) {
        // Identity with possible null entries: map each raw index to the next non-null row.
        var row = 0
        for (i in entries.indices) {
            while (row < rows.size && rows[row].rawIndex < i) row++
            map[i] = row.coerceAtMost((rows.size - 1).coerceAtLeast(0))
        }
    }

    private fun fillForward(map: IntArray, rowCount: Int) {
        var next = (rowCount - 1).coerceAtLeast(0)
        for (i in map.indices.reversed()) {
            if (map[i] == -1) map[i] = next else next = map[i]
        }
    }
}

/**
 * When to (re)build the favourites sort order, so that rows never jump while counts arrive:
 * - the first order may use any known count, even an old one (a revisited list is sorted at once);
 * - after that the order is replaced only when no listed group still waits for a (fresh) count;
 * - galleries whose lookup cannot happen now (failed, throttled) do not hold the order back:
 *   they are sorted last until their count is known.
 */
object SortSnapshotPolicy {
    /**
     * @param gid displayed gallery of a top-level row (representative or single)
     * @param count its known favourite count, or null
     * @param fresh the count is known and recent enough
     * @param blocked no lookup is possible for it right now
     */
    data class Head(val gid: Long, val count: Int?, val fresh: Boolean, val blocked: Boolean)

    /** @param snapshot the new order (null = keep the current one); [pending] = rows still to count */
    data class Decision(val snapshot: Map<Long, Int>?, val pending: Int)

    fun decide(heads: List<Head>, otherKnownCounts: Map<Long, Int>, current: Map<Long, Int>): Decision {
        val pending = heads.count { !it.fresh && !it.blocked }
        val counts = HashMap(otherKnownCounts)
        heads.forEach { h -> h.count?.let { counts[h.gid] = it } }
        val ready = pending == 0 || (current.isEmpty() && heads.all { it.count != null || it.blocked })
        return Decision(if (ready && counts != current) counts else null, pending)
    }
}
