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

import com.hippo.ehviewer.gallerylist.GalleryListProjector.Options
import com.hippo.ehviewer.gallerylist.GalleryListProjector.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryListProjectorTest {
    private val off = Options(hideFavorites = false, foldVersions = false, preferredLanguage = null)
    private val hide = Options(hideFavorites = true, foldVersions = false, preferredLanguage = null)
    private val fold = Options(hideFavorites = false, foldVersions = true, preferredLanguage = "ZH")
    private val both = Options(hideFavorites = true, foldVersions = true, preferredLanguage = "ZH")
    private val noneFavorited: (Long) -> FavoriteState = { FavoriteState.NOT_FAVORITED }

    private fun gids(p: GalleryListProjector.Projection) = p.rows.map { it.gid }

    /** Every row must display (rawIndex) exactly the gallery it claims (gid). */
    private fun assertDisplayConsistent(list: List<GalleryEntry?>, p: GalleryListProjector.Projection) {
        p.rows.forEachIndexed { r, row ->
            assertEquals("row $r displays the wrong gallery", row.gid, list[p.rowToRaw(r)]!!.gid)
        }
        assertEquals("a gallery is shown twice", p.rows.size, p.rows.map { it.gid }.toSet().size)
    }

    @Test
    fun identityWhenBothFeaturesAreOff() {
        val list = listOf(entry(1, "[A] One"), entry(1, "[A] One"), entry(2, "[A] Two"))
        val p = GalleryListProjector.project(list, off, { FavoriteState.LOCAL })
        // Even duplicates are kept: behaviour is unchanged when the features are disabled.
        assertEquals(listOf(1L, 1L, 2L), gids(p))
        assertEquals(2, p.rawToRow(2))
        assertEquals(1, p.rowToRaw(1))
    }

    @Test
    fun hidesFavoritesByGidAndKeepsUnknown() {
        val list = listOf(entry(1, "[A] Same Name"), entry(2, "[A] Same Name"), entry(3, "[B] Other"), entry(4, "[C] Third"))
        val state = mapOf(1L to FavoriteState.CLOUD, 3L to FavoriteState.UNKNOWN, 4L to FavoriteState.LOCAL)
        val p = GalleryListProjector.project(list, hide, { state[it] ?: FavoriteState.NOT_FAVORITED })
        // gid 2 has the same title as favourited gid 1 but must stay visible.
        assertEquals(listOf(2L, 3L), gids(p))
        assertEquals(2, p.hiddenFavorites)
        assertEquals(1, p.unknownFavorites)
        assertEquals(FavoriteState.UNKNOWN, p.rows[1].favoriteState)
    }

    @Test
    fun duplicatesAcrossPagesAreRemoved() {
        // Page 2 repeats gid 3 because new uploads shifted the listing.
        val page1 = listOf(entry(1, "[A] One"), entry(2, "[A] Two"), entry(3, "[A] Three"))
        val page2 = listOf(entry(3, "[A] Three"), entry(4, "[A] Four"))
        val p = GalleryListProjector.project(page1 + page2, hide, noneFavorited)
        assertEquals(listOf(1L, 2L, 3L, 4L), gids(p))
        assertEquals(1, p.duplicates)
        // The duplicate raw index maps to the next shown row.
        assertEquals(3, p.rawToRow(3))
    }

    @Test
    fun foldsVersionsAndPicksPreferredLanguage() {
        val list = listOf(
            entry(10, "[作者] タイトル"),
            entry(11, "[Other] Unrelated Work"),
            entry(12, "[Artist] Title [English]", titleJpn = "[作者] タイトル [英訳]", lang = "EN"),
            entry(13, "[Artist] Title [Chinese]", titleJpn = "[作者] タイトル [中国翻訳]", lang = "ZH"),
        )
        val p = GalleryListProjector.project(list, fold, noneFavorited)
        assertEquals(listOf(13L, 11L), gids(p))
        assertDisplayConsistent(list, p)
        val rep = p.rows[0]
        assertEquals(Role.REPRESENTATIVE, rep.role)
        assertEquals(3, rep.groupSize)
        assertEquals(3, rep.rawIndex) // displays and opens the chosen representative (gid 13)
        assertEquals(0, rep.anchorIndex) // but sits where the group first appeared
        assertEquals(0, p.rowToAnchor(0))
        assertEquals(2, p.foldedAway)
        // Raw positions of folded members map to the group row.
        assertEquals(0, p.rawToRow(2))
        assertEquals(0, p.rawToRow(3))
        assertEquals(1, p.rawToRow(1))
    }

    @Test
    fun expandedGroupShowsAllVersionsAfterRepresentative() {
        val list = listOf(
            entry(10, "[作者] タイトル"),
            entry(11, "[Other] Unrelated Work"),
            entry(13, "[Artist] Title [Chinese]", titleJpn = "[作者] タイトル [中国翻訳]", lang = "ZH"),
        )
        val p = GalleryListProjector.project(list, fold, noneFavorited, expandedGids = setOf(10L, 13L))
        assertEquals(listOf(13L, 10L, 11L), gids(p))
        assertDisplayConsistent(list, p)
        assertEquals(listOf(2, 0, 1), p.rows.map { it.rawIndex })
        assertEquals(listOf(0, 0, 1), p.rows.map { it.anchorIndex })
        assertEquals(listOf(Role.REPRESENTATIVE, Role.MEMBER, Role.SINGLE), p.rows.map { it.role })
        assertTrue(p.rows[0].expanded)
        // Stable ids: group row keeps its id regardless of which version represents it.
        assertEquals("g10", p.rows[0].stableId)
        assertEquals("m10", p.rows[1].stableId)
        assertEquals(setOf(10L, 13L), p.groupMembers[10L]!!.toSet())
    }

    @Test
    fun sameNameDifferentWorksStaySeparate() {
        val list = listOf(
            entry(1, "[Artist One] Summer Vacation"),
            entry(2, "[Artist Two] Summer Vacation"),
            entry(3, "Summer Vacation"),
            entry(4, "Summer Vacation"),
        )
        val p = GalleryListProjector.project(list, fold, noneFavorited)
        assertEquals(listOf(1L, 2L, 3L, 4L), gids(p))
        assertTrue(p.rows.all { it.role == Role.SINGLE })
        assertEquals(0, p.foldedAway)
    }

    @Test
    fun hiddenFavoriteIsNotAGroupMemberAndOtherVersionsStay() {
        val list = listOf(
            entry(10, "[作者] タイトル"),
            entry(13, "[Artist] Title [Chinese]", titleJpn = "[作者] タイトル [中国翻訳]", lang = "ZH"),
        )
        val p = GalleryListProjector.project(list, both, { if (it == 13L) FavoriteState.CLOUD else FavoriteState.NOT_FAVORITED })
        assertEquals(listOf(10L), gids(p))
        assertEquals(Role.SINGLE, p.rows[0].role)
    }

    @Test
    fun allHiddenGivesEmptyProjectionWithSafeMapping() {
        val list = listOf(entry(1, "[A] One"), entry(2, "[A] Two"))
        val p = GalleryListProjector.project(list, hide, { FavoriteState.CLOUD })
        assertEquals(0, p.size)
        assertEquals(0, p.rawToRow(1))
        assertEquals(-1, p.rowToRaw(0))
    }

    @Test
    fun nullEntriesAreSkipped() {
        val list = listOf(entry(1, "[A] One"), null, entry(2, "[A] Two"))
        assertEquals(listOf(1L, 2L), gids(GalleryListProjector.project(list, off, noneFavorited)))
        assertEquals(listOf(1L, 2L), gids(GalleryListProjector.project(list, both, noneFavorited)))
    }

    @Test
    fun representativeChangeKeepsRowIdentity() {
        val page1 = listOf(entry(10, "[作者] タイトル"), entry(11, "[Other] Unrelated Work"))
        val page2 = listOf(entry(13, "[Artist] Title [Chinese]", titleJpn = "[作者] タイトル [中国翻訳]", lang = "ZH"))
        val before = GalleryListProjector.project(page1, fold, noneFavorited)
        val after = GalleryListProjector.project(page1 + page2, fold, noneFavorited)
        assertDisplayConsistent(page1 + page2, after)
        assertEquals("g10", before.rows[0].stableId)
        assertEquals("g10", after.rows[0].stableId)
        assertEquals(13L, after.rows[0].gid)
        assertEquals(before.size, after.size)
    }
}
