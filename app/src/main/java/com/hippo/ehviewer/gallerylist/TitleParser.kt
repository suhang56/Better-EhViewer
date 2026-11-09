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

import java.text.Normalizer
import java.util.Locale

/**
 * Parses E-Hentai style titles:
 *
 * `(Event) [Circle (Artist)] Main Title (Parody) [Language] [Translator] [Extras] {Group}`
 *
 * The parser is deliberately conservative: bracket blocks that are not
 * known to be "decoration" (language, translator, quality markers) are kept
 * as part of the core title, so `Title (Zenpen)` and `Title (Kouhen)` never
 * collapse into the same key.
 */
object TitleParser {
    data class ParsedTitle(
        /** Normalized identity of the work, punctuation and spacing removed. Empty if unusable. */
        val core: String,
        /** Normalized creator names found in the leading `[Circle (Artist)]` block. */
        val creators: Set<String>,
        /** Normalized trailing `( ... )` blocks, usually the parody. */
        val parodies: Set<String>,
        /** Raw (lowercase) contents of all trailing `[...]` / `{...}` blocks. */
        val decorations: List<String>,
    ) {
        val isUsable: Boolean get() = core.isNotEmpty()
    }

    /** A core title shorter than this (in code points) is considered too generic to fold. */
    private const val MIN_CORE_LENGTH = 3

    /** Titles made only of these words are compilations, never folded. */
    private val GENERIC_WORDS = setOf(
        "artist", "artists", "cg", "cgs", "collection", "collections", "set", "sets", "pack",
        "illustration", "illustrations", "artwork", "artworks", "art", "works", "work", "misc",
        "miscellaneous", "various", "fanbox", "pixiv", "patreon", "fantia", "skeb", "twitter",
        "gumroad", "ci-en", "cien", "doujinshi", "manga", "comic", "comics", "image", "images",
        "picture", "pictures", "pics", "gallery", "update", "updates", "rough", "sketch",
        "sketches", "untitled", "no", "title", "notitle", "and", "the", "of", "vol", "part",
        "imageset", "selection", "compilation",
    )

    /** Parenthesized trailing blocks that are decoration rather than identity. */
    private val DECORATION_PAREN_WORDS = listOf(
        "decensored", "uncensored", "digital", "dl版", "無修正", "无修正", "colorized", "カラー化",
        "english", "chinese", "korean", "spanish", "french", "russian", "german", "italian",
        "portuguese", "thai", "vietnamese", "polish", "hungarian", "dutch", "translated",
        "中国翻訳", "中國翻譯", "英訳", "韓国翻訳", "complete", "ongoing", "incomplete",
    )

    private val OPEN_TO_CLOSE = mapOf('(' to ')', '[' to ']', '{' to '}')

    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('【', '[')
        .replace('】', ']')
        .replace('〔', '[')
        .replace('〕', ']')
        .replace('「', '[')
        .replace('」', ']')
        .trim()

    /** Remove everything that is not a letter or digit (keeps CJK). */
    fun squash(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private const val CACHE_SIZE = 4096
    private val EMPTY = ParsedTitle("", emptySet(), emptySet(), emptyList())
    private val cache = object : LinkedHashMap<String, ParsedTitle>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedTitle>?) = size > CACHE_SIZE
    }

    /** Parses [raw], memoized: list projections re-parse the same titles on every page load. */
    fun parse(raw: String?): ParsedTitle {
        if (raw.isNullOrBlank()) return EMPTY
        synchronized(cache) { cache[raw] }?.let { return it }
        val parsed = parseUncached(raw)
        synchronized(cache) { cache[raw] = parsed }
        return parsed
    }

    private fun parseUncached(raw: String): ParsedTitle {
        var text = normalize(raw)
        val creators = LinkedHashSet<String>()

        // Leading blocks: (Event) [Circle (Artist)] ...
        var creatorBlockSeen = false
        while (text.isNotEmpty() && text[0] in OPEN_TO_CLOSE) {
            val end = matchingClose(text, 0) ?: break
            val inner = text.substring(1, end).trim()
            if (text[0] == '[' && !creatorBlockSeen) {
                creatorBlockSeen = true
                creators += parseCreators(inner)
            }
            text = text.substring(end + 1).trim()
        }

        // Trailing blocks
        val decorations = ArrayList<String>()
        val parodies = LinkedHashSet<String>()
        val keptParens = ArrayList<String>()
        while (text.isNotEmpty() && (text.last() == ']' || text.last() == '}' || text.last() == ')')) {
            val start = matchingOpen(text, text.length - 1) ?: break
            if (start == 0) break // Whole remaining text is one block, keep it as title.
            val inner = text.substring(start + 1, text.length - 1).trim()
            val open = text[start]
            if (open == '(') {
                if (isDecorationParen(inner)) {
                    decorations += inner
                } else {
                    // Probably parody, but could be part of the title ("Zenpen").
                    keptParens += inner
                    squash(inner).takeIf { it.isNotEmpty() }?.let { parodies += it }
                }
            } else {
                decorations += inner
            }
            text = text.substring(0, start).trim()
        }
        // A title may have alternatives separated by '|': "Romaji | English". Use the first one.
        val main = text.substringBefore('|').trim()
        val coreBuilder = StringBuilder(squash(main))
        // Parenthesized identity blocks are part of the core so that
        // "Title (Zenpen)" and "Title (Kouhen)" stay different works.
        keptParens.asReversed().forEach { coreBuilder.append('(').append(squash(it)).append(')') }
        var core = coreBuilder.toString()
        if (!isMeaningfulCore(main)) core = ""
        return ParsedTitle(core, creators, parodies, decorations)
    }

    private fun isMeaningfulCore(main: String): Boolean {
        val squashed = squash(main)
        if (squashed.codePointCount(0, squashed.length) < MIN_CORE_LENGTH) return false
        // Composed only of generic words (no number / date) -> compilation-like title.
        val words = main.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isNotEmpty() && words.all { it in GENERIC_WORDS }) return false
        return true
    }

    private fun isDecorationParen(inner: String): Boolean {
        val s = inner.lowercase(Locale.ROOT)
        return DECORATION_PAREN_WORDS.any { s == it || s.contains(it) }
    }

    private fun parseCreators(inner: String): Set<String> {
        // "circle (artist1, artist2)" or "artist"
        val result = LinkedHashSet<String>()
        val parenStart = inner.indexOf('(')
        val circle: String
        val artists: String?
        if (parenStart > 0 && inner.endsWith(")")) {
            circle = inner.substring(0, parenStart)
            artists = inner.substring(parenStart + 1, inner.length - 1)
        } else {
            circle = inner
            artists = null
        }
        fun addAll(s: String) {
            s.split(',', '、', '&', '×', '/', '+').map { squash(it) }.filter { it.length >= 2 }.forEach { result += it }
        }
        addAll(circle)
        artists?.let { addAll(it) }
        // Drop things that are obviously not creators
        result.removeAll(setOf("english", "chinese", "korean", "translated", "anthology", "アンソロジー", "various", "variousartists"))
        return result
    }

    private fun matchingClose(text: String, openIndex: Int): Int? {
        val open = text[openIndex]
        val close = OPEN_TO_CLOSE[open] ?: return null
        var depth = 0
        for (i in openIndex until text.length) {
            when (text[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun matchingOpen(text: String, closeIndex: Int): Int? {
        val close = text[closeIndex]
        val open = OPEN_TO_CLOSE.entries.firstOrNull { it.value == close }?.key ?: return null
        var depth = 0
        for (i in closeIndex downTo 0) {
            when (text[i]) {
                close -> depth++
                open -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
