/*
 * Copyright © 2018 Samuel Holland <samuel@sholland.org>
 * Copyright © 2018 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.activity

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.SparseArray
import android.view.MenuItem
import androidx.annotation.Nullable
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.backend.WgQuickBackend
import java.util.*

/**
 * Interface for changing application-global persistent settings.
 */

class SettingsActivity : ThemeChangeAwareActivity() {
    private val permissionRequestCallbacks = SparseArray<(permissions: Array<String>, granted: IntArray) -> Unit>()
    private var permissionRequestCounter: Int = 0

    fun ensurePermissions(permissions: Array<String>, cb: (permissions: Array<String>, granted: IntArray) -> Unit) {
        val needPermissions = ArrayList<String>(permissions.size)
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
                needPermissions.add(permission)
        }
        if (needPermissions.isEmpty()) {
            val granted = IntArray(permissions.size)
            Arrays.fill(granted, PackageManager.PERMISSION_GRANTED)
            cb(permissions, granted)
            return
        }
        val idx = permissionRequestCounter++
        permissionRequestCallbacks.put(idx, cb)
        ActivityCompat.requestPermissions(this,
                needPermissions.toTypedArray(), idx)
    }

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (supportFragmentManager.findFragmentById(android.R.id.content) == null) {
            supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, SettingsFragment())
                    .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        val f = permissionRequestCallbacks.get(requestCode)
        if (f != null) {
            permissionRequestCallbacks.remove(requestCode)
            f(permissions, grantResults)
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, key: String?) {
            addPreferencesFromResource(R.xml.preferences)
            val wgQuickOnlyPrefs = arrayOf(preferenceManager.findPreference("tools_installer"),
                    preferenceManager.findPreference("restore_on_boot"))
            for (pref in wgQuickOnlyPrefs)
                pref.isVisible = false
            val screen = preferenceScreen
            Application.backendAsync.thenAccept { backend ->
                for (pref in wgQuickOnlyPrefs) {
                    if (backend is WgQuickBackend)
                        pref.isVisible = true
                    else
                        screen.removePreference(pref)
                }
            }
        }
    }
}
