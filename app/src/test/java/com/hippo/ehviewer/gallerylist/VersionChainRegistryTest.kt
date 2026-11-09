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
import org.junit.Assert.assertNull
import org.junit.Test

class VersionChainRegistryTest {
    @Test
    fun linksFormChainsRootedAtFirstUpload() {
        val r = VersionChainRegistry()
        r.link(30, 20)
        r.link(20, 10)
        r.link(40, 30)
        assertEquals(10L, r.rootOf(40))
        assertEquals(10L, r.rootOf(10))
        assertEquals(40L, r.newestOf(10))
        assertNull(r.rootOf(99))
    }

    @Test
    fun invalidLinksAreIgnored() {
        val r = VersionChainRegistry()
        r.link(5, 5)
        r.link(0, 5)
        r.link(-1, 5)
        assertNull(r.rootOf(5))
    }

    @Test
    fun boundedMemoryNeverCreatesWrongLinks() {
        val r = VersionChainRegistry(maxEntries = 10)
        for (i in 1L..100L) r.link(i * 1000, i * 1000 + 1)
        // Old chains may be forgotten but two unrelated galleries never share a root.
        val a = r.rootOf(1000)
        val b = r.rootOf(2000)
        if (a != null && b != null) assertEquals(false, a == b)
        assertEquals(100_000L, r.rootOf(100_001))
    }
}
