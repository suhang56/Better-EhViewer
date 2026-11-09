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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class VersionGrouperTest {
    @Test
    fun languageVersionsAreFoldedThroughJapaneseTitle() {
        val g = VersionGrouper()
        val original = g.add(entry(100, "(C99) [サークル (作者)] すごいタイトル (東方Project)"))
        val chinese = g.add(
            entry(
                200,
                "(C99) [Circle (Sakusha)] Sugoi Title (Touhou Project) [Chinese] [汉化组]",
                titleJpn = "(C99) [サークル (作者)] すごいタイトル (東方Project) [中国翻訳]",
                lang = "ZH",
            ),
        )
        val english = g.add(
            entry(
                300,
                "(C99) [Circle (Sakusha)] Sugoi Title (Touhou Project) [English]",
                titleJpn = "(C99) [サークル (作者)] すごいタイトル (東方Project) [英訳]",
                lang = "EN",
            ),
        )
        assertSame(original, chinese)
        assertSame(original, english)
        assertEquals(3, original.members.size)
    }

    @Test
    fun reuploadsWithSameTitleAreFolded() {
        val g = VersionGrouper()
        val a = g.add(entry(1, "[Artist] Title Here [Chinese] [A组]", lang = "ZH"))
        val b = g.add(entry(2, "[Artist] Title Here [Chinese] [B组] [Decensored]", lang = "ZH"))
        assertSame(a, b)
    }

    @Test
    fun sameTitleDifferentArtistIsNotFolded() {
        val g = VersionGrouper()
        val a = g.add(entry(1, "[Artist One] Summer Vacation"))
        val b = g.add(entry(2, "[Artist Two] Summer Vacation"))
        assertNotSame(a, b)
    }

    @Test
    fun sameTitleWithoutCreatorIsNotFolded() {
        val g = VersionGrouper()
        val a = g.add(entry(1, "Summer Vacation"))
        val b = g.add(entry(2, "Summer Vacation"))
        assertNotSame(a, b)
    }

    @Test
    fun sameTitleSameArtistDifferentCategoryIsNotFolded() {
        val g = VersionGrouper()
        val a = g.add(entry(1, "[Artist] Summer Vacation", category = DOUJINSHI))
        val b = g.add(entry(2, "[Artist] Summer Vacation", category = MANGA))
        assertNotSame(a, b)
    }

    @Test
    fun conflictingArtistTagsBlockTitleMatch() {
        val g = VersionGrouper()
        // Same circle in the title, different artist tags: different works.
        val a = g.add(entry(1, "[Circle] Night Story", tags = listOf("artist:alice", "group:circle")))
        val b = g.add(entry(2, "[Circle] Night Story", tags = listOf("artist:bob")))
        assertNotSame(a, b)
    }

    @Test
    fun conflictingParodiesBlockTitleMatch() {
        val g = VersionGrouper()
        val a = g.add(entry(1, "[Artist] Night Story", tags = listOf("parody:touhou project")))
        val b = g.add(entry(2, "[Artist] Night Story", tags = listOf("parody:kantai collection")))
        assertNotSame(a, b)
    }

    @Test
    fun veryDifferentPageCountsAreNotFolded() {
        val g = VersionGrouper()
        val a = g.add(entry(1, "[Artist] Long Story", pages = 20))
        val b = g.add(entry(2, "[Artist] Long Story", pages = 200))
        assertNotSame(a, b)
        val c = g.add(entry(3, "[Artist] Long Story [English]", pages = 22, lang = "EN"))
        assertSame(a, c)
    }

    @Test
    fun differentChaptersAreNotFolded() {
        val g = VersionGrouper()
        val a = g.add(entry(1, "[Artist] Story Ch. 1"))
        val b = g.add(entry(2, "[Artist] Story Ch. 2"))
        assertNotSame(a, b)
    }

    @Test
    fun versionChainFoldsEvenWithDifferentTitles() {
        val registry = VersionChainRegistry()
        registry.link(10, 20)
        val g = VersionGrouper(registry::rootOf)
        val a = g.add(entry(10, "Old Title"))
        val b = g.add(entry(20, "Completely Renamed Upload"))
        assertSame(a, b)
    }

    @Test
    fun duplicateGidReturnsSameGroupOnce() {
        val g = VersionGrouper()
        val a = g.add(entry(1, "[Artist] Title Here"))
        val b = g.add(entry(1, "[Artist] Title Here"))
        assertSame(a, b)
        assertEquals(1, a.members.size)
    }
}
