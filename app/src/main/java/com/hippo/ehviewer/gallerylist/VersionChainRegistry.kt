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
 * Remembers which galleries are known (from E-Hentai itself) to be different
 * upload versions of the same gallery: `parent_gid` / `first_gid` returned by the
 * gdata API and the "parent" / "newer versions" sections of the detail page.
 *
 * This is the strongest folding signal because it does not rely on titles at all.
 * Memory is bounded: when more than [maxEntries] gids are known the oldest
 * links are forgotten (folding then simply falls back to the title heuristic).
 */
class VersionChainRegistry(private val maxEntries: Int = 20_000) {
    private val parent = LinkedHashMap<Long, Long>(256, 0.75f, true)
    private val newest = HashMap<Long, Long>()

    @Synchronized
    fun link(a: Long, b: Long) {
        if (a <= 0 || b <= 0 || a == b) return
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) {
            // Keep the smallest gid (the first upload) as the root.
            val (root, child) = if (ra < rb) ra to rb else rb to ra
            parent[child] = root
            val n = maxOf(newest[root] ?: root, newest[child] ?: child, a, b)
            newest[root] = n
            newest.remove(child)
        } else {
            newest[ra] = maxOf(newest[ra] ?: ra, a, b)
        }
        trim()
    }

    /** Root (first upload) of the chain [gid] belongs to, or null if nothing is known. */
    @Synchronized
    fun rootOf(gid: Long): Long? {
        if (!parent.containsKey(gid) && !newest.containsKey(gid)) return null
        return find(gid)
    }

    /** Newest known gid of the chain [gid] belongs to. */
    @Synchronized
    fun newestOf(gid: Long): Long? {
        val root = rootOf(gid) ?: return null
        return newest[root] ?: root
    }

    @Synchronized
    fun clear() {
        parent.clear()
        newest.clear()
    }

    private fun find(gid: Long): Long {
        var x = gid
        var guard = 0
        while (true) {
            val p = parent[x] ?: return x
            if (p == x || guard++ > 64) return x
            // Path halving
            val gp = parent[p]
            if (gp != null) parent[x] = gp
            x = p
        }
    }

    private fun trim() {
        while (parent.size > maxEntries) {
            val eldest = parent.keys.iterator().next()
            parent.remove(eldest)
            // Children of a removed node become their own roots: links are lost, never wrong.
        }
    }
}
