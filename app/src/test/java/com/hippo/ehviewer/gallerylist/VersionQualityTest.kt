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

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionQualityTest {
    private val jp = entry(1, "[作者] タイトル", rating = 4.5f)
    private val zh = entry(2, "[Artist] Title [Chinese]", titleJpn = "[作者] タイトル [中国翻訳]", lang = "ZH", rating = 4f)
    private val zhMachine = entry(3, "[Artist] Title [Chinese] [机翻]", titleJpn = "[作者] タイトル [中国翻訳]", lang = "ZH", rating = 4.8f)
    private val en = entry(4, "[Artist] Title [English]", lang = "EN", rating = 3f)

    private fun best(list: List<GalleryEntry>, lang: String?, latest: Set<Long> = emptySet()) = list.sortedWith(VersionQuality.comparator(lang, latest)).first().gid

    @Test
    fun preferredLanguageWins() {
        val all = listOf(jp, zh, zhMachine, en)
        assertEquals(2L, best(all, "ZH"))
        assertEquals(4L, best(all, "EN"))
        assertEquals(1L, best(all, "JA"))
    }

    @Test
    fun machineTranslationAndIncompleteArePenalized() {
        val incomplete = entry(5, "[Artist] Title [Chinese]", lang = "ZH", tags = listOf("language:chinese", "other:incomplete"), rating = 5f)
        assertEquals(2L, best(listOf(zhMachine, zh, incomplete), "ZH"))
    }

    @Test
    fun languageFromTagsOverridesTitle() {
        val e = entry(6, "[Artist] Title", tags = listOf("language:korean", "language:translated"))
        assertEquals("KO", VersionQuality.languageOf(e))
        assertEquals(VersionQuality.LANG_ORIGINAL, VersionQuality.languageOf(jp))
    }

    @Test
    fun newestUploadBreaksTiesAndLatestVersionPreferred() {
        val a = entry(10, "[Artist] Title", rating = 4f)
        val b = entry(11, "[Artist] Title", rating = 4f)
        assertEquals(11L, best(listOf(a, b), null))
        assertEquals(10L, best(listOf(a, b), null, latest = setOf(10L)))
    }
}
