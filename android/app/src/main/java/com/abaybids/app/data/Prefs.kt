package com.abaybids.app.data

import android.content.Context
import android.content.SharedPreferences

/** Centralized prefs for notifications + theme. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("abay_prefs", Context.MODE_PRIVATE)

    var notificationsEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIF_ENABLED, true)
        set(v) { sp.edit().putBoolean(KEY_NOTIF_ENABLED, v).apply() }

    /** Default reminder window before deadline (days). Per-tender override still wins. */
    var defaultReminderDays: Int
        get() = sp.getInt(KEY_DEFAULT_REMINDER_DAYS, 3)
        set(v) { sp.edit().putInt(KEY_DEFAULT_REMINDER_DAYS, v).apply() }

    /** Theme mode: 0 = System Default, 1 = Light, 2 = Dark. Persists across sessions. */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME_MODE, 0)
        set(v) { sp.edit().putInt(KEY_THEME_MODE, v).apply() }

    companion object {
        private const val KEY_NOTIF_ENABLED = "notif_enabled"
        private const val KEY_DEFAULT_REMINDER_DAYS = "default_reminder_days"
        private const val KEY_THEME_MODE = "theme_mode"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}
