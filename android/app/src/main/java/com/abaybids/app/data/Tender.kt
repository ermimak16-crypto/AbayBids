package com.abaybids.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single tender/bid the user is tracking. Mirrors the localStorage schema
 * of the existing Windows web app (`dash-app.js`) for cross-platform parity.
 *
 *  - `date` holds the BID DEADLINE (it is `daysUntil(t.date)` in the web app)
 *  - `status` is one of: Participated | Pending | Won | Lost | Not Participated
 *  - `cpo` ("Yes"/"No") flags whether a bid bond / CPO was deposited and
 *    must be collected back after the deadline.
 *  - `reminderDays` is how many days before the deadline to fire a reminder.
 *
 *  Windows-parity fields:
 *  - `repStatus` — reporting status (Pending / Not Participated / Canceled),
 *    separate from the bid `status` so we can track office reporting state.
 *  - `vType`     — vehicle type (Bus / Minibus / Pickup / Automobile); only
 *    meaningful when `product` is "Vehicles" / "Vehicle Purchase".
 *  - `fType`     — fuel type (Diesel / Petrol / Electric); same as above.
 */
@Entity(tableName = "tenders")
data class Tender(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "customer")     val customer: String,        // Client / Organization
    @ColumnInfo(name = "no")           val no: String = "",         // Tender number
    @ColumnInfo(name = "product")      val product: String = "Other", // Category
    @ColumnInfo(name = "date")         val date: String,           // Bid deadline (ISO yyyy-MM-dd)
    @ColumnInfo(name = "value")        val value: Double = 0.0,    // Est. value (ETB)
    @ColumnInfo(name = "status")       val status: String = "Pending", // Participated/Pending/Won/Lost/Not Participated
    @ColumnInfo(name = "cpo")          val cpo: String = "No",     // "Yes" if bid bond deposited
    @ColumnInfo(name = "cpo_amount")   val cpoAmount: Double = 0.0,
    @ColumnInfo(name = "cpo_collected") val cpoCollected: Boolean = false,
    @ColumnInfo(name = "reminder_days") val reminderDays: Int = 3,
    @ColumnInfo(name = "contact")      val contact: String = "",
    @ColumnInfo(name = "phone")        val phone: String = "",
    @ColumnInfo(name = "qty")          val qty: Int = 1,
    @ColumnInfo(name = "remark")       val remark: String = "",

    /** Reporting status — tracks whether the bid result was reported to management. */
    @ColumnInfo(name = "rep_status", defaultValue = "Pending")
    val repStatus: String = "Pending",

    /** Vehicle type — Bus / Minibus / Pickup / Automobile (only for vehicle tenders). */
    @ColumnInfo(name = "v_type", defaultValue = "Bus")
    val vType: String = "Bus",

    /** Fuel type — Diesel / Petrol / Electric (only for vehicle tenders). */
    @ColumnInfo(name = "f_type", defaultValue = "Diesel")
    val fType: String = "Diesel",

    @ColumnInfo(name = "created_at")  val createdAt: Long = System.currentTimeMillis(),

    /**
     * Stable cross-platform ID — the Firestore document ID.
     *
     *  • Windows app: tender.id is already a string ("t_<ts>_<rand>") — its
     *    firebaseId == its localStorage id.
     *  • Android: Room uses a Long autoGenerate id locally; firebaseId is
     *    assigned on first save (or synced back from Firestore) so the row
     *    can be matched across devices.
     *
     * Empty for tenders that have never been pushed to Firestore yet.
     */
    @ColumnInfo(name = "firebase_id") val firebaseId: String = ""
)
