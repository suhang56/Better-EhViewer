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
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLoadPolicyTest {
    @Test
    fun autoLoadIsBounded() {
        val policy = AutoLoadPolicy(maxConsecutiveAutoPages = 3)
        var requests = 0
        // Simulate pages whose results are all hidden: without a cap this would loop forever.
        repeat(100) {
            if (policy.shouldAutoLoad(hasMore = true, visibleRows = 0, visibleAddedByLastPage = 0)) {
                policy.onAutoLoad()
                requests++
            }
        }
        assertEquals(3, requests)
        assertTrue(policy.isExhausted)
        policy.onUserAction()
        assertTrue(policy.shouldAutoLoad(hasMore = true, visibleRows = 0, visibleAddedByLastPage = 0))
    }

    @Test
    fun noAutoLoadWhenEnoughResultsOrNoMorePages() {
        val policy = AutoLoadPolicy()
        assertFalse(policy.shouldAutoLoad(hasMore = true, visibleRows = 40, visibleAddedByLastPage = 20))
        assertFalse(policy.shouldAutoLoad(hasMore = false, visibleRows = 0, visibleAddedByLastPage = 0))
        assertTrue(policy.shouldAutoLoad(hasMore = true, visibleRows = 40, visibleAddedByLastPage = 1))
        assertTrue(policy.shouldAutoLoad(hasMore = true, visibleRows = 3, visibleAddedByLastPage = 3))
    }
}
