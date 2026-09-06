package com.abaybids.app.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodically (every 6h) re-reads the tender DB and reschedules every
 * alarm. This is the safety net: it makes sure newly added tenders get
 * alarms even if the user adds them and then never reopens the dashboard,
 * and it self-heals if the OS has cleared alarms for any reason.
 *
 * It does NOT itself post notifications — only AlarmManager does that,
 * because we want exact, on-time delivery regardless of app state.
 */
class DeadlineRescanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            NotificationScheduler.rescheduleAll(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
