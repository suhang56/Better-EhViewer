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

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Human-readable verification report.
 *
 * Every line goes to logcat (tag [TAG]) and is appended to [FILE_NAME] in the
 * app's cache dir and external files dir, so CI can print the results even if
 * the logcat buffer wrapped. Lines are prefixed with the test name.
 *
 * [check] records a failure without stopping the test, so one run reports as
 * many facts as possible; [assertAll] fails the test at the end.
 */
class VerifyReport(private val test: String) {
    private val failures = ArrayList<String>()

    fun fact(message: String) = write("$test: $message")

    fun check(ok: Boolean, message: String): Boolean {
        write("$test: ${if (ok) "PASS" else "FAIL"} $message")
        if (!ok) failures += message
        return ok
    }

    /** Stops the test right away, after logging [message]. */
    fun fail(message: String, cause: Throwable? = null): Nothing {
        write("$test: FAIL $message")
        failures += message
        throw AssertionError("$test: $message", cause)
    }

    fun assertAll() {
        if (failures.isEmpty()) {
            write("$test: all checks passed")
            return
        }
        throw AssertionError("$test: ${failures.size} check(s) failed:\n" + failures.joinToString("\n") { "- $it" })
    }

    companion object {
        const val TAG = "VERIFY"
        const val FILE_NAME = "verify-report.txt"

        @Synchronized
        fun write(line: String) {
            // Logcat truncates long lines.
            line.chunked(3000).forEach { Log.i(TAG, it) }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            listOfNotNull(context.cacheDir, context.getExternalFilesDir(null)).forEach { dir ->
                runCatching { File(dir, FILE_NAME).appendText(line + "\n") }
            }
        }
    }
}

/** Logs the start and the outcome of every test into the report. */
class VerifyWatcher : TestWatcher() {
    private fun name(d: Description) = "${d.testClass?.simpleName}.${d.methodName}"

    override fun starting(description: Description) {
        VerifyReport.write("${name(description)}: ===== START")
    }

    override fun succeeded(description: Description) {
        VerifyReport.write("${name(description)}: ===== PASSED")
    }

    override fun failed(e: Throwable, description: Description) {
        val message = "${e.javaClass.simpleName}: ${e.message}".take(2500)
        VerifyReport.write("${name(description)}: ===== FAILED $message")
    }
}
