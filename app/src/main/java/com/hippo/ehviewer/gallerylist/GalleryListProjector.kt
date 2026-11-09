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
    ) {
        val isIdentity: Boolean get() = !hideFavorites && !foldVersions
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
        val rawIndex: Int,
        val gid: Long,
        val role: Role,
        /** Stable id of the group: smallest gid of its members. */
        val groupKey: Long,
        /** Number of galleries in the group (1 for SINGLE). */
        val groupSize: Int,
        val expanded: Boolean,
        val favoriteState: FavoriteState?,
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
        /** Group key to the gids of all its (visible) versions, only for groups of 2 or more. */
        val groupMembers: Map<Long, List<Long>> = emptyMap(),
    ) {
        val size: Int get() = rows.size
        fun rowToRaw(row: Int): Int = rows.getOrNull(row)?.rawIndex ?: -1
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
        var unknown = 0
        val visibleIndices = ArrayList<Int>(entries.size)
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
                if (state == FavoriteState.UNKNOWN) unknown++
            }
            visibleIndices += i
        }

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
            visibleIndices.forEach { i -> groupOfIndex[i] = g.add(entries[i]!!) }
            val indexOfGid = HashMap<Long, Int>()
            visibleIndices.forEach { i -> indexOfGid[entries[i]!!.gid] = i }
            val emitted = HashSet<Int>()
            visibleIndices.forEach { i ->
                val group = groupOfIndex[i]!!
                if (!emitted.add(group.id)) return@forEach
                val members = group.members
                if (members.size == 1) {
                    val e = members[0]
                    rows += Row(i, e.gid, Role.SINGLE, e.gid, 1, false, states[e.gid])
                    return@forEach
                }
                val latest = members.mapNotNull { m -> newestOf(m.gid)?.takeIf { it == m.gid } }.toSet()
                val sorted = members.sortedWith(VersionQuality.comparator(options.preferredLanguage, latest))
                val groupKey = members.minOf { it.gid }
                groupMembers[groupKey] = members.map { it.gid }
                val expanded = members.any { it.gid in expandedGids }
                val rep = sorted[0]
                // The group is shown where its first member appeared, so rows never jump around.
                rows += Row(i, rep.gid, Role.REPRESENTATIVE, groupKey, members.size, expanded, states[rep.gid])
                if (expanded) {
                    sorted.drop(1).forEach { m ->
                        rows += Row(indexOfGid[m.gid]!!, m.gid, Role.MEMBER, groupKey, members.size, true, states[m.gid])
                    }
                } else {
                    foldedAway += members.size - 1
                }
            }
        }

        // Raw index -> row: a raw item maps to the row representing it, otherwise to the next shown row.
        val map = IntArray(entries.size) { -1 }
        rows.forEachIndexed { r, row ->
            if (map[row.rawIndex] == -1 || row.role != Role.MEMBER) map[row.rawIndex] = r
        }
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
        return Projection(rows, map, hidden, unknown, duplicates, foldedAway, groupMembers)
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
