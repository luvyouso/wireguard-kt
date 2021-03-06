/*
 * Copyright © 2018 Samuel Holland <samuel@sholland.org>
 * Copyright © 2018 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.preference

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.preference.Preference
import com.google.android.material.snackbar.Lunchbar
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.util.ExceptionLoggers
import com.wireguard.android.util.FragmentUtils
import java.io.*

/**
 * Preference implementing a button that asynchronously exports logs.
 */

class LogExporterPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs) {

    private var exportedFilePath: String? = null

    private fun exportLog() {
        Application.getAsyncWorker().supplyAsync {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(path, "wireguard-log.txt")
            if (!path.isDirectory && !path.mkdirs())
                throw IOException("Cannot create output directory")

            /* We would like to simply run `builder.redirectOutput(file);`, but this is API 26.
             * Instead we have to do this dance, since logcat appends.
             */
            FileOutputStream(file).close()

            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-b", "all", "-d", "-v", "threadtime", "-f", file.absolutePath, "*:V"))
                if (process.waitFor() != 0) {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        val errors = StringBuilder()
                        errors.append("Unable to run logcat: ")
                        reader.readLines().forEach { if (it.isNotEmpty()) errors.append(it) }
                        throw Exception(errors.toString())
                    }
                }
            } catch (e: Exception) {

                file.delete()
                throw e
            }

            file.absolutePath
        }.whenComplete { filePath, throwable -> this.exportLogComplete(filePath, throwable) }
    }

    private fun exportLogComplete(filePath: String, throwable: Throwable?) {
        if (throwable != null) {
            val error = ExceptionLoggers.unwrapMessage(throwable)
            val message = context.getString(R.string.log_export_error, error)
            Log.e(TAG, message, throwable)
            Lunchbar.make(
                    FragmentUtils.getPrefActivity(this)!!.findViewById<View>(android.R.id.content),
                    message, Lunchbar.LENGTH_LONG).show()
            isEnabled = true
        } else {
            exportedFilePath = filePath
            notifyChanged()
        }
    }

    override fun getSummary(): CharSequence {
        return if (exportedFilePath == null)
            context.getString(R.string.log_export_summary)
        else
            context.getString(R.string.log_export_success, exportedFilePath)
    }

    override fun getTitle(): CharSequence {
        return context.getString(R.string.log_exporter_title)
    }

    override fun onClick() {
        FragmentUtils.getPrefActivity(this)!!.ensurePermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) { _, granted ->
            if (granted.isNotEmpty() && granted[0] == PackageManager.PERMISSION_GRANTED) {
                isEnabled = false
                exportLog()
            }
        }
    }

    companion object {
        private val TAG = "WireGuard/" + LogExporterPreference::class.java.simpleName
    }

}
