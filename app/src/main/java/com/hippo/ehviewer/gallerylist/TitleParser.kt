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
        /** Normalized creator names found in the last leading `[Circle (Artist)]` block. */
        val creators: Set<String>,
        /** Normalized trailing `( ... )` identity blocks, usually the parody. */
        val parodies: Set<String>,
        /** Raw (lowercase) contents of the trailing blocks recognised as decoration. */
        val decorations: List<String>,
        /** Normalized leading `(Event)` blocks such as `c102`, `comic1☆15`. */
        val events: Set<String> = emptySet(),
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
        "imageset", "selection", "compilation", "rakugaki", "omake", "bon", "hon", "copy",
        "rewards", "reward", "pack", "dump", "archive",
    )

    /** CJK generic fragments: a title made only of these is a compilation. */
    private val GENERIC_CJK = listOf(
        "イラスト集", "作品集", "cg集", "画集", "图集", "圖集", "合集", "まとめ", "支援者限定",
        "ファンボックス", "落書き", "ラクガキ", "らくがき", "おまけ本", "おまけ", "コピー本", "無配本",
        "無料配布", "会場限定本", "作品", "イラスト", "插画", "插圖", "差分",
    )

    /** Block contents that mark a translation (language) - anything after them is a credit. */
    private val LANGUAGE_MARKERS = listOf(
        "english", "chinese", "korean", "spanish", "español", "french", "russian", "german", "italian",
        "portuguese", "thai", "vietnamese", "polish", "hungarian", "dutch", "indonesian", "arabic",
        "turkish", "ukrainian", "czech", "tagalog", "filipino", "malay", "swedish", "norwegian",
        "danish", "finnish", "greek", "hebrew", "romanian", "bulgarian", "serbian", "croatian",
        "slovak", "persian", "hindi", "catalan", "mongolian", "translated", "translation",
        "中国翻訳", "中國翻譯", "中国語", "英訳", "韓国翻訳", "翻訳", "翻译", "翻譯", "汉化", "漢化",
        "中文", "简体", "簡體", "繁体", "繁體", "한국어", "ภาษาไทย", "tiếng việt",
    )

    /** Block contents that are decoration (quality / format / version) wherever they appear. */
    private val DECORATION_MARKERS = listOf(
        "decensored", "uncensored", "digital", "dl版", "無修正", "无修正", "無碼", "无码",
        "colorized", "colourized", "カラー化", "彩色", "complete", "ongoing", "incomplete",
        "進行中", "进行中", "連載中", "机翻", "機翻", "机器翻译", "機械翻訳", "machine translation",
        "mtl", "ai翻译", "ai翻訳", "textless", "無字", "无字", "rough translation", "sample",
        "サンプル", "preview", "修正版", "改訂版", "fixed", "reupload", "re-upload", "再up", "重新上传",
        "高画質", "高画质", "hd", "4k", "webp", "png", "jpg", "zip",
    )
    private val SIZE_OR_VERSION = Regex("""^(\d+(\.\d+)?\s*(p|pages?|枚|mb|gb|kb)|v(er(sion)?)?\.?\s*\d+(\.\d+)*)$""")

    /** Leading `[ ]` blocks that are not a creator. */
    private val PSEUDO_CREATORS = setOf(
        "aigenerated", "ai", "unknown", "不明", "na", "various", "variousartists", "anthology",
        "アンソロジー", "pixiv", "fanbox", "patreon", "fantia", "雑誌", "magazine", "3d", "cosplay",
        "english", "chinese", "korean", "translated", "全彩", "彩色", "成年コミック", "一般コミック",
    )
    private val TRANSLATOR_HINTS = listOf("汉化", "漢化", "翻訳", "翻译", "翻譯", "scan", "translat", "字幕", "嵌字")

    private val OPEN_TO_CLOSE = mapOf('(' to ')', '[' to ']', '{' to '}')

    /**
     * NFKC + lowercase. Only 【】 / 〔〕 are tag brackets in E-Hentai titles; 「」『』 are
     * ordinary quotation marks and stay part of the title.
     */
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('【', '[')
        .replace('】', ']')
        .replace('〔', '[')
        .replace('〕', ']')
        .trim()

    private fun isKept(cp: Int): Boolean {
        if (Character.isLetterOrDigit(cp)) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }

    /**
     * Removes punctuation and spacing but keeps letters, digits and combining marks (Thai,
     * Devanagari...). Numbers separated by punctuation keep a '.' between them, so "1.5",
     * "1-3" and "1+2" never collapse into "15", "13" and "12".
     */
    fun squash(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var removedSinceLast = false
        var lastWasDigit = false
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isKept(cp)) {
                val isDigit = Character.isDigit(cp)
                if (removedSinceLast && lastWasDigit && isDigit) sb.append('.')
                sb.appendCodePoint(cp)
                lastWasDigit = isDigit
                removedSinceLast = false
            } else {
                removedSinceLast = true
            }
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

        // Leading blocks: [TL group] (Event) [Circle (Artist)] ...
        // The creator is the LAST leading [ ] block that is not a pseudo creator; earlier ones are
        // usually translation groups or labels such as [AI Generated].
        val events = LinkedHashSet<String>()
        val leadingSquare = ArrayList<String>()
        while (text.isNotEmpty() && text[0] in OPEN_TO_CLOSE) {
            val end = matchingClose(text, 0) ?: break
            if (end == text.length - 1) break // The whole remaining text is one block: keep it as title.
            val inner = text.substring(1, end).trim()
            when (text[0]) {
                '(' -> squash(inner).takeIf { it.isNotEmpty() }?.let { events += it }
                '[' -> leadingSquare += inner
            }
            text = text.substring(end + 1).trim()
        }
        val creators = leadingSquare.asReversed().asSequence()
            .map { parseCreators(it) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

        // Trailing blocks, collected right to left.
        val trailing = ArrayList<Pair<Char, String>>()
        while (text.isNotEmpty() && (text.last() == ']' || text.last() == '}' || text.last() == ')')) {
            val start = matchingOpen(text, text.length - 1) ?: break
            if (start == 0) break // Whole remaining text is one block, keep it as title.
            trailing += text[start] to text.substring(start + 1, text.length - 1).trim()
            text = text.substring(0, start).trim()
        }
        // Left to right. Everything after the first language marker is a translator / group credit.
        val blocks = trailing.asReversed()
        val firstLanguage = blocks.indexOfFirst { (open, inner) -> open != '(' && isLanguageMarker(inner) }
            .let { if (it >= 0) it else blocks.indexOfFirst { (_, inner) -> isLanguageMarker(inner) } }
        val decorations = ArrayList<String>()
        val identity = ArrayList<String>()
        val parodies = LinkedHashSet<String>()
        blocks.forEachIndexed { index, (open, inner) ->
            val decoration = open == '{' ||
                (firstLanguage in 0..index) ||
                isDecorationMarker(inner)
            if (decoration) {
                decorations += inner
            } else {
                // Unknown block: part of the work's identity ("Zenpen", "[Part 2]", "[2023-05]", parody).
                identity += inner
                if (open == '(') squash(inner).takeIf { it.isNotEmpty() }?.let { parodies += it }
            }
        }
        // A title may have alternatives separated by '|': "Romaji | English". Use the first one.
        val main = text.substringBefore('|').trim()
        val coreBuilder = StringBuilder(squash(main))
        identity.forEach { coreBuilder.append('(').append(squash(it)).append(')') }
        var core = coreBuilder.toString()
        if (!isMeaningfulCore(main, identity)) core = ""
        return ParsedTitle(core, creators, parodies, decorations, events)
    }

    private fun isLanguageMarker(inner: String): Boolean = LANGUAGE_MARKERS.any { inner.contains(it) }

    private fun isDecorationMarker(inner: String): Boolean = isLanguageMarker(inner) ||
        DECORATION_MARKERS.any { inner == it || inner.contains(it) } ||
        SIZE_OR_VERSION.matches(inner)

    private fun isMeaningfulCore(main: String, identity: List<String>): Boolean {
        val squashed = squash(main)
        if (squashed.codePointCount(0, squashed.length) < MIN_CORE_LENGTH) return false
        // Composed only of generic words (English or CJK) without any number / identity block
        // -> compilation-like title ("Artist CG Collection", "CG集", "FANBOXまとめ").
        val hasNumber = squashed.any { it.isDigit() } || identity.any { block -> block.any { it.isDigit() } }
        if (hasNumber) return true
        val words = main.split(Regex("[^\\p{L}\\p{N}\\p{M}]+")).filter { it.isNotEmpty() }
        val meaningful = words.any { word ->
            var rest = word
            GENERIC_CJK.forEach { rest = rest.replace(it, " ") }
            rest.split(' ').filter { it.isNotEmpty() }.any { it !in GENERIC_WORDS }
        }
        return meaningful || identity.isNotEmpty()
    }

    private fun parseCreators(inner: String): Set<String> {
        val whole = squash(inner)
        if (whole in PSEUDO_CREATORS || TRANSLATOR_HINTS.any { inner.contains(it) }) return emptySet()
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
        result.removeAll(PSEUDO_CREATORS)
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
