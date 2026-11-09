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
package com.hippo.ehviewer.ui.scene

import android.annotation.SuppressLint
import android.content.res.Resources
import android.text.TextUtils
import android.util.TypedValue.COMPLEX_UNIT_PX
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IntDef
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.hippo.drawable.TriangleDrawable
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.download.DownloadManager as downloadManager
import com.hippo.ehviewer.widget.TileThumb
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager
import com.hippo.yorozuya.ViewUtils

@SuppressLint("InflateParams")
internal abstract class GalleryAdapter(
    private val mInflater: LayoutInflater,
    private val mResources: Resources,
    private val mRecyclerView: RecyclerView,
    type: Int,
    showFavourited: Boolean,
) : RecyclerView.Adapter<GalleryHolder>() {
    private val mLayoutManager: AutoStaggeredGridLayoutManager =
        AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL)
    private val mPaddingTopSB: Int =
        mResources.getDimensionPixelOffset(R.dimen.gallery_padding_top_search_bar)
    private val mListThumbWidth: Int
    private val mListThumbHeight: Int
    private val mShowFavourited: Boolean = showFavourited
    private var mListDecoration: MarginItemDecoration? = null
    private var mGirdDecoration: MarginItemDecoration? = null
    private var mType = TYPE_INVALID

    var type: Int
        get() = mType

        @SuppressLint("NotifyDataSetChanged")
        set(type) {
            if (type == mType) {
                return
            }
            mType = type
            val recyclerView = mRecyclerView
            when (type) {
                TYPE_LIST -> {
                    mLayoutManager.setColumnSize(Settings.detailSize)
                    mLayoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE)
                    if (null != mGirdDecoration) {
                        recyclerView.removeItemDecoration(mGirdDecoration!!)
                    }
                    if (null == mListDecoration) {
                        val interval =
                            mResources.getDimensionPixelOffset(R.dimen.gallery_list_interval)
                        val paddingH =
                            mResources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h)
                        val paddingV =
                            mResources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v)
                        mListDecoration =
                            MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
                    }
                    recyclerView.addItemDecoration(mListDecoration!!)
                    notifyDataSetChanged()
                }
                TYPE_GRID -> {
                    mLayoutManager.setColumnSize(Settings.thumbSize)
                    mLayoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_SUITABLE_SIZE)
                    if (null != mListDecoration) {
                        recyclerView.removeItemDecoration(mListDecoration!!)
                    }
                    if (null == mGirdDecoration) {
                        val interval =
                            mResources.getDimensionPixelOffset(R.dimen.gallery_grid_interval)
                        val paddingH =
                            mResources.getDimensionPixelOffset(R.dimen.gallery_grid_margin_h)
                        val paddingV =
                            mResources.getDimensionPixelOffset(R.dimen.gallery_grid_margin_v)
                        mGirdDecoration =
                            MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
                    }
                    recyclerView.addItemDecoration(mGirdDecoration!!)
                    notifyDataSetChanged()
                }
            }
        }

    init {
        mRecyclerView.adapter = this
        mRecyclerView.layoutManager = mLayoutManager
        val calculator =
            mInflater.inflate(R.layout.item_gallery_list_thumb_height, null)
        ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT)
        mListThumbHeight = calculator.measuredHeight
        mListThumbWidth = mListThumbHeight * 2 / 3
        this.type = type
        adjustPaddings()
    }

    private fun adjustPaddings() {
        val recyclerView = mRecyclerView
        recyclerView.setPadding(
            recyclerView.paddingLeft,
            recyclerView.paddingTop + mPaddingTopSB,
            recyclerView.paddingRight,
            recyclerView.paddingBottom,
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryHolder {
        val layoutId = when (viewType) {
            TYPE_LIST -> R.layout.item_gallery_list
            TYPE_GRID -> R.layout.item_gallery_grid
            else -> throw IllegalStateException("Unexpected value: $viewType")
        }
        val holder = GalleryHolder(mInflater.inflate(layoutId, parent, false))

        when (viewType) {
            TYPE_LIST -> {
                val lp = holder.thumb.layoutParams
                lp.width = mListThumbWidth
                lp.height = mListThumbHeight
                holder.thumb.layoutParams = lp
                holder.title.maxLines = if (Settings.listTitleSingleLine) 1 else 2
            }
            TYPE_GRID -> {
                val columnWidth = Settings.thumbSize
                val textSize = columnWidth / 14
                val lp = holder.category.layoutParams
                lp.width = columnWidth / 5
                lp.height = (lp.width * 0.75).toInt()
                holder.category.layoutParams = lp
                holder.simpleLanguage.setTextSize(COMPLEX_UNIT_PX, textSize.toFloat())
                holder.pages.setTextSize(COMPLEX_UNIT_PX, textSize.toFloat())
                holder.title.setTextSize(COMPLEX_UNIT_PX, textSize.toFloat())
                holder.title.maxLines = 2
                holder.versions?.let {
                    it.setTextSize(COMPLEX_UNIT_PX, textSize.toFloat())
                    it.maxWidth = columnWidth * 3 / 4
                }
            }
        }
        holder.card.setOnClickListener {
            onItemClick(
                holder.itemView,
                holder.bindingAdapterPosition,
            )
        }
        holder.card.setOnLongClickListener {
            onItemLongClick(
                holder.itemView,
                holder.bindingAdapterPosition,
            )
        }
        holder.versions?.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onToggleVersions(position)
        }
        return holder
    }

    abstract fun onItemClick(view: View, position: Int)
    abstract fun onItemLongClick(view: View, position: Int): Boolean
    override fun getItemViewType(position: Int): Int = mType

    abstract fun getDataAt(position: Int): GalleryInfo?

    /**
     * Folding / hiding state of a row. Null (the default) for lists that show
     * the raw data, such as favourites, history and downloads.
     */
    open fun getRowState(position: Int): RowState? = null

    /** The "other versions" entry of the row at [position] was clicked. */
    open fun onToggleVersions(position: Int) {}

    /**
     * @param otherVersions number of other versions folded with this gallery
     * @param expanded the other versions are currently displayed
     * @param isOtherVersion this row is one of the other versions of an expanded group
     * @param favoriteUnknown the cloud favourite state of this gallery is unknown
     */
    data class RowState(
        val otherVersions: Int,
        val expanded: Boolean,
        val isOtherVersion: Boolean,
        val favoriteUnknown: Boolean,
    )

    private fun bindRowState(holder: GalleryHolder, state: RowState?) {
        val versions = holder.versions ?: return
        when {
            state == null -> versions.visibility = View.GONE
            state.isOtherVersion -> {
                versions.visibility = View.VISIBLE
                versions.isClickable = false
                versions.text = mResources.getString(R.string.gallery_list_other_version)
                versions.alpha = .6f
            }
            state.otherVersions > 0 -> {
                versions.visibility = View.VISIBLE
                versions.isClickable = true
                versions.alpha = 1f
                val n = state.otherVersions
                versions.text = when {
                    // Grid cells are narrow: short badge text.
                    mType == TYPE_GRID && state.expanded -> mResources.getString(R.string.gallery_list_collapse_versions_short)
                    mType == TYPE_GRID -> mResources.getQuantityString(R.plurals.gallery_list_versions_badge, n, n)
                    state.expanded -> mResources.getQuantityString(R.plurals.gallery_list_collapse_versions, n, n)
                    else -> mResources.getQuantityString(R.plurals.gallery_list_expand_versions, n, n)
                }
            }
            else -> versions.visibility = View.GONE
        }
        holder.favourited?.let { heart ->
            if (state?.favoriteUnknown == true && heart.visibility != View.VISIBLE) {
                // Cloud state unknown (not synced): show a faded heart instead of claiming "not favourited".
                heart.visibility = View.VISIBLE
                heart.alpha = .3f
                heart.contentDescription = mResources.getString(R.string.gallery_list_favorite_unknown)
            } else if (heart.visibility == View.VISIBLE) {
                heart.alpha = 1f
                heart.contentDescription = null
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: GalleryHolder, position: Int) {
        val gi = getDataAt(position) ?: return
        when (mType) {
            TYPE_LIST -> {
                holder.thumb.load(getThumbKey(gi.gid), gi.thumbUrl!!, hardware = false)
                holder.title.text = EhUtils.getSuitableTitle(gi)
                holder.uploader!!.alpha = if (gi.disowned) .5f else 1f
                if (TextUtils.isEmpty(gi.uploader)) {
                    holder.uploader.text = null
                    holder.uploader.visibility = View.GONE
                } else {
                    holder.uploader.text = gi.uploader
                    holder.uploader.visibility = View.VISIBLE
                }
                holder.note!!.text = gi.favoriteNote
                holder.rating.rating = gi.rating
                val category = holder.category
                val newCategoryText = EhUtils.getCategory(gi.category)
                if (newCategoryText != category.text.toString()) {
                    category.text = newCategoryText
                    category.setBackgroundColor(EhUtils.getCategoryColor(gi.category))
                }
                holder.posted!!.text = gi.posted
                if (gi.pages == 0 || !Settings.showGalleryPages) {
                    holder.pages.text = null
                    holder.pages.visibility = View.GONE
                } else {
                    holder.pages.text = gi.pages.toString() + "P"
                    holder.pages.visibility = View.VISIBLE
                }
                if (TextUtils.isEmpty(gi.simpleLanguage)) {
                    holder.simpleLanguage.text = null
                    holder.simpleLanguage.visibility = View.GONE
                } else {
                    holder.simpleLanguage.text = gi.simpleLanguage
                    holder.simpleLanguage.visibility = View.VISIBLE
                }
                holder.favourited!!.visibility =
                    if (mShowFavourited && gi.favoriteSlot >= -1 && gi.favoriteSlot <= 10) View.VISIBLE else View.GONE
                holder.downloaded!!.visibility =
                    if (downloadManager.containDownloadInfo(gi.gid)) View.VISIBLE else View.GONE
            }
            TYPE_GRID -> {
                (holder.thumb as TileThumb).setThumbSize(gi.thumbWidth, gi.thumbHeight)
                holder.thumb.load(getThumbKey(gi.gid), gi.thumbUrl!!, hardware = false)
                if (Settings.thumbShowTitle) {
                    holder.title.text = EhUtils.getSuitableTitle(gi)
                    holder.title.visibility = View.VISIBLE
                    holder.rating.rating = gi.rating
                    holder.rating.visibility = View.VISIBLE
                    if (gi.pages == 0 || !Settings.showGalleryPages) {
                        holder.pages.text = null
                        holder.pages.visibility = View.GONE
                    } else {
                        holder.pages.text = gi.pages.toString() + "P"
                        holder.pages.visibility = View.VISIBLE
                    }
                } else {
                    holder.title.text = null
                    holder.title.visibility = View.GONE
                    holder.rating.visibility = View.GONE
                    holder.pages.text = null
                    holder.pages.visibility = View.GONE
                }
                val category: View = holder.category
                var drawable = category.background
                val color = EhUtils.getCategoryColor(gi.category)
                if (drawable !is TriangleDrawable) {
                    drawable = TriangleDrawable(color)
                    category.background = drawable
                } else {
                    drawable.setColor(color)
                }
                holder.simpleLanguage.text = gi.simpleLanguage
            }
        }
        bindRowState(holder, getRowState(position))
        // Update transition name
        ViewCompat.setTransitionName(
            holder.thumb,
            TransitionNameFactory.getThumbTransitionName(gi.gid),
        )
    }

    @IntDef(TYPE_LIST, TYPE_GRID)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Type
    companion object {
        const val TYPE_INVALID = -1
        const val TYPE_LIST = 0
        const val TYPE_GRID = 1
    }
}
