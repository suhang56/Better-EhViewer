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
package com.hippo.ehviewer.verify

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.parser.GalleryListParser
import com.hippo.ehviewer.gallerylist.GalleryEntry
import com.hippo.ehviewer.gallerylist.GalleryListFeatures
import com.hippo.ehviewer.gallerylist.GalleryListProjector
import com.hippo.ehviewer.gallerylist.InlineObservation
import com.hippo.ehviewer.gallerylist.TitleParser
import com.hippo.ehviewer.gallerylist.VersionQuality
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parses live search pages with the real parser, projects them with folding on
 * and logs every folded group so a human can judge whether the grouping is right.
 * Assertions are structural only.
 */
@RunWith(AndroidJUnit4::class)
class LiveListParsingTest {
    @get:Rule
    val watcher = VerifyWatcher()

    private val queries = listOf(
        "artist:shindol$",
        "artist:\"kisaragi gunma$\"",
        "artist:quzilax$",
        "group:\"mogiki nora$\"",
        "\"TSF Monogatari\"",
        "parody:\"touhou project$\" \"Reimu\"",
    )

    @Test
    fun foldLiveSearchPages() {
        val report = VerifyReport("LiveListParsingTest")
        Live.setUpGuest()
        Live.requireOnline(report)
        var totalGroups = 0
        for (keyword in queries) {
            val first = Live.fetchList(report, Live.searchUrl(keyword))
            logPage(report, keyword, 1, first)
            val pages = ArrayList<GalleryInfo>(first.galleryInfoList)
            // A second page exercises cross-page folding and de-duplication.
            if (keyword == queries.first() && first.next != null) {
                val second = Live.fetchList(report, Live.searchUrl(keyword, first.next))
                logPage(report, keyword, 2, second)
                pages += second.galleryInfoList
            }
            for (language in listOf("ZH", "EN")) {
                totalGroups += checkProjection(report, "[$keyword] pages=${if (pages.size > first.galleryInfoList.size) 2 else 1} lang=$language", pages, language)
            }
        }
        report.fact("total folded groups over all searches/languages: $totalGroups")
        report.assertAll()
    }

    private fun logPage(report: VerifyReport, keyword: String, page: Int, result: GalleryListParser.Result) {
        val list = result.galleryInfoList
        val markers = result.favoriteMarkers
        val withTags = list.count { !it.simpleTags.isNullOrEmpty() }
        report.fact(
            "[$keyword] page $page: ${list.size} galleries, next=${result.next != null}, tags present on $withTags, " +
                "favoriteMarkers.size=${markers.size} (posted_ element found), marker values=${markers.values.groupingBy { it }.eachCount()}",
        )
        report.check(
            markers.values.all { it == InlineObservation.SLOT_NONE },
            "[$keyword] page $page: guest page has no favourite markers (all SLOT_NONE)",
        )
        report.fact("[$keyword] page $page: posted_<gid> element found for ${list.count { it.gid in markers }} of ${list.size} parsed galleries")
    }

    /** Returns the number of folded groups. */
    private fun checkProjection(report: VerifyReport, label: String, list: List<GalleryInfo>, language: String): Int {
        val entries = list.map(GalleryListFeatures::toEntry)
        val byGid = entries.associateBy { it.gid }
        val projection = try {
            Live.projectFolded(list, language)
        } catch (e: Throwable) {
            report.fail("$label: projection crashed: $e", e)
        }
        val rows = projection.rows
        val rowGids = rows.map { it.gid }
        val folded = projection.groupMembers.flatMap { (key, members) ->
            val rep = rows.first { it.role != GalleryListProjector.Role.MEMBER && it.groupKey == key }.gid
            members.filter { it != rep }
        }
        val shown = rowGids + folded
        val distinctRaw = entries.map { it.gid }.distinct()
        report.check(
            shown.size == distinctRaw.size && shown.toSet() == distinctRaw.toSet(),
            "$label: every raw gid appears exactly once across rows + folded members (rows=${rows.size}, folded=${folded.size}, raw distinct=${distinctRaw.size}, raw=${entries.size}, duplicates=${projection.duplicates})",
        )
        report.check(rowGids.size == rowGids.toSet().size, "$label: no gid is shown in two rows")

        projection.groupMembers.forEach { (key, members) ->
            val repRow = rows.first { it.role != GalleryListProjector.Role.MEMBER && it.groupKey == key }
            report.check(repRow.gid in members, "$label: representative ${repRow.gid} of group $key is one of its members")
            report.check(repRow.groupSize == members.size, "$label: group $key size ${repRow.groupSize} == members ${members.size}")
            val artistSets = members.mapNotNull { byGid[it] }.map { creators(it) }.filter { it.isNotEmpty() }
            val conflict = artistSets.withIndex().any { (i, a) -> artistSets.drop(i + 1).any { b -> a.intersect(b).isEmpty() } }
            report.check(!conflict, "$label: group $key does not mix disjoint non-empty artist:/group: tag sets $artistSets")
            report.fact("$label: GROUP key=$key size=${members.size} representative=${repRow.gid}")
            members.forEach { gid -> byGid[gid]?.let { report.fact("$label:   ${describe(it, gid == repRow.gid)}") } }
        }

        // Fully expanded, every visible gallery has its own row again.
        val expanded = Live.projectFolded(list, language, expandAll = true)
        report.check(
            expanded.rows.size == distinctRaw.size,
            "$label: fully expanded projection shows every distinct gallery (${expanded.rows.size}/${distinctRaw.size})",
        )
        val memberRowsOk = expanded.rows.withIndex().all { (i, row) ->
            row.role != GalleryListProjector.Role.MEMBER ||
                (i > 0 && expanded.rows.subList(0, i).lastOrNull { it.role != GalleryListProjector.Role.MEMBER }?.groupKey == row.groupKey)
        }
        report.check(memberRowsOk, "$label: expanded member rows directly follow their own group")
        return projection.groupMembers.size
    }

    private fun creators(e: GalleryEntry): Set<String> = e.tags.orEmpty().filter { it.startsWith("artist:") || it.startsWith("group:") }.toSet()

    private fun describe(e: GalleryEntry, isRep: Boolean): String {
        val parsed = TitleParser.parse(e.title)
        val tags = e.tags.orEmpty().filter { it.startsWith("artist:") || it.startsWith("group:") || it.startsWith("language:") }
        return "${if (isRep) "*REP*" else "member"} gid=${e.gid} cat=${e.category} pages=${e.pages} rating=${e.rating} " +
            "lang=${VersionQuality.languageOf(e)} title=\"${e.title}\" titleJpn=\"${e.titleJpn}\" core=\"${parsed.core}\" creators=${parsed.creators} tags=$tags"
    }
}
