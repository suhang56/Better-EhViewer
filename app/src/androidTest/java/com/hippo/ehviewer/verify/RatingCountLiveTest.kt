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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "minimum number of ratings" filter reads the count from detail pages with a regex
 * (EhEngine.getRatingCount). Compare it with the app's full detail page parser on live pages.
 */
@RunWith(AndroidJUnit4::class)
class RatingCountLiveTest {
    @get:Rule
    val watcher = VerifyWatcher()

    @Test
    fun lookupMatchesDetailPage() {
        val r = VerifyReport("RatingCountLiveTest")
        Live.setUpGuest()
        Live.requireOnline(r)
        val list = Live.fetchList(r, Live.searchUrl("artist:shindol$")).galleryInfoList
        r.check(list.size >= 4, "live list has galleries (${list.size})")
        list.take(4).forEach { gi ->
            Thread.sleep(1500)
            val looked = runCatching { runBlocking { EhEngine.getRatingCount(gi.gid, gi.token) } }
            Thread.sleep(1500)
            val detail = Live.fetchDetailOrNull(r, gi.gid, gi.token!!)
            r.fact("gid ${gi.gid}: lookup=${looked.getOrNull()} (${looked.exceptionOrNull()?.javaClass?.simpleName ?: "ok"}), detail page ratingCount=${detail?.ratingCount}")
            r.check(looked.isSuccess && looked.getOrNull() != null, "rating count lookup of ${gi.gid} returned a number")
            if (detail != null) r.check(looked.getOrNull() == detail.ratingCount, "lookup of ${gi.gid} equals the detail page parser (${looked.getOrNull()} vs ${detail.ratingCount})")
        }
        r.assertAll()
    }
}
