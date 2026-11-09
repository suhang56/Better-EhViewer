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
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Cache of the cloud favourites, kept in its own database file on purpose:
 * it is derived data that can always be re-synced, so it must not bump the
 * version of the user database `eh.db` (whose file format is also used by
 * data export / import).
 */
@Database(entities = [CloudFavoriteInfo::class], version = 1, exportSchema = false)
abstract class CloudFavoritesDatabase : RoomDatabase() {
    abstract fun cloudFavoritesDao(): CloudFavoritesDao
}

fun buildCloudFavoritesDB(context: Context): CloudFavoritesDatabase = Room.databaseBuilder(context, CloudFavoritesDatabase::class.java, "cloud_favorites.db")
    .allowMainThreadQueries()
    .fallbackToDestructiveMigration(true)
    .build()
