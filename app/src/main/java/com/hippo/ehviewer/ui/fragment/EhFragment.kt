/*
 * Copyright 2016 Hippo Seven
 *
 * Modified in 2026 by Better-EhViewer contributors (hide favorited galleries /
 * fold gallery versions); see NOTICE.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.ui.fragment

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.gallerylist.CloudFavoriteSyncer
import com.hippo.ehviewer.gallerylist.GalleryListFeatures
import com.hippo.util.launchNonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EhFragment : BasePreferenceFragment() {
    private lateinit var detailSize: Preference
    private lateinit var listThumbSize: Preference
    private lateinit var thumbSize: Preference
    private lateinit var thumbShowTitle: Preference
    private var cloudFavoritesSync: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.eh_settings)
        val account = findPreference<Preference>(Settings.KEY_ACCOUNT)
        val gallerySite = findPreference<Preference>(Settings.KEY_GALLERY_SITE)
        val theme = findPreference<Preference>(Settings.KEY_THEME)
        val blackDarkTheme = findPreference<Preference>(Settings.KEY_BLACK_DARK_THEME)
        val listMode = findPreference<Preference>(Settings.KEY_LIST_MODE)
        val showTagTranslations = findPreference<Preference>(Settings.KEY_SHOW_TAG_TRANSLATIONS)
        val tagTranslationsSource = findPreference<Preference>(Settings.KEY_TAG_TRANSLATIONS_SOURCE)
        detailSize = findPreference(Settings.KEY_DETAIL_SIZE)!!
        listThumbSize = findPreference(Settings.KEY_LIST_THUMB_SIZE)!!
        thumbSize = findPreference(Settings.KEY_THUMB_SIZE)!!
        thumbShowTitle = findPreference(Settings.KEY_THUMB_SHOW_TITLE)!!

        gallerySite!!.onPreferenceChangeListener = this
        theme!!.onPreferenceChangeListener = this
        blackDarkTheme!!.onPreferenceChangeListener = this
        listMode!!.onPreferenceChangeListener = this
        showTagTranslations!!.onPreferenceChangeListener = this
        detailSize.onPreferenceChangeListener = this
        listThumbSize.onPreferenceChangeListener = this
        thumbSize.onPreferenceChangeListener = this
        thumbShowTitle.onPreferenceChangeListener = this
        cloudFavoritesSync = findPreference(Settings.KEY_CLOUD_FAVORITES_SYNC)
        cloudFavoritesSync?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            syncCloudFavorites()
            true
        }
        if (!EhCookieStore.hasSignedIn()) {
            cloudFavoritesSync?.let { preferenceScreen.removePreference(it) }
            cloudFavoritesSync = null
        }
        updateCloudFavoritesSummary()
        Settings.displayName?.let { account?.summary = it }
        if (!EhTagDatabase.isTranslatable(requireActivity())) {
            if (!Settings.showTagTranslations) {
                preferenceScreen.removePreference(showTagTranslations)
            }
            preferenceScreen.removePreference(tagTranslationsSource!!)
        }
        if (!EhCookieStore.hasSignedIn()) {
            Settings.SIGN_IN_REQUIRED.forEach {
                val preference = findPreference<Preference>(it)
                preferenceScreen.removePreference(preference!!)
            }
        }
        updateListPreference(Settings.listMode)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        val key = preference.key
        if (Settings.KEY_THEME == key) {
            AppCompatDelegate.setDefaultNightMode((newValue as String).toInt())
            requireActivity().recreate()
        } else if (Settings.KEY_BLACK_DARK_THEME == key) {
            if (requireActivity().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES > 0) {
                EhApplication.application.recreateAllActivity()
            }
        } else if (Settings.KEY_GALLERY_SITE == key) {
            requireActivity().setResult(Activity.RESULT_OK)
            lifecycleScope.launchNonCancellable {
                runCatching {
                    EhEngine.getUConfig()
                }.onFailure {
                    Log.e("EhFragment", "Error", it)
                }
            }
        } else if (Settings.KEY_LIST_MODE == key) {
            updateListPreference((newValue as String).toInt())
            requireActivity().setResult(Activity.RESULT_OK)
        } else if (Settings.KEY_DETAIL_SIZE == key) {
            requireActivity().setResult(Activity.RESULT_OK)
        } else if (Settings.KEY_LIST_THUMB_SIZE == key) {
            requireActivity().setResult(Activity.RESULT_OK)
        } else if (Settings.KEY_THUMB_SIZE == key) {
            requireActivity().setResult(Activity.RESULT_OK)
        } else if (Settings.KEY_THUMB_SHOW_TITLE == key) {
            requireActivity().setResult(Activity.RESULT_OK)
        } else if (Settings.KEY_SHOW_TAG_TRANSLATIONS == key) {
            if (java.lang.Boolean.TRUE == newValue) {
                lifecycleScope.launchNonCancellable { EhTagDatabase.update(true) }
            }
        }
        return true
    }

    @get:StringRes
    override val fragmentTitle: Int
        get() = R.string.settings_eh

    private val favoriteDataListener = GalleryListFeatures.Listener { if (isAdded) updateCloudFavoritesSummary() }

    override fun onStart() {
        super.onStart()
        // A background sync (started from a gallery list) updates the summary while this screen is open.
        GalleryListFeatures.addListener(favoriteDataListener)
        updateCloudFavoritesSummary()
    }

    override fun onStop() {
        super.onStop()
        GalleryListFeatures.removeListener(favoriteDataListener)
    }

    private fun partialReason(code: String): String {
        val parts = code.split(':')
        return when (parts[0]) {
            CloudFavoriteSyncer.PARTIAL_PAGE_LIMIT -> getString(R.string.cloud_fav_partial_page_limit, parts.getOrNull(1)?.toIntOrNull() ?: 0)
            CloudFavoriteSyncer.PARTIAL_MISSING -> getString(
                R.string.cloud_fav_partial_missing,
                parts.getOrNull(1)?.toIntOrNull() ?: 0,
                parts.getOrNull(2)?.toIntOrNull() ?: 0,
            )
            else -> getString(R.string.cloud_fav_partial_stuck)
        }
    }

    private fun updateCloudFavoritesSummary() {
        val pref = cloudFavoritesSync ?: return
        if (GalleryListFeatures.isSyncing) {
            pref.summary = getString(R.string.settings_eh_cloud_favorites_syncing)
            return
        }
        val cache = GalleryListFeatures.cloudCache
        val state = runCatching { cache.ensureLoaded() }.map { cache.syncState }.getOrNull() ?: return
        val count = cache.size
        val partial = state.lastPartial
        pref.summary = when {
            state.lastError != null -> getString(R.string.settings_eh_cloud_favorites_sync_failed, state.lastError, count)
            partial != null -> getString(R.string.settings_eh_cloud_favorites_sync_partial_status, partialReason(partial), count)
            state.lastFullSyncAt > 0 -> getString(
                R.string.settings_eh_cloud_favorites_sync_status,
                DateUtils.getRelativeTimeSpanString(state.lastFullSyncAt),
                count,
            )
            else -> getString(R.string.settings_eh_cloud_favorites_sync_never)
        }
    }

    private fun syncCloudFavorites() {
        // If a background sync is running, sync() joins it (single flight) and reports its result.
        cloudFavoritesSync?.summary = getString(R.string.settings_eh_cloud_favorites_syncing)
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            // Not cancelled with the screen: a sync is cheap to finish and expensive to restart.
            val result = withContext(Dispatchers.IO + NonCancellable) {
                runCatching { GalleryListFeatures.sync() }.getOrNull()
            }
            val message = when (result) {
                is CloudFavoriteSyncer.Result.Complete -> R.string.settings_eh_cloud_favorites_sync_done
                is CloudFavoriteSyncer.Result.Partial -> R.string.settings_eh_cloud_favorites_sync_partial
                else -> R.string.settings_eh_cloud_favorites_sync_error
            }
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            if (isAdded) updateCloudFavoritesSummary()
        }
    }

    private fun updateListPreference(newValue: Int) {
        val isDetailMode = newValue == 0
        detailSize.isVisible = isDetailMode
        listThumbSize.isVisible = isDetailMode
        thumbSize.isVisible = !isDetailMode
        thumbShowTitle.isVisible = !isDetailMode
    }
}
