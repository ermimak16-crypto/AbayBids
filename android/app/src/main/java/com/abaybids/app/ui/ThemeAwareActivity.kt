package com.abaybids.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * Base class for every screen in the app.
 *
 * Why this exists: switching the theme in Settings calls
 * [AppCompatDelegate.setDefaultNightMode], which only auto-recreates the
 * *currently active* Activity (Settings itself). Every other screen sitting
 * paused in the back stack — Dashboard, Tenders, Contracts, Calendar, etc. —
 * keeps its already-inflated views and never re-resolves the light/dark
 * color resources until the app process restarts. That's why the theme
 * toggle appeared to only affect the Settings page while the rest of the
 * app stayed on the old theme.
 *
 * Fix: remember which night mode was in effect when this screen last drew
 * itself, and if it no longer matches by the time the screen is resumed
 * (e.g. the user backed out of Settings after switching themes), recreate()
 * so it re-inflates with the correct colors — same fix, once, for every
 * screen instead of duplicating the check in each Activity.
 */
abstract class ThemeAwareActivity : AppCompatActivity() {

    private var appliedNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedNightMode = AppCompatDelegate.getDefaultNightMode()
    }

    override fun onResume() {
        super.onResume()
        val current = AppCompatDelegate.getDefaultNightMode()
        if (current != appliedNightMode) {
            appliedNightMode = current
            recreate()
        }
    }
}
