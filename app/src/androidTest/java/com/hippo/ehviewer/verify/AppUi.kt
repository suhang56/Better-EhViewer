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
package com.hippo.ehviewer.verify

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.hippo.easyrecyclerview.LayoutManagerUtils
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.scene.GalleryAdapter
import com.hippo.scene.StageActivity
import java.util.regex.Pattern

/** Drives the real app UI (UiAutomator) and inspects its gallery list in-process. */
internal object AppUi {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice by lazy { UiDevice.getInstance(instrumentation) }
    private val context get() = instrumentation.targetContext
    val packageName: String get() = context.packageName

    const val LIST_MODE_LIST = 0
    const val LIST_MODE_GRID = 1

    /** Matches a view id of the app whatever the resource package (applicationId suffix) is. */
    fun res(id: String): BySelector = By.res(Pattern.compile(".*:id/$id"))

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    fun waitUntil(timeoutMs: Long, pollMs: Long = 300, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (runCatching(condition).getOrDefault(false)) return true
            if (SystemClock.uptimeMillis() > end) return false
            Thread.sleep(pollMs)
        }
    }

    /** Settings that skip sign-in, site selection and first-run dialogs, plus the feature switches. */
    fun prepare(hide: Boolean, fold: Boolean, listMode: Int) {
        Live.setUpGuest()
        Settings.putAppLinkVerifyTip(true)
        Settings.putGuideGallery(false)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Settings.KEY_HIDE_FAVORITED_GALLERIES, hide)
            .putBoolean(Settings.KEY_FOLD_GALLERY_VERSIONS, fold)
            .putString(Settings.KEY_FOLD_PREFERRED_LANGUAGE, Settings.FOLD_PREFERRED_LANGUAGE_AUTO)
            .putString(Settings.KEY_LIST_MODE, listMode.toString())
            .commit()
    }

    /** Opens [url] through the app's ACTION_VIEW handling, in a fresh task. */
    fun launch(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 30_000)
    }

    fun topScene(): Class<*>? = onMain { (EhApplication.application.topActivity as? StageActivity)?.topSceneClass }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }

    /** The gallery list RecyclerView of the top activity (main thread only). */
    private fun galleryRecycler(): RecyclerView? {
        val root = EhApplication.application.topActivity?.window?.decorView ?: return null
        return allViews(root).filterIsInstance<RecyclerView>().firstOrNull { it.adapter is GalleryAdapter && it.isAttachedToWindow }
    }

    data class Row(val position: Int, val gid: Long, val title: String, val state: GalleryAdapter.RowState?) {
        val otherVersions get() = state?.otherVersions ?: 0
        val expanded get() = state?.expanded == true
        val isOtherVersion get() = state?.isOtherVersion == true
    }

    /** The rows the adapter currently exposes (what the user can scroll through). */
    fun rows(): List<Row> = onMain {
        val adapter = galleryRecycler()?.adapter as? GalleryAdapter ?: return@onMain emptyList()
        (0 until adapter.itemCount).mapNotNull { p ->
            adapter.getDataAt(p)?.let { Row(p, it.gid, EhUtils.getSuitableTitle(it), adapter.getRowState(p)) }
        }
    }

    fun galleryAt(position: Int): GalleryInfo? = onMain { (galleryRecycler()?.adapter as? GalleryAdapter)?.getDataAt(position) }

    fun isListShown(): Boolean = onMain { galleryRecycler()?.isShown == true }

    fun firstVisible(): Int = onMain { galleryRecycler()?.layoutManager?.let { LayoutManagerUtils.getFirstVisibleItemPosition(it) } ?: -1 }

    fun lastVisible(): Int = onMain { galleryRecycler()?.layoutManager?.let { LayoutManagerUtils.getLastVisibleItemPosition(it) } ?: -1 }

    fun scrollTo(position: Int) {
        onMain { galleryRecycler()?.layoutManager?.let { LayoutManagerUtils.scrollToPositionWithOffset(it, position, 0) } }
        device.waitForIdle()
        Thread.sleep(800)
    }

    /** A finger swipe upwards (content moves up), like a user scrolling down the list. */
    fun swipeUp(fraction: Float = 0.5f) {
        val w = device.displayWidth
        val h = device.displayHeight
        val from = (h * (0.5f + fraction / 2)).toInt().coerceAtMost(h - 50)
        val to = (h * (0.5f - fraction / 2)).toInt().coerceAtLeast(50)
        device.swipe(w / 2, from, w / 2, to, 30)
        device.waitForIdle()
    }

    fun tipText(): String? = runCatching { device.findObject(res("tip"))?.text }.getOrNull()

    /** Waits for at least [min] rows; logs the tip text (error message) on failure. */
    fun waitForRows(report: VerifyReport, min: Int = 1, timeoutMs: Long = 90_000): Boolean {
        val ok = waitUntil(timeoutMs, 500) { rows().size >= min }
        if (!ok) report.fact("list did not show $min rows within ${timeoutMs / 1000} s; rows=${rows().size} tip=\"${tipText()}\" topScene=${topScene()?.simpleName}")
        Thread.sleep(1500)
        return ok
    }

    /** Card of the gallery whose title is exactly [title]. */
    fun card(title: String): UiObject2? = device.findObject(res("card").hasDescendant(res("title").text(title)))

    fun cardCount(title: String): Int = device.findObjects(res("card").hasDescendant(res("title").text(title))).size

    fun visibleTexts(): List<String> = device.findObjects(By.pkg(packageName)).mapNotNull { runCatching { it.text }.getOrNull() }.filter { it.isNotBlank() }

    /** Leaves the app (system settings) and comes back, so the list scene goes through onPause/onResume. */
    fun leaveAndReturn() {
        context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(3000)
        device.pressBack()
        if (!device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 8_000)) {
            device.pressRecentApps()
            Thread.sleep(1500)
            device.pressRecentApps()
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 8_000)
        }
        device.waitForIdle()
        Thread.sleep(1500)
    }
}

/** Reads logcat through the shell (UiAutomation), so it sees every process. */
internal object Logcat {
    private const val ENGINE_TAG = "EhEngine"

    fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** Writes a unique marker into the EhEngine log stream and returns it. */
    fun mark(): String {
        val marker = "VERIFY_MARK_${System.nanoTime()}"
        Log.i(ENGINE_TAG, marker)
        Thread.sleep(300)
        return marker
    }

    private fun linesSince(marker: String, filter: String): List<String> {
        val lines = shell("logcat -d -v time $filter").lines()
        val index = lines.indexOfLast { it.contains(marker) }
        return if (index < 0) lines else lines.drop(index + 1)
    }

    /** Gallery list requests (EhEngine logs every URL it fetches) made after [marker]. */
    fun listRequestsSince(marker: String): List<String> = linesSince(marker, "-s $ENGINE_TAG:V")
        .filter { it.contains("f_search=") && !it.contains("api.php") }
        .map { it.substringAfter("): ").trim() }

    /** FATAL EXCEPTION blocks of the app process logged after [marker]. */
    fun fatalSince(marker: String, packageName: String): List<String> {
        val lines = linesSince(marker, "-b main,crash -s $ENGINE_TAG:V AndroidRuntime:E")
        return lines.withIndex().filter { (i, line) ->
            line.contains("FATAL EXCEPTION") && lines.drop(i).take(3).any { it.contains(packageName) }
        }.map { (i, _) -> lines.drop(i).take(8).joinToString("\n") }
    }
}
