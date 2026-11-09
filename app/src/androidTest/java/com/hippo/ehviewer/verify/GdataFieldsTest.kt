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
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.gallerylist.GalleryListFeatures
import java.util.TreeSet
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Answers which version-chain fields the live gdata API really returns
 * (parent_gid, first_gid, current_gid, ...) and checks that the app's
 * EhEngine.fillGalleryListByApi links exactly what the JSON says.
 */
@RunWith(AndroidJUnit4::class)
class GdataFieldsTest {
    @get:Rule
    val watcher = VerifyWatcher()

    private val versionKeys = listOf("parent_gid", "parent_key", "first_gid", "first_key", "current_gid", "current_key")

    private val queries = listOf(
        "artist:shindol$",
        "artist:\"kisaragi gunma$\"",
        "\"TSF Monogatari\"",
        "language:english artist:quzilax$",
    )

    @Test
    fun gdataVersionFields() {
        val report = VerifyReport("GdataFieldsTest")
        Live.setUpGuest()
        Live.requireOnline(report)

        val candidates = LinkedHashMap<Long, String>()
        for (keyword in queries) {
            Live.fetchList(report, Live.searchUrl(keyword)).galleryInfoList.forEach { gi ->
                gi.token?.let { candidates.putIfAbsent(gi.gid, it) }
            }
            if (candidates.size >= 75) break
        }
        report.fact("collected ${candidates.size} gid/token pairs from live list pages")
        if (candidates.isEmpty()) report.fail("no galleries found on live list pages")

        val metas = ArrayList<JSONObject>()
        candidates.entries.take(75).chunked(25).forEach { batch ->
            metas += Live.gdata(report, batch.map { it.key to it.value })
        }
        report.fact("gdata returned ${metas.size} gmetadata entries for ${candidates.size.coerceAtMost(75)} requested")
        metas.filter { it.has("error") }.forEach { report.fact("gdata error entry: $it") }

        val keyUnion = TreeSet<String>()
        metas.forEach { m -> m.keys().forEach { keyUnion += it } }
        report.fact("gmetadata key union: $keyUnion")
        versionKeys.forEach { key -> report.fact("key '$key' present in ${metas.count { it.has(key) }} of ${metas.size} entries") }
        val sample = metas.firstOrNull { m -> versionKeys.any { m.has(it) } } ?: metas.firstOrNull()
        sample?.let { m -> report.fact("keys of one entry (gid=${m.optLong("gid")}): ${m.keys().asSequence().toList()}") }
        metas.filter { m -> versionKeys.any { m.has(it) } }.forEach { m ->
            report.fact("gid=${m.optLong("gid")} " + versionKeys.joinToString(" ") { "$it=${if (m.has(it)) m.opt(it) else "<absent>"}" } + " title=\"${m.optString("title")}\"")
        }

        val related = metas.filter { m -> gidOf(m, "parent_gid") != null || gidOf(m, "first_gid") != null }
        report.check(
            related.isNotEmpty(),
            "at least one live gallery has parent_gid/first_gid in gdata (${related.size} of ${metas.size}); otherwise the API version-chain path is unverified",
        )

        // Cross-check one child against the detail pages (parent link and the parent's newer versions).
        related.firstOrNull { gidOf(it, "parent_gid") != null }?.let { child ->
            val gid = child.getLong("gid")
            val parentGid = gidOf(child, "parent_gid")!!
            val gd = Live.fetchDetailOrNull(report, gid, child.getString("token"))
            if (gd != null) {
                val detailParent = gd.parent?.let { GalleryDetailUrlParser.parse(it, false) }?.gid
                report.fact("detail page of $gid: parent=$detailParent newerVersions=${gd.newerVersions.map { it.gid }}")
                report.check(detailParent == parentGid, "detail page parent of $gid ($detailParent) == gdata parent_gid ($parentGid)")
            }
            val parentKey = child.optString("parent_key")
            val pd = if (parentKey.isNotEmpty()) Live.fetchDetailOrNull(report, parentGid, parentKey) else null
            if (pd != null) {
                report.fact("detail page of parent $parentGid: newerVersions=${pd.newerVersions.map { it.gid }}")
                report.check(pd.newerVersions.any { it.gid == gid }, "parent $parentGid lists $gid among its newer versions")
            }
        }

        // The app's own path: fillGalleryListByApi -> GalleryApiParser -> versionChains.
        val chains = GalleryListFeatures.versionChains
        chains.clear()
        val infos: List<GalleryInfo> = candidates.entries.take(75).map { BaseGalleryInfo(gid = it.key, token = it.value) }
        try {
            runBlocking { EhEngine.fillGalleryListByApi(infos, EhUrl.referer) }
        } catch (e: Throwable) {
            report.fail("EhEngine.fillGalleryListByApi failed: ${e.javaClass.name}: ${e.message}", e)
        }
        report.check(infos.count { !it.title.isNullOrEmpty() } == metas.count { !it.has("error") }, "fillGalleryListByApi filled a title for every gmetadata entry")
        for (m in related) {
            val gid = m.getLong("gid")
            gidOf(m, "parent_gid")?.let { parent ->
                report.check(
                    chains.rootOf(gid) != null && chains.rootOf(gid) == chains.rootOf(parent),
                    "versionChains links $gid with parent_gid $parent (roots ${chains.rootOf(gid)} / ${chains.rootOf(parent)})",
                )
            }
            gidOf(m, "first_gid")?.let { first ->
                report.check(
                    chains.rootOf(gid) != null && chains.rootOf(gid) == chains.rootOf(first),
                    "versionChains links $gid with first_gid $first (roots ${chains.rootOf(gid)} / ${chains.rootOf(first)})",
                )
            }
        }
        val unrelated = metas.filter { it !in related && !it.has("error") }.map { it.getLong("gid") }
        report.check(
            unrelated.none { chains.rootOf(it) != null && !isReferenced(related, it) },
            "galleries without parent_gid/first_gid are not linked to anything",
        )
        report.assertAll()
    }

    private fun gidOf(m: JSONObject, key: String): Long? = if (m.has(key) && !m.isNull(key)) m.optString(key).toLongOrNull()?.takeIf { it > 0 } else null

    private fun isReferenced(related: List<JSONObject>, gid: Long) = related.any { gidOf(it, "parent_gid") == gid || gidOf(it, "first_gid") == gid }
}
