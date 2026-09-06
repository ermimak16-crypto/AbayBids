package com.abaybids.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.google.android.material.color.DynamicColors
import com.abaybids.app.data.AppDatabase
import com.abaybids.app.data.DocumentSync
import com.abaybids.app.data.FirebaseSync
import com.abaybids.app.data.OfflineUploadWorker
import com.abaybids.app.data.Prefs
import com.abaybids.app.notification.NotificationScheduler

/**
 * App entry point. Crash-safe initialization order:
 *   1. Register the notification channel that all reminders post to.
 *   2. Let Room seed the local DB on first run so the dashboard isn't empty.
 *   3. Schedule the periodic background scan + per-tender alarms.
 *   4. Initialize the Firebase link to the Windows app.
 *
 * IMPORTANT: We do NOT call `WorkManager.initialize(this, ...)` manually.
 * WorkManager 2.6+ auto-initializes via `androidx.startup.WorkManagerInitializer`
 * (declared in the merged manifest). Since this Application class implements
 * `Configuration.Provider`, the auto-initialized WorkManager picks up our
 * `workManagerConfiguration` automatically. Calling `WorkManager.initialize`
 * here would throw `IllegalStateException: WorkManager is already initialized`
 * — that was the original crash that prevented the app from opening at all.
 *
 * Every step below is wrapped in try/catch so that a single failing
 * initialization can never prevent the app from opening. If something goes
 * wrong we log it and continue, so the user always sees a working dashboard
 * and only the affected feature (e.g. sync) is unavailable.
 */
class AbayBidsApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // On Android 12+, tint the app's Material components (buttons, FAB,
        // selected nav items, etc.) with colors derived from the user's
        // wallpaper — the same "Material You" personalization every other
        // native app on the device gets. Falls back to our fixed brand gold
        // on older Android versions, so nothing changes there.
        safe("dynamic_color") { DynamicColors.applyToActivitiesIfAvailable(this) }
        // Apply the saved theme BEFORE any activity is created so the entire
        // app (every screen, dialog, drawer, notification) uses the right mode.
        applyTheme()
        safe("channels") { createNotificationChannels() }
        safe("seed_db") { AppDatabase.get(this).seedIfEmpty() }
        // WorkManager picks up its config from Configuration.Provider below —
        // do NOT call WorkManager.initialize() here, it is already auto-init'd
        // by androidx.startup and a second call throws IllegalStateException.
        safe("periodic_rescan") { NotificationScheduler.schedulePeriodicRescan(this) }
        safe("reschedule_alarms") { NotificationScheduler.rescheduleAll(this) }
        safe("firebase") { FirebaseSync.init(this) }
        safe("doc_sync") { DocumentSync.init(this) }
        safe("doc_upload_worker") { OfflineUploadWorker.schedule(this) }
    }

    private inline fun safe(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("AbayBidsApp", "init step '$tag' failed", t)
        }
    }

    /** Apply the user-selected theme (System / Light / Dark) globally. */
    private fun applyTheme() {
        val prefs = Prefs(this)
        val mode = when (prefs.themeMode) {
            Prefs.THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            Prefs.THEME_DARK  -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else               -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        NotificationChannel(
            CHANNEL_DEADLINES,
            getString(R.string.channel_deadlines_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_deadlines_desc)
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
            nm.createNotificationChannel(this)
        }

        NotificationChannel(
            CHANNEL_CPO,
            getString(R.string.channel_cpo_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_cpo_desc)
            nm.createNotificationChannel(this)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    companion object {
        private const val TAG = "AbayBidsApp"
        const val CHANNEL_DEADLINES = "tender_deadlines"
        const val CHANNEL_CPO = "tender_cpo"

        @Volatile
        lateinit var instance: AbayBidsApp
            private set
    }
}
