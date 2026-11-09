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

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Groups galleries that are different upload / language / translation versions
 * of the same work.
 *
 * Signals, strongest first:
 *  1. A version chain reported by E-Hentai ([chainRootOf]).
 *  2. Same normalized core title AND a shared creator (artist / group tag, or
 *     the `[Circle (Artist)]` block of the title). Romanized and Japanese titles
 *     are both used, so a translation (whose `title_jpn` is the original title)
 *     joins its original.
 *
 * A title match alone is never enough; a group is only joined when every member
 * passes [isCompatible] (category, artist tags, page count).
 */
class VersionGrouper(
    private val chainRootOf: (Long) -> Long? = { null },
) {
    class Group(val id: Int) {
        val members = ArrayList<GalleryEntry>(2)
    }

    private val groups = ArrayList<Group>()
    private val gidToGroup = HashMap<Long, Group>()
    private val keyToGroups = HashMap<String, MutableList<Group>>()
    private val chainToGroup = HashMap<Long, Group>()
    private val profiles = HashMap<Long, Profile>()

    val allGroups: List<Group> get() = groups

    fun groupOf(gid: Long): Group? = gidToGroup[gid]

    /** Adds [entry] and returns its group. Adding the same gid twice returns the existing group. */
    fun add(entry: GalleryEntry): Group {
        gidToGroup[entry.gid]?.let { return it }
        val profile = profileOf(entry)
        val chainRoot = chainRootOf(entry.gid)

        var target: Group? = null
        if (chainRoot != null) {
            target = chainToGroup[chainRoot]
        }
        if (target == null) {
            val candidates = LinkedHashSet<Group>()
            profile.keys.forEach { key -> keyToGroups[key]?.let { candidates += it } }
            target = candidates.sortedBy { it.id }.firstOrNull { group ->
                group.members.all { member -> isCompatible(profile, profileOf(member)) }
            }
        }
        val group = target ?: Group(groups.size).also { groups += it }
        group.members += entry
        gidToGroup[entry.gid] = group
        profile.keys.forEach { key ->
            val list = keyToGroups.getOrPut(key) { ArrayList(1) }
            if (group !in list) list += group
        }
        if (chainRoot != null) chainToGroup.putIfAbsent(chainRoot, group)
        return group
    }

    private class Profile(
        val entry: GalleryEntry,
        val keys: Set<String>,
        val artistTags: Set<String>,
        val groupTags: Set<String>,
        val parodyTags: Set<String>,
        val events: Set<String>,
        val chainRoot: Long?,
    )

    private fun profileOf(entry: GalleryEntry): Profile = profiles.getOrPut(entry.gid) { buildProfile(entry) }

    private fun buildProfile(entry: GalleryEntry): Profile {
        val tags = entry.tags?.map { it.lowercase(Locale.ROOT) }.orEmpty()
        fun tagValues(namespace: String) = tags.filter { it.startsWith("$namespace:") }
            .map { TitleParser.squash(TitleParser.normalize(it.substringAfter(':'))) }
            .filter { it.isNotEmpty() }
            .toSet()
        val artistTags = tagValues("artist")
        val groupTags = tagValues("group")
        val parodyTags = tagValues("parody") - "original"
        val keys = LinkedHashSet<String>()
        val events = LinkedHashSet<String>()
        listOf(entry.title, entry.titleJpn).forEach { title ->
            val parsed = TitleParser.parse(title)
            events += parsed.events
            if (!parsed.isUsable) return@forEach
            val creators = parsed.creators + artistTags + groupTags
            creators.forEach { creator -> keys += "$creator\u0000${parsed.core}" }
        }
        return Profile(entry, keys, artistTags, groupTags, parodyTags, events, chainRootOf(entry.gid))
    }

    private fun isCompatible(a: Profile, b: Profile): Boolean {
        if (a.chainRoot != null && a.chainRoot == b.chainRoot) return true
        val ea = a.entry
        val eb = b.entry
        if (ea.gid == eb.gid) return true
        if (isKnownCategory(ea.category) && isKnownCategory(eb.category) && ea.category != eb.category) return false
        // When both sides have creator tags they must overlap.
        val creatorsA = a.artistTags + a.groupTags
        val creatorsB = b.artistTags + b.groupTags
        if (creatorsA.isNotEmpty() && creatorsB.isNotEmpty() && creatorsA.intersect(creatorsB).isEmpty()) return false
        if (a.parodyTags.isNotEmpty() && b.parodyTags.isNotEmpty() && a.parodyTags.intersect(b.parodyTags).isEmpty()) return false
        // Books released at different events ("(C95)" vs "(C96)") are different works, even with the same title.
        if (a.events.isNotEmpty() && b.events.isNotEmpty() && a.events.intersect(b.events).isEmpty()) return false
        // Very different page counts usually mean a different work (chapter vs. complete edition).
        if (ea.pages > 0 && eb.pages > 0) {
            val hi = max(ea.pages, eb.pages)
            val lo = min(ea.pages, eb.pages)
            if (hi > lo * 3 / 2 && abs(hi - lo) > 8) return false
        }
        return true
    }

    private fun isKnownCategory(category: Int): Boolean = category > 0 && category != GalleryEntry.UNKNOWN_CATEGORY && category != 0x400
}
