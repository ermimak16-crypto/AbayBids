package com.abaybids.app.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.abaybids.app.R
import com.abaybids.app.ui.TenderDetailActivity

/**
 * Fires when an AlarmManager alarm goes off. This receiver runs OUTSIDE
 * the app process — that is the whole point of using AlarmManager here:
 * it lets the OS deliver the reminder even when Abay Bids is fully closed.
 *
 * The receiver only:
 *   1. Reads the alarm payload from intent extras
 *   2. Builds a NotificationCompat
 *   3. Builds a PendingIntent to the TenderDetailActivity (deep link target)
 *   4. Posts it via NotificationManagerCompat
 *
 *  No app state, no Room, no coroutines. The class does all of it in <10ms.
 */
class TenderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val tenderId = intent.getLongExtra(NotificationScheduler.EXTRA_TENDER_ID, -1L)
        if (tenderId < 0) return
        val title = intent.getStringExtra(NotificationScheduler.EXTRA_TITLE) ?: return
        val message = intent.getStringExtra(NotificationScheduler.EXTRA_MESSAGE) ?: return
        val channel = intent.getStringExtra(NotificationScheduler.EXTRA_CHANNEL)
            ?: com.abaybids.app.AbayBidsApp.CHANNEL_DEADLINES

        if (!hasPostPermission(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; dropping reminder for tender $tenderId")
            return
        }

        // Build the deep-link intent that fires when the user taps the notification.
        val deepIntent = Intent(context, TenderDetailActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = NotificationScheduler.buildDeepLinkUri(tenderId)
            putExtra(TenderDetailActivity.EXTRA_TENDER_ID, tenderId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            context,
            tenderId.toInt(),
            deepIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_tender_notif)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(tenderId.toInt(), notif)
            Log.i(TAG, "Posted notification for tender $tenderId: $title")
        } catch (se: SecurityException) {
            Log.w(TAG, "No permission to notify", se)
        }
    }

    private fun hasPostPermission(context: Context): Boolean {
        // Pre-Android 13 doesn't need a runtime permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "TenderAlarmReceiver"
    }
}
