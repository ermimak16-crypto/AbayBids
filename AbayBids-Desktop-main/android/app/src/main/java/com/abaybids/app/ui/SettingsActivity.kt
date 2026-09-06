package com.abaybids.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.abaybids.app.AbayBidsApp
import com.abaybids.app.R
import com.abaybids.app.data.Prefs
import com.abaybids.app.databinding.ActivitySettingsBinding
import com.abaybids.app.notification.NotificationScheduler
import com.abaybids.app.notification.TenderAlarmReceiver
import kotlinx.coroutines.launch

class SettingsActivity : ThemeAwareActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val prefs by lazy { Prefs(this) }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // If granted, re-arm alarms so they can now actually post.
            if (granted) NotificationScheduler.rescheduleAll(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        b.toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_arrow_back)
        b.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = getString(R.string.title_settings)

        // --- Theme selector (Light / Dark / System Default) ---
        val themeOptions = arrayOf("System Default", "Light", "Dark")
        b.themeSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, themeOptions
        )
        b.themeSpinner.setSelection(prefs.themeMode)
        b.themeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position == prefs.themeMode) return // no change
                prefs.themeMode = position
                // Apply the theme immediately — AppCompatDelegate recreates the activity
                val mode = when (position) {
                    Prefs.THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    Prefs.THEME_DARK  -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else               -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // --- Notifications toggle ---
        b.notifSwitch.isChecked = prefs.notificationsEnabled
        b.notifSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.notificationsEnabled = checked
            if (checked) {
                // Ensure runtime permission (Android 13+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                NotificationScheduler.rescheduleAll(this)
            } else {
                // Cancel all alarms (notifications disabled)
                NotificationScheduler.rescheduleAll(this) // reschedule reads the pref and will cancel
            }
        }

        // --- Reminder window slider ---
        b.reminderSlider.value = prefs.defaultReminderDays.toFloat()
        b.reminderSub.text = getString(R.string.settings_default_reminder_sub_days, prefs.defaultReminderDays)
        b.reminderSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                val days = value.toInt()
                b.reminderSub.text = getString(R.string.settings_default_reminder_sub_days, days)
            }
        }
        b.reminderSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                prefs.defaultReminderDays = slider.value.toInt()
                NotificationScheduler.rescheduleAll(this@SettingsActivity)
            }
        })

        // --- Battery optimization (required for exact alarms in Doze) ---
        b.batteryCard.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${packageName}")
            }
            try { startActivity(intent) } catch (_: Exception) {}
        }

        // --- Test notification (fires in 5s so user sees it land in the bar) ---
        b.testCard.setOnClickListener {
            scheduleTestNotification()
        }
    }

    /** Schedule a 5-second-out alarm so the user can see the receiver post to the bar. */
    private fun scheduleTestNotification() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, TenderAlarmReceiver::class.java).apply {
            action = "com.abaybids.app.ALARM"
            putExtra(NotificationScheduler.EXTRA_TENDER_ID, 9999L)
            putExtra(NotificationScheduler.EXTRA_TYPE, "test")
            putExtra(NotificationScheduler.EXTRA_TITLE, getString(R.string.notif_test_title))
            putExtra(NotificationScheduler.EXTRA_MESSAGE, getString(R.string.notif_test_body))
            putExtra(NotificationScheduler.EXTRA_CHANNEL, AbayBidsApp.CHANNEL_DEADLINES)
        }
        val pi = PendingIntent.getBroadcast(
            this, 9999, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5000L,
            pi
        )
        // Make sure we're allowed to post (Android 13+ runtime permission).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        android.widget.Toast.makeText(this, "Test reminder scheduled — check your status bar in 5s", android.widget.Toast.LENGTH_LONG).show()
    }
}
