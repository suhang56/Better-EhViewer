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

const val DOUJINSHI = 0x2
const val MANGA = 0x4

fun entry(
    gid: Long,
    title: String?,
    titleJpn: String? = null,
    category: Int = DOUJINSHI,
    tags: List<String>? = null,
    pages: Int = 24,
    rating: Float = 4f,
    lang: String? = null,
) = GalleryEntry(
    gid = gid,
    title = title,
    titleJpn = titleJpn,
    category = category,
    tags = tags,
    pages = pages,
    rating = rating,
    simpleLanguage = lang,
)
