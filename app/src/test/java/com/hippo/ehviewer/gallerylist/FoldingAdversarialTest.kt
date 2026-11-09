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

/**
 * Each test asserts the behaviour the requirement asks for. A failing test is a proven defect.
 */
class FoldingAdversarialTest {
    private fun folds(a: GalleryEntry, b: GalleryEntry): Boolean {
        val g = VersionGrouper()
        return g.add(a) === g.add(b)
    }

    private fun projectRows(entries: List<GalleryEntry>, lang: String? = null) = GalleryListProjector.project(
        entries,
        GalleryListProjector.Options(hideFavorites = false, foldVersions = true, preferredLanguage = lang),
        favoriteState = { FavoriteState.NOT_FAVORITED },
    ).rows

    // ---------------------------------------------------------------- F1 trailing [] / 【】 blocks dropped

    @Test fun f1_zenpenKouhenInLenticularBracketsStaySeparate() {
        // Japanese part markers are very often written in 【】.
        val a = entry(1, "[作者] 彼女のヒミツ【前編】", pages = 26)
        val b = entry(2, "[作者] 彼女のヒミツ【後編】", pages = 30)
        assertNotEquals(TitleParser.parse(a.title).core, TitleParser.parse(b.title).core)
        assertEquals(false, folds(a, b))
    }

    @Test fun f1_episodeNumberInBracketsStaysSeparate() {
        val a = entry(1, "[作者] 隣のお姉さん [第1話]", pages = 22)
        val b = entry(2, "[作者] 隣のお姉さん [第2話]", pages = 24)
        assertEquals(false, folds(a, b))
    }

    @Test fun f1_partInSquareBracketsStaysSeparate() {
        val a = entry(1, "[Artist] Summer Vacation [Part 1] [English]", pages = 30, lang = "EN")
        val b = entry(2, "[Artist] Summer Vacation [Part 2] [English]", pages = 34, lang = "EN")
        assertEquals(false, folds(a, b))
    }

    @Test fun f1_chapterRangeInSquareBracketsStaysSeparate() {
        val a = entry(1, "[Artist] Summer Vacation [Ch. 1-5] [English]", pages = 110, lang = "EN")
        val b = entry(2, "[Artist] Summer Vacation [Ch. 6-10] [English]", pages = 120, lang = "EN")
        assertEquals(false, folds(a, b))
    }

    @Test fun f1_monthlyDumpsWithDateInBracketsStaySeparate() {
        val cat = 0x8 // Artist CG
        val a = entry(1, "[Artist] Patreon Rewards [2023-05]", category = cat, pages = 40)
        val b = entry(2, "[Artist] Patreon Rewards [2023-06]", category = cat, pages = 45)
        assertEquals(false, folds(a, b))
        val c = entry(3, "[Fanbox] Hoshino Ryuichi [2023.05]", category = cat, pages = 40)
        val d = entry(4, "[Fanbox] Hoshino Ryuichi [2023.06]", category = cat, pages = 45)
        assertEquals(false, folds(c, d))
    }

    @Test fun f1_cosplaySetsDifferingInBracketStaySeparate() {
        val cos = 0x40 // Cosplay
        val a = entry(1, "[Byoru] Raiden Shogun (Genshin Impact) [Swimsuit]", category = cos, pages = 60)
        val b = entry(2, "[Byoru] Raiden Shogun (Genshin Impact) [Bunny Girl]", category = cos, pages = 70)
        assertEquals(false, folds(a, b))
    }

    @Test fun f1_projectorHidesThePartTwoAsAVersionOfPartOne() {
        val rows = projectRows(
            listOf(
                entry(1, "[作者] 彼女のヒミツ【前編】", pages = 26),
                entry(2, "[作者] 彼女のヒミツ【後編】", pages = 30),
            ),
        )
        assertEquals("both parts must be visible rows", 2, rows.size)
    }

    // ---------------------------------------------------------------- F2 「」 quotes treated as brackets

    @Test fun f2_trailingJapaneseQuoteIsNotDecoration() {
        val a = entry(1, "[作者] 彼女の「秘密」", pages = 24)
        val b = entry(2, "[作者] 彼女の「願い」", pages = 26)
        assertEquals(false, folds(a, b))
    }

    @Test fun f2_leadingJapaneseQuoteIsNotALeadingBlock() {
        val a = entry(1, "[作者] 「好き」って言わせたい", pages = 24)
        val b = entry(2, "[作者] 「嫌い」って言わせたい", pages = 26)
        assertEquals(false, folds(a, b))
    }

    // ---------------------------------------------------------------- F3 differing events ignored

    @Test fun f3_sameCircleRecurringGenericBookAtDifferentEventsStaysSeparate() {
        val a = entry(1, "(C95) [Circle (Artist)] Rakugaki Bon", pages = 8)
        val b = entry(2, "(C96) [Circle (Artist)] Rakugaki Bon", pages = 10)
        assertEquals(false, folds(a, b))
        val c = entry(3, "(C97) [サークル (作者)] コピー本", pages = 8)
        val d = entry(4, "(C99) [サークル (作者)] コピー本", pages = 12)
        assertEquals(false, folds(c, d))
    }

    // ---------------------------------------------------------------- F4 CJK generic compilation titles

    @Test fun f4_japaneseCompilationTitlesAreNotUsableLikeEnglishOnes() {
        // The English equivalent is deliberately never folded ...
        assertEquals(false, TitleParser.parse("[Artist] Artist CG Collection").isUsable)
        // ... but the Japanese / Chinese equivalents are.
        assertEquals(false, TitleParser.parse("[作者] CG集").isUsable)
        assertEquals(false, TitleParser.parse("[作者] イラスト集").isUsable)
        assertEquals(false, TitleParser.parse("[作者] FANBOXまとめ").isUsable)
        assertEquals(false, TitleParser.parse("[作者] 作品合集").isUsable)
    }

    @Test fun f4_twoDifferentCgPacksWithGenericJapaneseTitleFold() {
        val cat = 0x8
        val a = entry(1, "[作者] FANBOXまとめ", category = cat, pages = 120)
        val b = entry(2, "[作者] FANBOXまとめ", category = cat, pages = 150)
        assertEquals(false, folds(a, b))
    }

    // ---------------------------------------------------------------- F5 numeric separators squashed

    @Test fun f5_decimalAndRangeDoNotCollideWithTwoDigitNumbers() {
        assertNotEquals(TitleParser.parse("[Artist] Mesu Ochi 1.5").core, TitleParser.parse("[Artist] Mesu Ochi 15").core)
        assertNotEquals(TitleParser.parse("[Artist] Mesu Ochi Ch. 1-3").core, TitleParser.parse("[Artist] Mesu Ochi Ch. 13").core)
        assertNotEquals(TitleParser.parse("[Artist] Mesu Ochi Part 1+2").core, TitleParser.parse("[Artist] Mesu Ochi Part 12").core)
        val a = entry(1, "[Artist] Mesu Ochi 1.5 [English]", pages = 20, lang = "EN")
        val b = entry(2, "[Artist] Mesu Ochi 15 [English]", pages = 24, lang = "EN")
        assertEquals(false, folds(a, b))
    }

    // ---------------------------------------------------------------- F6 pseudo creators

    @Test fun f6_aiGeneratedPrefixIsNotACreator() {
        val cat = 0x20 // Image Set
        val a = entry(1, "[AI Generated] Raiden Shogun (Genshin Impact)", category = cat, pages = 80)
        val b = entry(2, "[AI Generated] Raiden Shogun (Genshin Impact)", category = cat, pages = 100)
        // Different uploaders, different images; no author information at all.
        assertEquals(false, folds(a, b))
    }

    @Test fun f6_translationGroupPrefixIsNotACreator() {
        val a = entry(1, "[脸肿汉化组] (C96) [Circle A (Alice)] Onee-chan to Issho", pages = 24, lang = "ZH")
        val b = entry(2, "[脸肿汉化组] (C98) [Circle B (Bob)] Onee-chan to Issho", pages = 26, lang = "ZH")
        assertEquals(false, folds(a, b))
    }

    // ---------------------------------------------------------------- F7 representative language

    @Test fun f7_originalPreferenceDoesNotPickUnmappedTranslationLanguage() {
        val original = entry(100, "[Artist] Mesu Ochi Biyori", pages = 24, rating = 4.0f)
        val indonesian = entry(
            200,
            "[Artist] Mesu Ochi Biyori [Indonesian] [Decensored]",
            pages = 24,
            rating = 4.5f,
            tags = listOf("artist:artist", "language:indonesian", "language:translated"),
        )
        assertEquals("JA", VersionQuality.languageOf(original))
        assertNotEquals("indonesian is not the original language", "JA", VersionQuality.languageOf(indonesian))
        val rows = projectRows(listOf(original, indonesian), lang = "JA")
        assertEquals(1, rows.size)
        assertEquals("representative must be the Japanese original", 100L, rows[0].gid)
    }
}
