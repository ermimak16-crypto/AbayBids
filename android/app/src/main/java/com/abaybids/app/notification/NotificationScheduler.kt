package com.abaybids.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.abaybids.app.data.AppDatabase
import com.abaybids.app.data.Prefs
import com.abaybids.app.data.Tender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Schedules the actual OS-level alarms that wake the device and deliver
 * tender reminders to the Android notification bar — even when the app
 * is completely closed.
 *
 *  - Deadline reminder  -> fires `reminderDays` before the deadline
 *                           (per-tender, falling back to the global default)
 *  - CPO (bid-bond) collect reminder -> fires the day AFTER the deadline,
 *    if the bond hasn't been marked collected yet.
 *
 * Implementation notes:
 *   • AlarmManager.setExactAndAllowWhileIdle() is the only API that
 *     fires on time in Doze mode and does NOT require the user to grant
 *     the SCHEDULE_EXACT_ALARM permission on Android 12+ (USE_EXACT_ALARM
 *     auto-grants on Android 13+; we declare both).
 *   • Each alarm targets an explicit BroadcastReceiver (TenderAlarmReceiver)
 *     so the system can deliver it whether or not the app process is alive.
 *   • PendingIntents are FLAG_IMMUTABLE (Android 12+ requirement) and
 *     FLAG_UPDATE_CURRENT so rescheduling replaces the previous alarm.
 */
object NotificationScheduler {

    private const val TAG = "NotifScheduler"
    private const val WORK_PERIODIC_RESCAN = "abay_rescan_alarms"

    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Re-read every tender from the DB and re-schedule its alarms. */
    fun rescheduleAll(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val prefs = Prefs(context)
            if (!prefs.notificationsEnabled) {
                cancelAll(context)
                return@launch
            }
            val tenders = AppDatabase.get(context).tenderDao().all()
            tenders.forEach { scheduleTenderAlarms(context, it) }
            Log.i(TAG, "Scheduled alarms for ${tenders.size} tenders")
        }
    }

    /** Schedule (or reschedule) the deadline + CPO alarms for one tender. */
    fun scheduleTenderAlarms(context: Context, t: Tender) {
        val prefs = Prefs(context)
        if (!prefs.notificationsEnabled) return

        val days = if (t.reminderDays > 0) t.reminderDays else prefs.defaultReminderDays
        val deadlineMs = parseDateToMillis(t.date) ?: return

        // ---- Deadline reminder ----
        val reminderAt = deadlineMs - days.toLong() * DAY_MS
        scheduleExact(
            context,
            type = TYPE_DEADLINE,
            tenderId = t.id,
            triggerAtMillis = reminderAt,
            title = "Tender deadline in ${days}d",
            message = "${t.customer} (#${t.no}) — bid closes in ${days} days",
            channel = "tender_deadlines"
        )

        // ---- CPO (bid bond) collect reminder — fires day after deadline ----
        if (t.cpo.equals("Yes", true) && !t.cpoCollected) {
            val cpoAt = deadlineMs + DAY_MS
            scheduleExact(
                context,
                type = TYPE_CPO,
                tenderId = t.id,
                triggerAtMillis = cpoAt,
                title = "Collect bid bond (CPO)",
                message = "${t.customer} (#${t.no}) deadline has passed — collect your CPO back",
                channel = "tender_cpo"
            )
        }
    }

    /** Cancel alarms for a tender (e.g. when deleted). */
    fun cancelTenderAlarms(context: Context, tenderId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(piFor(context, TYPE_DEADLINE, tenderId))
        am.cancel(piFor(context, TYPE_CPO, tenderId))
    }

    private fun cancelAll(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            AppDatabase.get(context).tenderDao().all().forEach {
                cancelTenderAlarms(context, it.id)
            }
        }
    }

    private fun scheduleExact(
        context: Context,
        type: String,
        tenderId: Long,
        triggerAtMillis: Long,
        title: String,
        message: String,
        channel: String
    ) {
        // Don't schedule in the past (other than very recent — fire within 60s).
        val now = System.currentTimeMillis()
        if (triggerAtMillis < now - 60_000L) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Stash the payload inside the PendingIntent's intent extras; the
        // receiver reads them back when the alarm fires.
        val intent = Intent(context, TenderAlarmReceiver::class.java).apply {
            action = "com.abaybids.app.ALARM"
            putExtra(EXTRA_TENDER_ID, tenderId)
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_CHANNEL, channel)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode(type, tenderId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // setExactAndAllowWhileIdle = fires on time even in Doze, no permission prompt
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
        Log.d(TAG, "Scheduled $type alarm for tender $tenderId at ${java.util.Date(triggerAtMillis)}")
    }

    private fun piFor(context: Context, type: String, tenderId: Long): PendingIntent {
        val intent = Intent(context, TenderAlarmReceiver::class.java).apply {
            action = "com.abaybids.app.ALARM"
            putExtra(EXTRA_TENDER_ID, tenderId)
            putExtra(EXTRA_TYPE, type)
        }
        // No FLAG_NO_CREATE: we need a non-null PendingIntent to pass to
        // AlarmManager.cancel(). With matching Intent.filterEquals(), the
        // system cancels any existing alarm regardless of the PI instance.
        return PendingIntent.getBroadcast(
            context,
            requestCode(type, tenderId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Register a periodic background rescan so newly added tenders get alarms too. */
    fun schedulePeriodicRescan(context: Context) {
        val req = PeriodicWorkRequestBuilder<DeadlineRescanWorker>(
            6, TimeUnit.HOURS
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_PERIODIC_RESCAN,
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    // --- helpers ---
    private fun requestCode(type: String, tenderId: Long): Int =
        ((tenderId.toInt() shl 2) or when (type) {
            TYPE_DEADLINE -> 0b01
            TYPE_CPO -> 0b10
            else -> 0b00
        })

    private fun parseDateToMillis(iso: String): Long? {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.parse(iso)?.time
        } catch (e: Exception) { null }
    }

    fun buildDeepLinkUri(tenderId: Long): Uri =
        Uri.parse("abaybids://tender/$tenderId")

    const val EXTRA_TENDER_ID = "tender_id"
    const val EXTRA_TYPE = "alarm_type"
    const val EXTRA_TITLE = "title"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_CHANNEL = "channel"
    const val TYPE_DEADLINE = "deadline"
    const val TYPE_CPO = "cpo"

    private const val DAY_MS = 24L * 60L * 60L * 1000L
}
