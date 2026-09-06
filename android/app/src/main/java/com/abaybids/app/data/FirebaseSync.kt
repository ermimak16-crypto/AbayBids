package com.abaybids.app.data

import android.content.Context
import android.util.Log
import com.abaybids.app.AbayBidsApp
import com.abaybids.app.notification.NotificationScheduler
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FirebaseSync — the bridge that "links" the Windows (Electron) app and the
 * Android app.
 *
 *  How it works
 *  ─────────────
 *   • Loads `assets/firebase-config.json` at process start.
 *   • If the config has real (non-empty) values, initializes a FirebaseApp
 *     programmatically — this is why we don't need the google-services Gradle
 *     plugin or a `google-services.json` file at build time.
 *   • Subscribes to FCM topic `tenders_new` so a tender added on Windows
 *     wakes this app up with a real status-bar notification (handled by
 *     `AbayMessagingService`), even when the app is fully closed.
 *   • Opens a real-time Firestore listener on the `tenders` collection. Any
 *     add/update/delete on the other device is reflected in this device's
 *     Room DB within a second or two — instant cross-device sync.
 *   • Exposes `pushTender()` / `pushDelete()` for the repository to call
 *     after every local CRUD, so a tender added on Android lands on the
 *     Windows app immediately (and vice-versa).
 *
 *  Graceful no-op
 *  ──────────────
 *   If `firebase-config.json` still has empty placeholder values (the user
 *   hasn't filled in their project credentials yet), every method here
 *   becomes a no-op. The app keeps working 100% locally — Room DB +
 *   AlarmManager reminders + status-bar notifications — exactly as before.
 */
object FirebaseSync {

    private const val TAG = "FirebaseSync"
    private const val ASSET = "firebase-config.json"
    private const val COLLECTION = "tenders"
    private const val TOPIC = "tenders_new"

    /** Stable document ID generator for tenders created locally on Android. */
    private val docIdTimeFormat = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var initialized = false
    @Volatile private var enabled = false
    @Volatile private var registration: ListenerRegistration? = null
    @Volatile private var lastSyncedIds: MutableSet<String> = mutableSetOf()

    /** True iff Firebase has real credentials loaded and is actively syncing. */
    val isActive: Boolean get() = enabled

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val cfg = readConfig(context) ?: run {
            Log.i(TAG, "firebase-config.json missing or unreadable — staying local.")
            return
        }
        if (cfg.apiKey.isBlank() || cfg.projectId.isBlank() || cfg.appId.isBlank()) {
            Log.i(TAG, "firebase-config.json has empty credentials — staying local. " +
                    "Fill in your Firebase project's apiKey/projectId/appId to link Windows + Android.")
            return
        }
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApiKey(cfg.apiKey)
                        .setProjectId(cfg.projectId)
                        .setApplicationId(cfg.appId)
                        .apply {
                            if (cfg.databaseUrl.isNotBlank()) setDatabaseUrl(cfg.databaseUrl)
                            if (cfg.messagingSenderId.isNotBlank()) setGcmSenderId(cfg.messagingSenderId)
                        }
                        .build(),
                    "abay-bids"
                )
            }
            enabled = true
            Log.i(TAG, "Firebase initialized for project ${cfg.projectId}. Linking Android ↔ Windows via Firestore.")
            subscribeToTopic()
            startFirestoreListener(context)
        } catch (e: Exception) {
            Log.e(TAG, "Firebase init failed — staying local.", e)
            enabled = false
        }
    }

    private fun subscribeToTopic() {
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
                .addOnCompleteListener { t ->
                    if (t.isSuccessful) Log.i(TAG, "Subscribed to FCM topic '$TOPIC'.")
                    else Log.w(TAG, "FCM topic subscribe failed", t.exception)
                }
        } catch (e: Exception) { Log.w(TAG, "subscribeToTopic", e) }
    }

    /**
     * Live mirror of the Firestore `tenders` collection into local Room.
     * Runs in the background for the entire app lifetime.
     */
    private fun startFirestoreListener(context: Context) {
        if (registration != null) return
        val db = FirebaseFirestore.getInstance()
        registration = db.collection(COLLECTION)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(500)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "Firestore listener error", err); return@addSnapshotListener }
                if (snap == null) return@addSnapshotListener
                scope.launch {
                    val dao = AppDatabase.get(context).tenderDao()
                    for (dc in snap.documentChanges) {
                        val fid = dc.document.id
                        when (dc.type) {
                            DocumentChange.Type.ADDED,
                            DocumentChange.Type.MODIFIED -> {
                                val mapped = mapToTender(fid, dc.document.data) ?: continue
                                val existingLocalId = dao.findLocalIdByFirebaseId(fid)
                                if (existingLocalId != null) {
                                    // update in place — preserve the local Long PK
                                    dao.update(mapped.copy(id = existingLocalId))
                                } else {
                                    // brand new on this device — autoGenerate a Long PK
                                    val newId = dao.upsert(mapped.copy(id = 0L))
                                    // Schedule local deadline + CPO alarms so the
                                    // newly-arrived tender gets reminders just like a
                                    // locally-created one.
                                    NotificationScheduler.scheduleTenderAlarms(
                                        context, mapped.copy(id = newId)
                                    )
                                }
                                lastSyncedIds += fid
                            }
                            DocumentChange.Type.REMOVED -> {
                                dao.deleteByFirebaseId(fid)
                                lastSyncedIds.remove(fid)
                            }
                            else -> Unit
                        }
                    }
                }
            }
    }

    // ─── Public write-through API used by TenderRepository ─────────────────────

    /** Push a local tender up to Firestore (called after every local save). */
    fun pushTender(t: Tender) {
        if (!enabled) return
        val fid = t.firebaseId.ifBlank { generateFirebaseId() }
        val toPush = if (t.firebaseId.isBlank()) t.copy(firebaseId = fid) else t
        if (t.firebaseId.isBlank()) {
            // First push for this tender — stamp the freshly-generated fid
            // back onto the local Room row so future updates use the same doc.
            scope.launch {
                AppDatabase.get(AbayBidsApp.instance).tenderDao().assignFirebaseId(t.id, fid)
            }
        }
        val data = tenderToMap(toPush)
        try {
            FirebaseFirestore.getInstance().collection(COLLECTION).document(fid)
                .set(data, SetOptions.merge())
                .addOnFailureListener { Log.w(TAG, "pushTender failed", it) }
        } catch (e: Exception) { Log.w(TAG, "pushTender", e) }
    }

    /** Delete a tender from Firestore (called after every local delete). */
    fun pushDelete(firebaseId: String) {
        if (!enabled || firebaseId.isBlank()) return
        try {
            FirebaseFirestore.getInstance().collection(COLLECTION).document(firebaseId)
                .delete()
                .addOnFailureListener { Log.w(TAG, "pushDelete failed", it) }
        } catch (e: Exception) { Log.w(TAG, "pushDelete", e) }
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun generateFirebaseId(): String {
        val ts = docIdTimeFormat.format(Date())
        val rand = (1..6).map { ('a'..'z') + ('0'..'9') }.flatten().shuffled().take(6).joinToString("")
        return "a_${ts}_$rand"
    }

    /** Maps a Firestore document to a Room Tender (ignoring unknown platform-specific extras). */
    private fun mapToTender(fid: String, data: Map<String, Any>): Tender? {
        return try {
            Tender(
                id = 0L, // let Room upsertRemote resolve by firebaseId
                customer = data["customer"] as? String ?: "",
                no = data["no"] as? String ?: "",
                product = data["product"] as? String ?: "Other",
                date = data["date"] as? String ?: "",
                value = (data["value"] as? Number)?.toDouble() ?: 0.0,
                status = data["status"] as? String ?: "Pending",
                cpo = data["cpo"] as? String ?: "No",
                cpoAmount = (data["cpoAmount"] as? Number)?.toDouble() ?: 0.0,
                cpoCollected = data["cpoCollected"] as? Boolean ?: false,
                reminderDays = (data["reminderDays"] as? Number)?.toInt() ?: 3,
                contact = data["contact"] as? String ?: "",
                phone = data["phone"] as? String ?: "",
                qty = (data["qty"] as? Number)?.toInt() ?: 1,
                remark = data["remark"] as? String ?: "",
                createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                repStatus = data["repStatus"] as? String ?: "Pending",
                vType = data["vType"] as? String ?: "Bus",
                fType = data["fType"] as? String ?: "Diesel",
                firebaseId = fid
            )
        } catch (e: Exception) {
            Log.w(TAG, "mapToTender failed for $fid", e); null
        }
    }

    /** Serializes a Tender to a Firestore map. Includes `updatedAt` for ordering. */
    private fun tenderToMap(t: Tender): Map<String, Any> {
        val m = HashMap<String, Any>()
        m["firebaseId"] = t.firebaseId
        m["customer"] = t.customer
        m["no"] = t.no
        m["product"] = t.product
        m["date"] = t.date
        m["value"] = t.value
        m["status"] = t.status
        m["cpo"] = t.cpo
        m["cpoAmount"] = t.cpoAmount
        m["cpoCollected"] = t.cpoCollected
        m["reminderDays"] = t.reminderDays
        m["contact"] = t.contact
        m["phone"] = t.phone
        m["qty"] = t.qty
        m["remark"] = t.remark
        m["createdAt"] = t.createdAt
        m["updatedAt"] = System.currentTimeMillis()
        m["origin"] = "android"
        // Cross-platform reporting fields (parity with the Windows dashboard).
        m["repStatus"] = t.repStatus
        m["vType"] = t.vType
        m["fType"] = t.fType
        return m
    }

    private data class Cfg(
        val apiKey: String,
        val projectId: String,
        val appId: String,
        val messagingSenderId: String,
        val databaseUrl: String,
    )

    private fun readConfig(context: Context): Cfg? {
        return try {
            val src = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val o = JSONObject(src)
            Cfg(
                apiKey = o.optString("apiKey"),
                projectId = o.optString("projectId"),
                appId = o.optString("appId"),
                messagingSenderId = o.optString("messagingSenderId"),
                databaseUrl = o.optString("databaseUrl"),
            )
        } catch (e: Exception) { null }
    }
}
