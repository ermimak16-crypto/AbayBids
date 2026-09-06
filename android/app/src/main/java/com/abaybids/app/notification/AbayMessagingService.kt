package com.abaybids.app.notification

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.abaybids.app.AbayBidsApp
import com.abaybids.app.R
import com.abaybids.app.data.AppDatabase
import com.abaybids.app.data.Prefs
import com.abaybids.app.ui.MainActivity
import com.abaybids.app.ui.TenderDetailActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AbayMessagingService — receives FCM data messages sent from the Windows
 * Electron app (via the optional Cloud Function in `cloud-functions/`, or
 * from any other writer that uses the same Firebase project).
 *
 * When a message arrives with `tenderId` in its data payload:
 *   1. Posts a real status-bar notification (high-importance channel).
 *   2. The notification's tap-action is a PendingIntent to TenderDetailActivity
 *      via the deep link `abaybids://tender/{localId}` (or `abaybids://tender/firebase/{fid}`
 *      if the row hasn't synced into Room yet — TenderDetailActivity handles both).
 *
 * This works even when the app is fully closed: FCM wakes the service, which
 * in turn posts the notification. This is FREE (Firebase Cloud Messaging has
 * no per-message fee) and is the canonical pattern for cross-device push on
 * Android without writing a custom server.
 *
 * Note: the existing AlarmManager reminders (NotificationScheduler) are a
 * separate, fully-local mechanism for deadline reminders — they keep working
 * with no Firebase project configured at all.
 */
class AbayMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val fid = data["firebaseId"] ?: data["tenderId"] ?: ""
        val title = data["title"] ?: "Abay Bids"
        val body = data["body"] ?: data["message"] ?: "New tender activity"
        val channelId = data["channel"] ?: AbayBidsApp.CHANNEL_DEADLINES
        val type = data["type"] ?: "deadline"

        if (!Prefs(this).notificationsEnabled) return

        // Try to resolve the local Room Long id so the deep link points to the
        // already-stored row. If the row hasn't synced yet (the FCM message
        // arrives before the Firestore listener upserts it), use the firebase
        // path segment instead — TenderDetailActivity resolves it on open.
        scope.launch {
            val localId: Long? = if (fid.isNotBlank()) {
                AppDatabase.get(this@AbayMessagingService).tenderDao().byFirebaseIdOnce(fid)?.id
            } else null
            postNotification(
                title = title,
                body = body,
                channelId = channelId,
                deepLink = buildDeepLink(localId, fid),
                tag = "$type:$fid"
            )
        }
    }

    /** Called by FCM when the device's registration token rotates. */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Tokens are auto-managed by the Firebase SDK. We re-subscribe to the
        // tender topic so cross-device pushes keep landing after a rotation.
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .subscribeToTopic("tenders_new")
        } catch (_: Exception) { }
    }

    private fun buildDeepLink(localId: Long?, fid: String): Uri {
        return when {
            localId != null && localId > 0 -> Uri.parse("abaybids://tender/$localId")
            fid.isNotBlank() -> Uri.parse("abaybids://tender/firebase/$fid")
            else -> Uri.parse("abaybids://tender/0")
        }
    }

    private fun postNotification(
        title: String,
        body: String,
        channelId: String,
        deepLink: Uri,
        tag: String
    ) {
        val intent = Intent(this, TenderDetailActivity::class.java).apply {
            data = deepLink
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, tag.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_tender_notif)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        try {
            NotificationManagerCompat.from(this).notify(tag.hashCode(), builder.build())
        } catch (se: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+ — silently drop.
        }
    }
}
