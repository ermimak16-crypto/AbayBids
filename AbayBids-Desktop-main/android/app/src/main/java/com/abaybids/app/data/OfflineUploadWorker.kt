package com.abaybids.app.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import java.util.concurrent.TimeUnit

/**
 * Background worker that uploads pending documents when the device is online.
 *
 * - Scans the Room `documents` table for records with status PENDING_UPLOAD
 *   or UPLOAD_FAILED.
 * - Uploads each one to Firebase Storage + Firestore via DocumentSync.
 * - Runs every 15 minutes AND whenever network connectivity is restored.
 *
 * This is what makes offline file attachment work: the user attaches a file
 * while offline → it's saved locally as PENDING_UPLOAD → this worker picks
 * it up when internet returns → uploads → the Windows app receives it.
 */
class OfflineUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "OfflineUploadWorker"
        private const val WORK_NAME = "abay_doc_upload"

        /** Schedule the periodic upload worker (every 15 min, only when online). */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = PeriodicWorkRequestBuilder<OfflineUploadWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
            Log.i(TAG, "Offline upload worker scheduled (every 15 min when online).")
        }

        /** Run once immediately (e.g. when the app opens or user attaches a file). */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = androidx.work.OneTimeWorkRequestBuilder<OfflineUploadWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(req)
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!FirebaseSync.isActive) {
            Log.i(TAG, "Firebase not active — skipping upload cycle.")
            return Result.success()
        }

        val dao = AppDatabase.get(context).documentDao()
        val pending = dao.getByStatus(Document.STATUS_PENDING_UPLOAD) +
                      dao.getByStatus(Document.STATUS_FAILED)

        if (pending.isEmpty()) return Result.success()

        Log.i(TAG, "Found ${pending.size} pending/failed documents to upload.")

        var allOk = true
        for (doc in pending) {
            val ok = DocumentSync.uploadDocument(context, doc)
            if (!ok) allOk = false
        }

        return if (allOk) Result.success() else Result.retry()
    }
}
