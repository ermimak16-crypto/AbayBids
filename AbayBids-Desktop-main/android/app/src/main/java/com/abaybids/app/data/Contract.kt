package com.abaybids.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A contract — either auto-created when a tender is Won, or manually added.
 * Mirrors the Windows dashboard's contract schema so the two stay in sync
 * via Firestore.
 *
 *  - `endDate` drives the effective status: Active / ExpiringSoon / Expired.
 *  - `status` can be manually set to Completed or Terminated (overrides date).
 *  - `tenderId` is 0 for manually-added contracts (not tied to a tender).
 */
@Entity(tableName = "contracts")
data class Contract(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "tender_id")     val tenderId: Long = 0L,
    @ColumnInfo(name = "customer")      val customer: String,
    @ColumnInfo(name = "tender_no")     val tenderNo: String = "",
    @ColumnInfo(name = "contract_no")  val contractNo: String = "",
    @ColumnInfo(name = "signing_date")  val signingDate: String = "",
    @ColumnInfo(name = "start_date")    val startDate: String = "",
    @ColumnInfo(name = "end_date")      val endDate: String = "",
    @ColumnInfo(name = "value")         val value: Double = 0.0,
    @ColumnInfo(name = "status")        val status: String = "Active",
    @ColumnInfo(name = "contact_person") val contactPerson: String = "",
    @ColumnInfo(name = "contact_phone") val contactPhone: String = "",
    @ColumnInfo(name = "contact_email") val contactEmail: String = "",
    @ColumnInfo(name = "reminder_days") val reminderDaysBeforeExpiry: Int = 14,
    @ColumnInfo(name = "created_at")    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")   val updatedAt: Long = System.currentTimeMillis()
)
