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
import com.hippo.ehviewer.client.parser.GalleryListParser
import com.hippo.ehviewer.gallerylist.InlineObservation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Takes a real list page, paints favourite markers onto some posted_<gid>
 * elements the way E-Hentai does for signed-in users, and checks that
 * GalleryListParser reports exactly those markers.
 */
@RunWith(AndroidJUnit4::class)
class ParserMarkerTest {
    @get:Rule
    val watcher = VerifyWatcher()

    private val postedTag = Regex("<div\\b[^>]*\\bid=\"posted_(\\d+)\"[^>]*>")
    private val styleAttr = Regex("\\sstyle=\"[^\"]*\"")

    /** Replaces (or adds) the style attribute of the posted_<gid> opening tag. */
    private fun paint(body: String, gid: Long, style: String): String {
        val match = postedTag.findAll(body).first { it.groupValues[1].toLong() == gid }
        val tag = match.value.replace(styleAttr, "")
        val painted = tag.replaceFirst("id=\"posted_$gid\"", "id=\"posted_$gid\" style=\"$style\"")
        return body.replaceRange(match.range, painted)
    }

    @Test
    fun injectedFavoriteMarkersAreParsed() {
        val report = VerifyReport("ParserMarkerTest")
        Live.setUpGuest()
        Live.requireOnline(report)
        val url = Live.searchUrl("artist:shindol$")
        val (code, body) = Live.rawGet(url)
        report.fact("GET $url: HTTP $code, ${body.length} chars")
        if (code != 200 || !body.contains("class=\"itg")) report.fail("no gallery list page: HTTP $code body: ${Live.snippet(body)}")

        val postedGids = postedTag.findAll(body).map { it.groupValues[1].toLong() }.toList()
        report.fact("posted_<gid> elements in raw HTML: ${postedGids.size}; sample opening tag: ${postedTag.find(body)?.value?.let { Live.snippet(it, 300) }}")
        if (postedGids.size < 3) report.fail("need at least 3 posted_ elements, found ${postedGids.size}")

        val baseline = GalleryListParser.parse(body)
        report.fact("unmodified page: ${baseline.galleryInfoList.size} galleries, markers ${baseline.favoriteMarkers.size}, values ${baseline.favoriteMarkers.values.toSet()}")
        report.check(baseline.favoriteMarkers.values.all { it == InlineObservation.SLOT_NONE }, "unmodified guest page: every marker is SLOT_NONE")

        // Slot 1 (red, as on E-Hentai), slot 9 (pink) and an unknown colour.
        val (slot1Gid, slot9Gid, unknownGid) = postedGids.take(3)
        var painted = paint(body, slot1Gid, "border-color:#f00;background-color:rgba(240,0,0,0.1)")
        painted = paint(painted, slot9Gid, "border-color:#e8e;background-color:rgba(224,128,224,0.1)")
        painted = paint(painted, unknownGid, "border-color:#123;background-color:rgba(1,2,3,0.1)")

        val result = GalleryListParser.parse(painted)
        val markers = result.favoriteMarkers
        report.fact("painted page: ${result.galleryInfoList.size} galleries, markers=${markers.size}")
        report.check(markers[slot1Gid] == 1, "gid $slot1Gid painted rgba(240,0,0) -> slot ${markers[slot1Gid]} (expected 1)")
        report.check(markers[slot9Gid] == 9, "gid $slot9Gid painted rgba(224,128,224) -> slot ${markers[slot9Gid]} (expected 9)")
        report.check(
            markers[unknownGid] == InlineObservation.SLOT_UNKNOWN_COLOR,
            "gid $unknownGid painted unknown colour -> ${markers[unknownGid]} (expected ${InlineObservation.SLOT_UNKNOWN_COLOR})",
        )
        val others = markers.filterKeys { it != slot1Gid && it != slot9Gid && it != unknownGid }
        report.check(others.values.all { it == InlineObservation.SLOT_NONE }, "all ${others.size} other galleries are SLOT_NONE: ${others.values.toSet()}")
        report.check(markers.keys == baseline.favoriteMarkers.keys, "same set of galleries has a marker entry before and after painting")
        report.check(
            result.galleryInfoList.first { it.gid == slot1Gid }.favoriteSlot == 1,
            "GalleryInfo.favoriteSlot of $slot1Gid is 1",
        )
        report.assertAll()
    }
}
