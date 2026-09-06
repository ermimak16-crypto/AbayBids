package com.abaybids.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Audit-log entry — recorded every time an EXISTING tender's status changes
 * (created from [TenderRepository.save] when old.status != new.status).
 *
 *  - `tenderId` is the local Long PK of the tender (0 if unknown).
 *  - `customer` / `tenderNo` are denormalized so the history list still
 *    renders correctly even after the underlying tender is deleted.
 *  - `value` is the tender's estimated value at the time of the change
 *    (so the history reflects what was at stake for each transition).
 *
 * Mirrors the Windows app's "Bid History" view (one row per status change).
 */
@Entity(tableName = "bid_history")
data class BidHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "tender_id")   val tenderId: Long = 0L,
    @ColumnInfo(name = "customer")   val customer: String = "",
    @ColumnInfo(name = "tender_no")  val tenderNo: String = "",
    @ColumnInfo(name = "from_status") val fromStatus: String = "",
    @ColumnInfo(name = "to_status")   val toStatus: String = "",
    @ColumnInfo(name = "value")       val value: Double = 0.0,
    @ColumnInfo(name = "timestamp")   val timestamp: Long = System.currentTimeMillis()
)
