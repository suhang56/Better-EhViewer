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

import java.util.Locale

/**
 * Chooses the representative of a folded group: user's preferred language
 * first, then quality signals (complete, not machine translated, decensored,
 * rating, newer upload).
 */
object VersionQuality {
    /** Pseudo language code meaning "Japanese / original (no translation language tag)". */
    const val LANG_ORIGINAL = "JA"

    private val TAG_LANGUAGE = mapOf(
        "language:english" to "EN",
        "language:chinese" to "ZH",
        "language:spanish" to "ES",
        "language:korean" to "KO",
        "language:russian" to "RU",
        "language:french" to "FR",
        "language:portuguese" to "PT",
        "language:thai" to "TH",
        "language:german" to "DE",
        "language:italian" to "IT",
        "language:vietnamese" to "VI",
        "language:polish" to "PL",
        "language:hungarian" to "HU",
        "language:dutch" to "NL",
        "language:japanese" to LANG_ORIGINAL,
    )

    private val BAD_TAGS = mapOf(
        "other:incomplete" to -300,
        "other:rough translation" to -150,
        "other:ai generated" to -50,
        "language:rewrite" to -200,
        "other:watermarked" to -60,
        "other:extraneous ads" to -60,
        "other:missing cover" to -40,
        "other:already uploaded" to -80,
        "other:forbidden content" to -40,
    )
    private val GOOD_TAGS = mapOf(
        "other:uncensored" to 40,
        "other:decensored" to 30,
        "other:full color" to 10,
    )
    private val BAD_DECORATIONS = listOf(
        "機翻" to -150, "机翻" to -150, "机器翻译" to -150, "機械翻訳" to -150,
        "machine translation" to -150, "mtl" to -150, "ai翻译" to -150, "ai翻訳" to -150,
        "incomplete" to -300, "進行中" to -100, "进行中" to -100, "ongoing" to -100,
        "sample" to -200, "サンプル" to -200, "preview" to -200,
    )
    private val GOOD_DECORATIONS = listOf(
        "無修正" to 40,
        "无修正" to 40,
        "uncensored" to 40,
        "decensored" to 30,
        "dl版" to 15,
        "digital" to 15,
    )

    /** Language of a gallery, as a simple language code ([LANG_ORIGINAL] for Japanese/untagged). */
    fun languageOf(entry: GalleryEntry): String {
        entry.tags?.forEach { tag -> TAG_LANGUAGE[tag.lowercase(Locale.ROOT)]?.let { return it } }
        return entry.simpleLanguage?.uppercase(Locale.ROOT) ?: LANG_ORIGINAL
    }

    /**
     * @param preferredLanguage simple language code, or null when the user has no preference
     * @param isLatestVersion true if a version chain says this is the newest upload of the work
     */
    fun score(entry: GalleryEntry, preferredLanguage: String?, isLatestVersion: Boolean = false): Long {
        var score = 0L
        if (preferredLanguage != null && languageOf(entry) == preferredLanguage) score += 10_000
        val tags = entry.tags?.map { it.lowercase(Locale.ROOT) }.orEmpty()
        tags.forEach { tag ->
            BAD_TAGS[tag]?.let { score += it }
            GOOD_TAGS[tag]?.let { score += it }
        }
        val decorations = (TitleParser.parse(entry.title).decorations + TitleParser.parse(entry.titleJpn).decorations)
            .joinToString(" ")
        BAD_DECORATIONS.forEach { (marker, value) -> if (decorations.contains(marker)) score += value }
        GOOD_DECORATIONS.forEach { (marker, value) -> if (decorations.contains(marker)) score += value }
        if (isLatestVersion) score += 100
        score += (entry.rating.coerceIn(0f, 5f) * 10).toLong()
        score += entry.pages.coerceIn(0, 1000) / 50
        return score
    }

    /**
     * Comparator putting the best version first. Ties are broken by the newest
     * gallery id (newer uploads usually fix earlier mistakes), which keeps the
     * ordering deterministic.
     */
    fun comparator(preferredLanguage: String?, latestVersions: Set<Long> = emptySet()): Comparator<GalleryEntry> = compareByDescending<GalleryEntry> { score(it, preferredLanguage, it.gid in latestVersions) }
        .thenByDescending { it.gid }
}
