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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleParserTest {
    @Test
    fun parsesEventCreatorAndDecorations() {
        val p = TitleParser.parse("(C99) [Circle Name (Artist A, Artist B)] Great Title (Touhou Project) [English] [Some Translator] [Decensored]")
        assertEquals(setOf("circlename", "artista", "artistb"), p.creators)
        assertTrue(p.core.startsWith("greattitle"))
        assertTrue(p.decorations.contains("english"))
        assertTrue(p.decorations.contains("decensored"))
        assertEquals(setOf("touhouproject"), p.parodies)
    }

    @Test
    fun translationAndOriginalShareCore() {
        val original = TitleParser.parse("(C99) [サークル (作者)] すごいタイトル (東方Project)")
        val translationJpn = TitleParser.parse("(C99) [サークル (作者)] すごいタイトル (東方Project) [中国翻訳] [DL版]")
        assertEquals(original.core, translationJpn.core)
        assertEquals(original.creators, translationJpn.creators)
    }

    @Test
    fun fullWidthBracketsAreNormalized() {
        val a = TitleParser.parse("［作者］タイトル　ＡＢＣ")
        val b = TitleParser.parse("[作者] タイトル ABC")
        assertEquals(a.core, b.core)
        assertEquals(a.creators, b.creators)
    }

    @Test
    fun differentPartsStayDifferent() {
        val zenpen = TitleParser.parse("[Artist] Story (Zenpen)")
        val kouhen = TitleParser.parse("[Artist] Story (Kouhen)")
        assertNotEquals(zenpen.core, kouhen.core)
        val vol1 = TitleParser.parse("[Artist] Story 1")
        val vol2 = TitleParser.parse("[Artist] Story 2")
        assertNotEquals(vol1.core, vol2.core)
    }

    @Test
    fun genericTitlesAreNotUsable() {
        assertFalse(TitleParser.parse("[Artist] Artist CG Collection").isUsable)
        assertFalse(TitleParser.parse("[Artist] Fanbox").isUsable)
        assertFalse(TitleParser.parse("[Artist] ab").isUsable)
        assertFalse(TitleParser.parse("").isUsable)
        assertFalse(TitleParser.parse(null).isUsable)
        assertTrue(TitleParser.parse("[Artist] Fanbox 2023-05").isUsable)
    }

    @Test
    fun unbalancedBracketsDoNotCrash() {
        val p = TitleParser.parse("[Artist (Name] Title (unclosed")
        assertTrue(p.core.isNotEmpty() || !p.isUsable)
        TitleParser.parse("]]]((([[[")
        TitleParser.parse("[Only A Block]")
    }
}
