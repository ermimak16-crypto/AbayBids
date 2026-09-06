package com.abaybids.app.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * Every screen in the app extends this instead of AppCompatActivity directly.
 *
 * WHY THIS EXISTS:
 * AppCompatDelegate.setDefaultNightMode(...) (called from SettingsActivity
 * when the user picks Light/Dark/System) only auto-recreates the activity
 * that is on screen *at the moment it's called*. Any other activity that is
 * still alive underneath it in the back stack (e.g. the Dashboard, which is
 * almost always there since Settings is opened from it) is NOT recreated
 * automatically. That activity keeps rendering with whatever theme/colors it
 * originally inflated — which is exactly the bug where switching to Light
 * mode only visibly updates the Settings screen, and going "back" to the
 * Dashboard (or any other screen) still shows the old dark colors.
 *
 * The fix: every activity remembers which night-mode value was active when
 * it was created/resumed. If that value doesn't match the delegate's current
 * value in onResume() (e.g. because the user changed it on another screen
 * and then navigated back here), we recreate() this activity so it re-reads
 * the correct color resources (values/ vs values-night/) from scratch.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var appliedNightMode: Int = AppCompatDelegate.getDefaultNightMode()

    override fun onResume() {
        super.onResume()
        val currentNightMode = AppCompatDelegate.getDefaultNightMode()
        if (appliedNightMode != currentNightMode) {
            appliedNightMode = currentNightMode
            recreate()
        }
    }
}
