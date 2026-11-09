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

import android.os.SystemClock
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhRequestBuilder
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.parser.GalleryListParser
import com.hippo.ehviewer.gallerylist.FavoriteState
import com.hippo.ehviewer.gallerylist.GalleryListFeatures
import com.hippo.ehviewer.gallerylist.GalleryListProjector
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Access to LIVE E-Hentai (guest, e-hentai.org) through the app's own network
 * stack (EhEngine / EhApplication.okHttpClient / EhRequestBuilder headers).
 *
 * Every failure is turned into an AssertionError that carries the HTTP status
 * and a body snippet, so a ban page or a timeout can never look like a pass.
 */
object Live {
    /** Searches likely to contain the same work several times (translations, re-uploads). */
    val FOLD_QUERIES = listOf(
        "\"TSF Monogatari\"",
        "artist:shindol$",
        "artist:\"kisaragi gunma$\"",
        "artist:quzilax$",
        "artist:\"shiwasu no okina$\"",
        "artist:\"mizuryu kei$\"",
        "parody:\"blue archive$\" \"Hoshino\"",
    )

    /** Low-traffic searches with at least 5 result pages (uploads rarely shift their pages). */
    val PAGED_QUERIES = listOf(
        "artist:shindol$",
        "artist:\"kisaragi gunma$\"",
        "artist:\"shiwasu no okina$\"",
        "artist:quzilax$",
    )

    private const val MIN_REQUEST_INTERVAL_MS = 1500L
    private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
    private val IMAGE_URL = Regex("https?://\\S+?\\.(?:jpe?g|png|gif|webp)\\S*", RegexOption.IGNORE_CASE)
    private var lastRequestAt = 0L

    /** Guest on e-hentai.org, no sign-in / site-selection scene. */
    fun setUpGuest() {
        Settings.putNeedSignIn(false)
        Settings.putSelectSite(false)
        Settings.putGallerySite(EhUrl.SITE_E)
    }

    @Synchronized
    private fun throttle() {
        val wait = lastRequestAt + MIN_REQUEST_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = SystemClock.elapsedRealtime()
    }

    /** Body excerpt for the report: whitespace collapsed, image URLs removed. */
    fun snippet(body: String, max: Int = 500): String = body.replace(IMAGE_URL, "<image-url>").replace(Regex("\\s+"), " ").trim().take(max)

    fun searchUrl(keyword: String, next: String? = null): String = ListUrlBuilder().apply {
        this.keyword = keyword
        setIndex(next, true)
    }.build()

    /** Raw GET with the app's client and headers. Returns (HTTP status, body); status -1 on exceptions. */
    fun rawGet(url: String): Pair<Int, String> {
        throttle()
        return try {
            val request = EhRequestBuilder(url, EhUrl.referer).build()
            EhApplication.okHttpClient.newCall(request).execute().use { it.code to it.body.string() }
        } catch (e: Throwable) {
            -1 to "${e.javaClass.name}: ${e.message}"
        }
    }

    /** Fails the test loudly unless the e-hentai.org front page is a normal gallery list. */
    fun requireOnline(report: VerifyReport) {
        val (code, body) = rawGet(EhUrl.host)
        report.fact("preflight GET ${EhUrl.host}: HTTP $code, ${body.length} chars")
        if (code != 200 || !body.contains("class=\"itg")) {
            report.fail("E-Hentai is not reachable as a guest from this device: HTTP $code, body: ${snippet(body)}")
        }
    }

    fun fetchList(report: VerifyReport, url: String): GalleryListParser.Result {
        throttle()
        return try {
            runBlocking { EhEngine.getGalleryList(url) }
        } catch (e: Throwable) {
            val (code, body) = rawGet(url)
            report.fail("list request failed for $url: ${e.javaClass.name}: ${e.message}; raw re-fetch HTTP $code, body: ${snippet(body)}", e)
        }
    }

    /** Gallery detail through EhEngine; null (with the reason in the report) if it can't be loaded or parsed. */
    fun fetchDetailOrNull(report: VerifyReport, gid: Long, token: String): GalleryDetail? {
        val url = EhUrl.getGalleryDetailUrl(gid, token)
        throttle()
        return try {
            runBlocking { EhEngine.getGalleryDetail(url) }
        } catch (e: Throwable) {
            val (code, body) = rawGet(url)
            report.fact("detail request failed for $url: ${e.javaClass.name}: ${e.message}; raw re-fetch HTTP $code, body: ${snippet(body)}")
            null
        }
    }

    /** Raw gdata API call (method gdata, namespace 1). Returns the gmetadata objects. */
    fun gdata(report: VerifyReport, gidTokens: List<Pair<Long, String>>): List<JSONObject> {
        val json = JSONObject()
        json.put("method", "gdata")
        json.put("gidlist", JSONArray(gidTokens.map { (gid, token) -> JSONArray().put(gid).put(token) }))
        json.put("namespace", 1)
        throttle()
        val (code, body) = try {
            val request = EhRequestBuilder(EhUrl.apiUrl, EhUrl.referer, EhUrl.origin)
                .post(json.toString().toRequestBody(MEDIA_TYPE_JSON))
                .build()
            EhApplication.okHttpClient.newCall(request).execute().use { it.code to it.body.string() }
        } catch (e: Throwable) {
            report.fail("gdata request to ${EhUrl.apiUrl} failed: ${e.javaClass.name}: ${e.message}", e)
        }
        val array = try {
            JSONObject(body).getJSONArray("gmetadata")
        } catch (e: Throwable) {
            report.fail("gdata response is not JSON with gmetadata: HTTP $code, body: ${snippet(body)}", e)
        }
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    /** Projects [list] like the app does with folding on (nothing hidden). */
    fun projectFolded(
        list: List<GalleryInfo>,
        preferredLanguage: String?,
        expandAll: Boolean = false,
    ): GalleryListProjector.Projection = GalleryListProjector.project(
        entries = list.map(GalleryListFeatures::toEntry),
        options = GalleryListProjector.Options(hideFavorites = false, foldVersions = true, preferredLanguage = preferredLanguage),
        favoriteState = { FavoriteState.NOT_FAVORITED },
        chainRootOf = GalleryListFeatures.versionChains::rootOf,
        newestOf = GalleryListFeatures.versionChains::newestOf,
        expandedGids = if (expandAll) list.map { it.gid }.toSet() else emptySet(),
    )

    data class FoldPick(val keyword: String, val url: String, val row: Int, val representative: Long, val members: List<Long>)

    /** First search of [FOLD_QUERIES] whose first page folds at least two galleries together. */
    fun findFoldableSearch(report: VerifyReport): FoldPick? {
        val preferred = GalleryListFeatures.options().preferredLanguage
        for (keyword in FOLD_QUERIES) {
            val url = searchUrl(keyword)
            val result = fetchList(report, url)
            val projection = projectFolded(result.galleryInfoList, preferred)
            val row = projection.rows.indexOfFirst { it.role == GalleryListProjector.Role.REPRESENTATIVE }
            report.fact("fold candidate [$keyword]: ${result.galleryInfoList.size} galleries, ${projection.groupMembers.size} groups, first group at row $row")
            if (row >= 0) {
                val r = projection.rows[row]
                return FoldPick(keyword, url, row, r.gid, projection.groupMembers[r.groupKey].orEmpty())
            }
        }
        return null
    }
}
