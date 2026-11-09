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
package com.hippo.ehviewer.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class CloudFavoritesDao {
    @Query("SELECT * FROM CLOUD_FAVORITES")
    abstract fun list(): List<CloudFavoriteInfo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsert(list: List<CloudFavoriteInfo>)

    @Query("DELETE FROM CLOUD_FAVORITES WHERE GID IN (:gids)")
    abstract fun delete(gids: List<Long>)

    @Query("DELETE FROM CLOUD_FAVORITES")
    abstract fun deleteAll()

    @Transaction
    open fun replaceAll(list: List<CloudFavoriteInfo>) {
        deleteAll()
        upsert(list)
    }
}
