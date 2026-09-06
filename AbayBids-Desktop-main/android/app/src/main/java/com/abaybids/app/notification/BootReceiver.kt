package com.abaybids.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a device reboot — or after the app is updated — all in-flight
 * AlarmManager alarms are wiped by the OS. We catch BOOT_COMPLETED
 * (and MY_PACKAGE_REPLACED, LOCKED_BOOT_COMPLETED) here and re-arm every
 * tender alarm so the user keeps getting reminders.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // Hand off to the scheduler (which uses its own coroutine scope).
                NotificationScheduler.schedulePeriodicRescan(context)
                NotificationScheduler.rescheduleAll(context)
            }
        }
    }
}
