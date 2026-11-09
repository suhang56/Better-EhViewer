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

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.gallerylist.AutoLoadPolicy
import com.hippo.ehviewer.gallerylist.GalleryListFeatures
import com.hippo.ehviewer.ui.scene.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.GalleryListScene
import java.util.regex.Pattern
import kotlin.math.abs
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * End-to-end checks of the real UI against live E-Hentai (guest): folding,
 * hiding favourites in place, paging, grid badges and the bounded auto-load.
 * Expects an English device locale.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiEndToEndTest {
    @get:Rule
    val watcher = VerifyWatcher()

    private val device get() = AppUi.device

    private fun showText(n: Int) = if (n == 1) "Show 1 other version" else "Show $n other versions"
    private fun hideText(n: Int) = if (n == 1) "Hide 1 other version" else "Hide $n other versions"
    private fun gridBadgeText(n: Int) = if (n == 1) "+1 version" else "+$n versions"

    private inline fun uiTest(name: String, body: (VerifyReport) -> Unit) {
        val report = VerifyReport("UiEndToEndTest.$name")
        if (!onlineChecked) {
            Live.setUpGuest()
            Live.requireOnline(report)
            onlineChecked = true
        }
        val marker = Logcat.mark()
        try {
            body(report)
        } finally {
            val fatal = runCatching { Logcat.fatalSince(marker, AppUi.packageName) }.getOrElse { listOf("logcat read failed: $it") }
            report.check(fatal.isEmpty(), "no FATAL EXCEPTION for the app in logcat" + if (fatal.isEmpty()) "" else ": " + fatal.joinToString(" | "))
            runCatching { device.pressHome() }
        }
        report.assertAll()
    }

    private fun foldPick(report: VerifyReport): Live.FoldPick {
        cachedPick?.let { return it }
        val pick = Live.findFoldableSearch(report)
            ?: report.fail("none of ${Live.FOLD_QUERIES} folds anything on its first page; folding cannot be tested in the UI")
        report.fact("foldable search [${pick.keyword}]: in-test projection puts a group at row ${pick.row}, representative ${pick.representative}, members ${pick.members}")
        cachedPick = pick
        return pick
    }

    private fun logRows(report: VerifyReport, label: String) {
        val rows = AppUi.rows()
        report.fact(
            "$label: ${rows.size} rows, first/last visible ${AppUi.firstVisible()}/${AppUi.lastVisible()}, groups " +
                rows.filter { it.otherVersions > 0 && !it.isOtherVersion }.joinToString { "${it.position}:${it.gid}+${it.otherVersions}${if (it.expanded) "(expanded)" else ""}" },
        )
    }

    // ------------------------------------------------------------------ (1) fold, expand, open, back

    @Test
    fun step1FoldExpandOpenBack() = uiTest("step1FoldExpandOpenBack") { r ->
        AppUi.prepare(hide = false, fold = true, listMode = AppUi.LIST_MODE_LIST)
        val pick = foldPick(r)
        AppUi.launch(pick.url)
        if (!AppUi.waitForRows(r, 5)) r.fail("gallery list of [${pick.keyword}] did not load")
        logRows(r, "after load")
        r.fact("visible texts: ${AppUi.visibleTexts().take(60)}")

        val group = AppUi.rows().firstOrNull { it.otherVersions > 0 && !it.isOtherVersion }
            ?: r.fail("the app shows no folded group although the in-test projection of the same page found ${pick.members}")
        val n = group.otherVersions
        r.fact("app group: row ${group.position} representative ${group.gid} \"${group.title}\" with $n other versions (in-test representative ${pick.representative})")
        AppUi.scrollTo(group.position)
        val card = AppUi.card(group.title) ?: r.fail("card of the representative \"${group.title}\" is not on screen")
        val label = card.findObject(AppUi.res("versions"))
        r.check(label?.text == showText(n), "versions entry reads '${label?.text}' (expected '${showText(n)}')")
        if (label == null) r.fail("no versions entry in the representative's card")
        val rowCountCollapsed = AppUi.rows().size
        label.click()

        r.check(
            AppUi.waitUntil(5_000) { AppUi.rows().firstOrNull { it.gid == group.gid }?.expanded == true },
            "tapping '${showText(n)}' expands the group",
        )
        val expandedRows = AppUi.rows()
        val repIndex = expandedRows.indexOfFirst { it.gid == group.gid }
        val members = expandedRows.drop(repIndex + 1).take(n)
        r.check(members.size == n && members.all { it.isOtherVersion }, "the $n rows after the representative are other versions: ${members.map { "${it.gid}:${it.isOtherVersion}" }}")
        r.check(expandedRows.size == rowCountCollapsed + n, "expanding added exactly $n rows ($rowCountCollapsed -> ${expandedRows.size})")
        r.check(expandedRows.count { it.isOtherVersion } == n, "no other group is expanded")
        r.check(device.wait(Until.hasObject(By.text(hideText(n))), 5_000), "entry now reads '${hideText(n)}'")
        r.check(device.wait(Until.hasObject(By.text(OTHER_VERSION)), 5_000), "'$OTHER_VERSION' rows are on screen")
        r.fact("on-screen '$OTHER_VERSION' labels: ${device.findObjects(By.text(OTHER_VERSION)).size}; member titles: ${members.map { it.title }}")

        // Open one of the other versions.
        val member = members.firstOrNull() ?: r.fail("no member row to open")
        val firstBefore = AppUi.firstVisible()
        var memberCard = AppUi.card(member.title)
        if (memberCard == null) {
            AppUi.swipeUp(0.25f)
            memberCard = AppUi.card(member.title)
        }
        if (memberCard == null) r.fail("card of member ${member.gid} \"${member.title}\" not on screen")
        (memberCard.findObject(AppUi.res("title")) ?: memberCard).click()
        r.check(AppUi.waitUntil(20_000) { AppUi.topScene() == GalleryDetailScene::class.java }, "tapping the member opens GalleryDetailScene (top scene: ${AppUi.topScene()?.simpleName})")
        val titleShown = device.wait(Until.hasObject(AppUi.res("title").text(member.title)), 30_000)
        r.check(titleShown && device.hasObject(AppUi.res("header")), "detail scene shows the member's own title \"${member.title}\" (gid ${member.gid})")
        r.fact("detail scene texts: ${AppUi.visibleTexts().take(20)}")

        device.pressBack()
        r.check(
            AppUi.waitUntil(15_000) { AppUi.topScene() == GalleryListScene::class.java && AppUi.rows().isNotEmpty() },
            "back returns to the gallery list (top scene: ${AppUi.topScene()?.simpleName})",
        )
        Thread.sleep(1500)
        logRows(r, "after back")
        val repAfter = AppUi.rows().firstOrNull { it.gid == group.gid }
        r.check(repAfter?.expanded == true, "group is still expanded after returning from the detail scene (row: $repAfter)")
        val firstAfter = AppUi.firstVisible()
        r.check(abs(firstAfter - firstBefore) <= 3, "scroll position kept across the detail scene: first visible $firstBefore -> $firstAfter")

        // Collapse again.
        if (repAfter != null && repAfter.expanded) {
            AppUi.scrollTo(repAfter.position)
            val hide = device.wait(Until.findObject(By.text(hideText(n))), 5_000)
            if (hide == null) {
                r.check(false, "'${hideText(n)}' entry not found for collapsing")
            } else {
                val before = AppUi.rows().size
                hide.click()
                r.check(
                    AppUi.waitUntil(5_000) { AppUi.rows().firstOrNull { it.gid == group.gid }?.expanded == false },
                    "tapping '${hideText(n)}' collapses the group",
                )
                r.check(AppUi.rows().size == before - n, "collapsing removed $n rows ($before -> ${AppUi.rows().size})")
                r.check(device.wait(Until.hasObject(By.text(showText(n))), 5_000), "entry reads '${showText(n)}' again")
            }
        }
    }

    // ------------------------------------------------------------------ (2) hide a favourite in place

    @Test
    fun step2HideFavoriteInPlace() = uiTest("step2HideFavoriteInPlace") { r ->
        AppUi.prepare(hide = true, fold = false, listMode = AppUi.LIST_MODE_LIST)
        val url = foldPick(r).url
        AppUi.launch(url)
        if (!AppUi.waitForRows(r, 10)) r.fail("gallery list did not load")
        AppUi.scrollTo(3)
        val rows = AppUi.rows()
        val first = AppUi.firstVisible()
        val last = AppUi.lastVisible()
        r.fact("${rows.size} rows, first/last visible $first/$last")
        if (first < 0 || last <= first) r.fail("cannot determine visible rows ($first/$last)")
        val anchor = rows[first]
        val targetPos = (first + 2).coerceAtMost(last)
        val target = rows[targetPos]
        val gi: GalleryInfo = AppUi.galleryAt(targetPos) ?: r.fail("no gallery at $targetPos")
        val cardsBefore = AppUi.cardCount(target.title)
        r.fact("target gid ${target.gid} \"${target.title}\" at row $targetPos ($cardsBefore card(s) on screen); anchor gid ${anchor.gid} at row $first")
        val wasLocal = EhDB.containLocalFavorites(target.gid)
        if (wasLocal) r.fail("target ${target.gid} is already a local favourite; the device state is not clean")
        try {
            // (a) Favourite through the app's router, like the favourite button does.
            EhDB.putLocalFavorites(gi)
            AppUi.onMain { EhApplication.favouriteStatusRouter.modifyFavourites(target.gid, -1) }
            r.check(AppUi.waitUntil(10_000) { AppUi.rows().none { it.gid == target.gid } }, "favourited gallery ${target.gid} disappears from the list")
            r.check(AppUi.waitUntil(5_000) { AppUi.cardCount(target.title) < cardsBefore }, "its title is no longer on screen")
            r.check(AppUi.rows().size == rows.size - 1, "exactly one row removed (${rows.size} -> ${AppUi.rows().size})")
            val firstAfter = AppUi.firstVisible()
            val anchorAfter = AppUi.rows().indexOfFirst { it.gid == anchor.gid }
            r.check(
                firstAfter > 0 && anchorAfter >= firstAfter - 1 && anchorAfter <= AppUi.lastVisible(),
                "list did not jump: first visible $first -> $firstAfter, anchor row $first -> $anchorAfter",
            )

            EhDB.removeLocalFavorites(target.gid)
            AppUi.onMain { EhApplication.favouriteStatusRouter.modifyFavourites(target.gid, -2) }
            r.check(AppUi.waitUntil(10_000) { AppUi.rows().any { it.gid == target.gid } }, "un-favourited gallery comes back (router event)")
            r.check(AppUi.waitUntil(5_000) { AppUi.cardCount(target.title) >= cardsBefore }, "its title is on screen again")
            r.check(AppUi.rows().indexOfFirst { it.gid == target.gid } == targetPos, "it is back at its original row $targetPos")

            // (b) Favourite changed behind the router's back: picked up on onResume.
            EhDB.putLocalFavorites(gi)
            Thread.sleep(1000)
            r.fact("DB-only change: still listed before onResume = ${AppUi.rows().any { it.gid == target.gid }}")
            AppUi.leaveAndReturn()
            r.check(AppUi.waitUntil(10_000) { AppUi.rows().none { it.gid == target.gid } }, "after onResume the DB-only favourite is hidden")
            EhDB.removeLocalFavorites(target.gid)
            AppUi.leaveAndReturn()
            r.check(AppUi.waitUntil(10_000) { AppUi.rows().any { it.gid == target.gid } }, "after onResume the gallery is shown again")
        } finally {
            EhDB.removeLocalFavorites(target.gid)
            GalleryListFeatures.reloadLocalFavorites()
        }
    }

    // ------------------------------------------------------------------ (3) paging by scrolling

    @Test
    fun step3ScrollLoadsMore() = uiTest("step3ScrollLoadsMore") { r ->
        AppUi.prepare(hide = false, fold = true, listMode = AppUi.LIST_MODE_LIST)
        val url = Live.searchUrl(Live.PAGED_QUERIES.first())
        val marker = Logcat.mark()
        AppUi.launch(url)
        if (!AppUi.waitForRows(r, 5)) r.fail("gallery list did not load")
        val initial = AppUi.rows().size
        val sizes = ArrayList<Int>()
        repeat(15) {
            AppUi.swipeUp(0.7f)
            Thread.sleep(1500)
            sizes += AppUi.rows().size
        }
        AppUi.waitUntil(20_000) { AppUi.rows().size > initial }
        val rows = AppUi.rows()
        val requests = Logcat.listRequestsSince(marker)
        r.fact("rows after each swipe: $sizes; final ${rows.size}; list requests: ${requests.size}")
        requests.forEach { r.fact("  request: $it") }
        r.check(rows.size > initial, "scrolling to the end loaded more rows ($initial -> ${rows.size})")
        r.check(requests.size >= 2, "at least one next-page request was made")
        r.check(rows.map { it.gid }.distinct().size == rows.size, "no gallery is shown twice after paging")
        r.check(rows.none { it.isOtherVersion }, "no other-version rows appear without expanding")
        r.check(rows.all { it.state != null }, "every row carries projection state (folding active)")
        r.check(AppUi.topScene() == GalleryListScene::class.java, "still on the gallery list")
    }

    // ------------------------------------------------------------------ (4) grid mode

    @Test
    fun step4GridBadge() = uiTest("step4GridBadge") { r ->
        AppUi.prepare(hide = false, fold = true, listMode = AppUi.LIST_MODE_GRID)
        val pick = foldPick(r)
        AppUi.launch(pick.url)
        if (!AppUi.waitForRows(r, 5)) r.fail("gallery grid did not load")
        logRows(r, "grid")
        val group = AppUi.rows().firstOrNull { it.otherVersions > 0 } ?: r.fail("no folded group in grid mode")
        AppUi.scrollTo(group.position)
        val text = gridBadgeText(group.otherVersions)
        val badge = device.wait(Until.findObject(AppUi.res("versions").text(text)), 10_000)
        r.check(badge != null, "grid badge '$text' is shown")
        r.fact("grid badges on screen: ${device.findObjects(AppUi.res("versions")).map { it.text }}")
        if (badge != null) {
            badge.click()
            r.check(AppUi.waitUntil(5_000) { AppUi.rows().firstOrNull { it.gid == group.gid }?.expanded == true }, "tapping the grid badge expands the group")
            r.check(device.wait(Until.hasObject(AppUi.res("versions").text(OTHER_VERSION)), 5_000), "grid shows '$OTHER_VERSION' badges")
        }
    }

    // ------------------------------------------------------------------ (5) bounded auto-load

    @Test
    fun step5BoundedAutoLoad() = uiTest("step5BoundedAutoLoad") { r ->
        AppUi.prepare(hide = true, fold = false, listMode = AppUi.LIST_MODE_LIST)
        val maxAuto = AutoLoadPolicy.DEFAULT_MAX_AUTO_PAGES
        // Pages 1..(1 + maxAuto): everything the app may load without user action.
        val pageCount = 1 + maxAuto
        var chosen: Pair<String, List<GalleryInfo>>? = null
        for (keyword in Live.PAGED_QUERIES) {
            val all = ArrayList<GalleryInfo>()
            var next: String? = null
            var pages = 0
            var hasMoreAfter = false
            while (pages < pageCount) {
                val result = Live.fetchList(r, Live.searchUrl(keyword, next))
                all += result.galleryInfoList
                pages++
                next = result.next
                hasMoreAfter = next != null
                if (next == null) break
            }
            r.fact("[$keyword]: fetched $pages page(s), ${all.size} galleries, more pages after: $hasMoreAfter")
            if (pages == pageCount && hasMoreAfter) {
                chosen = keyword to all
                break
            }
        }
        val (keyword, galleries) = chosen ?: r.fail("no search in ${Live.PAGED_QUERIES} has more than $pageCount pages")
        val existing = EhDB.localFavoriteGids
        val added = galleries.filter { it.gid !in existing }.distinctBy { it.gid }
        EhDB.putLocalFavorites(added)
        GalleryListFeatures.reloadLocalFavorites()
        r.fact("favourited all ${galleries.size} galleries of the first $pageCount pages of [$keyword] locally (${added.size} new)")
        try {
            val marker = Logcat.mark()
            AppUi.launch(Live.searchUrl(keyword))
            Thread.sleep(25_000)
            val requests = Logcat.listRequestsSince(marker)
            r.fact("list requests within 25 s: ${requests.size}")
            requests.forEach { r.fact("  request: $it") }
            val limit = 1 + maxAuto + 1
            r.check(requests.size <= limit, "automatic loading is bounded: ${requests.size} <= $limit (1 + $maxAuto auto pages + 1 layout pass)")
            r.check(requests.size >= 2, "automatic loading did top up at least once (${requests.size} requests)")
            val tip = AppUi.tipText()
            r.fact("tip text: \"$tip\"; adapter rows: ${AppUi.rows().size}; list shown: ${AppUi.isListShown()}")
            r.check(tip != null && TIP_LOAD_MORE.matcher(tip).matches(), "tip '...hidden or folded. Tap to load more' is shown")
            r.check(AppUi.rows().isEmpty(), "no row is visible (all favourited)")

            Thread.sleep(8_000)
            val idle = Logcat.listRequestsSince(marker)
            r.check(idle.size == requests.size, "no further requests while idle (${requests.size} -> ${idle.size})")

            val tipView = device.findObject(AppUi.res("tip"))
            if (tipView == null) {
                r.check(false, "tip view not found, cannot tap it")
            } else {
                tipView.click()
                AppUi.waitUntil(20_000) { AppUi.rows().isNotEmpty() }
                Thread.sleep(3_000)
                val after = Logcat.listRequestsSince(marker)
                val delta = after.size - idle.size
                val afterTap = after.drop(idle.size)
                afterTap.forEach { r.fact("  request after tap: $it") }
                // The tap loads the next page; if folding/hiding leaves too few rows the list may top up
                // automatically, but only within the budget and never requesting the same page twice.
                r.check(delta in 1..(1 + maxAuto), "tapping the tip made 1..${1 + maxAuto} list requests (made $delta)")
                r.check(afterTap.toSet().size == afterTap.size, "no page was requested twice: $afterTap")
                r.check(AppUi.rows().isNotEmpty(), "the next page (not favourited) is shown: ${AppUi.rows().size} rows")
                if (AppUi.rows().isNotEmpty()) {
                    val leaked = AppUi.rows().filter { row -> galleries.any { it.gid == row.gid } }
                    r.check(leaked.isEmpty(), "no favourited gallery is shown: ${leaked.map { it.gid }}")
                }
            }
        } finally {
            EhDB.removeLocalFavorites(added.map { it.gid }.toLongArray())
            GalleryListFeatures.reloadLocalFavorites()
            r.fact("removed the ${added.size} temporary local favourites")
        }
    }

    // ------------------------------------------------------------------ (6) minimum number of ratings

    private fun clockMs(t: String): Long {
        // "MM-dd HH:mm:ss.SSS"
        val (h, m, rest) = t.substringAfter(' ').split(':')
        val (sec, ms) = rest.split('.')
        return ((h.toLong() * 60 + m.toLong()) * 60 + sec.toLong()) * 1000 + ms.toLong()
    }

    @Test
    fun step6MinRatingCount() = uiTest("step6MinRatingCount") { r ->
        // A threshold no gallery reaches: every gallery whose count is looked up must disappear.
        AppUi.prepare(hide = false, fold = false, listMode = AppUi.LIST_MODE_LIST, minRatingCount = 100_000)
        // The first page as the site serves it (lookups start as soon as the list shows, so the
        // UI rows may already be partly filtered when the test reads them).
        val initial = Live.fetchList(r, Live.searchUrl("artist:shindol$")).galleryInfoList.map { it.gid }
        val marker = Logcat.mark()
        AppUi.launch(Live.searchUrl("artist:shindol$"))
        if (!AppUi.waitForRows(r, 1)) r.fail("gallery list did not load")
        r.fact("first page: ${initial.size} galleries; rows on screen now ${AppUi.rows().size}, first/last visible ${AppUi.firstVisible()}/${AppUi.lastVisible()}")
        Thread.sleep(20_000)
        val lookups = Logcat.ratingLookupsSince(marker)
        r.fact("rating count lookups in ~20 s: ${lookups.size}: ${lookups.map { it.second }}")
        r.check(lookups.isNotEmpty(), "rating counts were looked up")
        val gaps = lookups.zipWithNext { a, b -> clockMs(b.first) - clockMs(a.first) }
        r.fact("gaps between lookups (ms): $gaps")
        r.check(gaps.all { it >= 1000 }, "lookups are spaced at least 1 s apart (min gap ${gaps.minOrNull()} ms)")
        r.check(lookups.size <= 21, "at most ~1 lookup per 1.2 s (${lookups.size} in ~20 s)")
        val gids = lookups.map { it.second }
        r.check(gids.toSet().size == gids.size, "no gallery was looked up twice")
        // (Hidden rows can make the list load another page, whose rows may be looked up too.)
        val everListed = (initial + AppUi.rows().map { it.gid }).toSet()
        r.check(everListed.containsAll(gids), "only galleries of this list were looked up")
        // Only rows near the screen: the first lookup targets must be at the top of the list.
        val firstTargets = gids.take(5)
        r.check(firstTargets.all { initial.indexOf(it) in 0..(AppUi.lastVisible().coerceAtLeast(5) + 4 + gids.size) }, "lookups start with rows on or near the screen: positions ${firstTargets.map { initial.indexOf(it) }}")
        r.check(gids.map { initial.indexOf(it) }.filter { it >= 0 } == gids.map { initial.indexOf(it) }.filter { it >= 0 }.sorted(), "lookups go from the top of the list downwards: ${gids.map { initial.indexOf(it) }}")
        val rowsNow = AppUi.rows().map { it.gid }
        val stillShown = gids.filter { it in rowsNow }
        r.check(stillShown.isEmpty(), "every looked-up gallery (all below 100000 ratings) is hidden: still shown $stillShown")
        val initialLeft = initial.filter { it in rowsNow }
        r.check(initialLeft == initial.filter { it !in gids }, "of the first page exactly the looked-up galleries are gone: ${initial.size} -> ${initialLeft.size} (looked up ${gids.count { it in initial }})")
        val unknown = initial.filter { it !in gids }
        r.check(unknown.all { it in rowsNow }, "galleries not looked up yet stay visible (${unknown.size})")

        // Leaving the list stops lookups.
        device.pressHome()
        Thread.sleep(1500)
        val stopMarker = Logcat.mark()
        Thread.sleep(8_000)
        val afterLeaving = Logcat.ratingLookupsSince(stopMarker)
        r.check(afterLeaving.isEmpty(), "no lookups while the list is not visible (${afterLeaving.size})")

        // Turning the filter off brings every gallery back.
        PreferenceManager.getDefaultSharedPreferences(AppUi.context).edit().putString(Settings.KEY_MIN_RATING_COUNT, "0").commit()
        AppUi.launch(Live.searchUrl("artist:shindol$"))
        AppUi.waitForRows(r, 1)
        r.check(AppUi.waitUntil(15_000) { AppUi.rows().map { it.gid }.containsAll(initial) }, "with the filter off all ${initial.size} galleries of the first page are listed again (${AppUi.rows().size} rows)")
        val offMarker = Logcat.mark()
        Thread.sleep(5_000)
        r.check(Logcat.ratingLookupsSince(offMarker).isEmpty(), "no lookups when the filter is off")
    }

    companion object {
        private const val OTHER_VERSION = "Another version of the same work"
        private val TIP_LOAD_MORE = Pattern.compile("(All \\d+ loaded results are|The only loaded result is) hidden or folded\\. Tap to load more")

        @Volatile
        private var onlineChecked = false

        @Volatile
        private var cachedPick: Live.FoldPick? = null
    }
}
