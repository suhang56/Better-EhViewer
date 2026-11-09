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

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** Number of ratings and of favourites of a gallery, read from its detail page. Keyed by gallery id. */
@Entity(tableName = "GALLERY_STATS")
data class GalleryStatsInfo(
    @PrimaryKey
    @ColumnInfo(name = "GID")
    val gid: Long,
    @ColumnInfo(name = "RATING_COUNT")
    val ratingCount: Int?,
    @ColumnInfo(name = "FAVORITE_COUNT")
    val favoriteCount: Int?,
    @ColumnInfo(name = "FETCHED_AT")
    val fetchedAt: Long,
)

@Dao
interface GalleryStatsDao {
    @Query("SELECT * FROM GALLERY_STATS")
    fun list(): List<GalleryStatsInfo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(list: List<GalleryStatsInfo>)
}

/**
 * Cache of per-gallery statistics that the gallery list does not provide. Its own file, like
 * the cloud favourites cache: derived data, never part of eh.db export / import.
 */
// Version 2 replaced the rating-count-only table; it is a cache, so the old one is simply dropped.
@Database(entities = [GalleryStatsInfo::class], version = 2, exportSchema = false)
abstract class GalleryStatsDatabase : RoomDatabase() {
    abstract fun galleryStatsDao(): GalleryStatsDao
}

fun buildGalleryStatsDB(context: Context): GalleryStatsDatabase = Room.databaseBuilder(context, GalleryStatsDatabase::class.java, "gallery_stats.db")
    .allowMainThreadQueries()
    .fallbackToDestructiveMigration(true)
    .build()
