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
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FoldingAdversarialExtraTest {
    private fun folds(a: GalleryEntry, b: GalleryEntry): Boolean {
        val g = VersionGrouper()
        return g.add(a) === g.add(b)
    }

    @Test fun f6b_aiGeneratedPrefixHidesTheRealArtistBlock() {
        val cat = 0x20 // Image Set
        // Two different AI "artists"; the real creator block is the second [] and is ignored.
        val a = entry(1, "[AI Generated] [Alice] Raiden Shogun (Genshin Impact)", category = cat, pages = 80)
        val b = entry(2, "[AI Generated] [Bob] Raiden Shogun (Genshin Impact)", category = cat, pages = 90)
        assertEquals(false, folds(a, b))
    }

    @Test fun f6c_unknownArtistIsNoAuthorInformation() {
        val a = entry(1, "[Unknown] Ane no Himitsu", pages = 20)
        val b = entry(2, "[Unknown] Ane no Himitsu", pages = 22)
        // Doc: galleries with the same name and no author information are never folded.
        assertEquals(false, folds(a, b))
        val c = entry(3, "Ane no Himitsu", pages = 20)
        val d = entry(4, "Ane no Himitsu", pages = 22)
        assertEquals(false, folds(c, d)) // passes: shows the inconsistency
    }

    @Test fun f8_thaiToneMarksAreNotDropped() {
        // ป่า (forest) vs ป้า (aunt): differ only by the tone mark (Mn)
        assertNotEquals(TitleParser.parse("[Artist] ป่าลึกลับ").core, TitleParser.parse("[Artist] ป้าลึกลับ").core)
    }

    @Test fun translationsStillFoldWithTheOriginal() {
        val orig = entry(1, "(C102) [Circle (Artist)] Sugoi Story (Blue Archive)", pages = 24)
        val zh = entry(2, "(C102) [Circle (Artist)] Sugoi Story (Blue Archive) [Chinese] [某汉化组] [DL版]", pages = 26, lang = "ZH")
        val en = entry(3, "[Circle (Artist)] Sugoi Story | Great Story (Blue Archive) [English] [Digital]", pages = 25, lang = "EN")
        val omake = entry(4, "(C102) [Circle (Artist)] Sugoi Story + Omake (Blue Archive)", pages = 28)
        val g = VersionGrouper()
        val go = g.add(orig)
        assertEquals(true, go === g.add(zh))
        assertEquals(true, go === g.add(en))
        // "+ Omake" is a different edition: kept separate (conservative).
        assertEquals(false, go === g.add(omake))
    }
}
