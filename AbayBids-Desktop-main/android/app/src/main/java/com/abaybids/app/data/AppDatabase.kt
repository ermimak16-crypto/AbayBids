package com.abaybids.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Database(
    entities = [Tender::class, Contract::class, Document::class, BidHistory::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tenderDao(): TenderDao
    abstract fun contractDao(): ContractDao
    abstract fun documentDao(): DocumentDao
    abstract fun bidHistoryDao(): BidHistoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tenders ADD COLUMN firebase_id TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tenders_firebase_id " +
                    "ON tenders(firebase_id) WHERE firebase_id != ''"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS contracts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        tender_id INTEGER NOT NULL DEFAULT 0,
                        customer TEXT NOT NULL,
                        tender_no TEXT NOT NULL DEFAULT '',
                        contract_no TEXT NOT NULL DEFAULT '',
                        signing_date TEXT NOT NULL DEFAULT '',
                        start_date TEXT NOT NULL DEFAULT '',
                        end_date TEXT NOT NULL DEFAULT '',
                        value REAL NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'Active',
                        contact_person TEXT NOT NULL DEFAULT '',
                        contact_phone TEXT NOT NULL DEFAULT '',
                        contact_email TEXT NOT NULL DEFAULT '',
                        reminder_days INTEGER NOT NULL DEFAULT 14,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // v3 -> v4: add the documents table for cross-platform file sync.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS documents (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        firebase_id TEXT NOT NULL DEFAULT '',
                        file_name TEXT NOT NULL,
                        mime_type TEXT NOT NULL DEFAULT '',
                        size INTEGER NOT NULL DEFAULT 0,
                        upload_date INTEGER NOT NULL DEFAULT 0,
                        uploaded_by TEXT NOT NULL DEFAULT '',
                        tender_id TEXT NOT NULL DEFAULT '',
                        contract_id TEXT NOT NULL DEFAULT '',
                        storage_path TEXT NOT NULL DEFAULT '',
                        local_path TEXT NOT NULL DEFAULT '',
                        version INTEGER NOT NULL DEFAULT 1,
                        status TEXT NOT NULL DEFAULT 'PENDING_UPLOAD',
                        origin TEXT NOT NULL DEFAULT 'android',
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_tender_id ON documents(tender_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_contract_id ON documents(contract_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_status ON documents(status)")
            }
        }

        // v4 -> v5:
        //   • add tenders.rep_status, tenders.v_type, tenders.f_type columns
        //     (parity with the Windows dashboard schema).
        //   • create the bid_history table (status-change audit log).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Tender reporting + vehicle fields (TEXT NOT NULL DEFAULT).
                db.execSQL(
                    "ALTER TABLE tenders ADD COLUMN rep_status TEXT NOT NULL DEFAULT 'Pending'"
                )
                db.execSQL(
                    "ALTER TABLE tenders ADD COLUMN v_type TEXT NOT NULL DEFAULT 'Bus'"
                )
                db.execSQL(
                    "ALTER TABLE tenders ADD COLUMN f_type TEXT NOT NULL DEFAULT 'Diesel'"
                )

                // Bid history audit log.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bid_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        tender_id INTEGER NOT NULL DEFAULT 0,
                        customer TEXT NOT NULL DEFAULT '',
                        tender_no TEXT NOT NULL DEFAULT '',
                        from_status TEXT NOT NULL DEFAULT '',
                        to_status TEXT NOT NULL DEFAULT '',
                        value REAL NOT NULL DEFAULT 0,
                        timestamp INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bid_history_tender_id ON bid_history(tender_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bid_history_timestamp ON bid_history(timestamp)"
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "abay-bids.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }

    /** On first launch, seed the same kind of demo tenders the web app shows. */
    fun seedIfEmpty() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val dao = tenderDao()
            if (dao.count() > 0) return@launch
            val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val now = Calendar.getInstance()
            fun daysFromNow(days: Int): String {
                val c = now.clone() as Calendar
                c.add(Calendar.DAY_OF_MONTH, days)
                return iso.format(c.time)
            }
            listOf(
                Tender(
                    customer = "Ethiopian Electric Power",
                    no = "EEP/TND/2025/0142",
                    product = "Construction",
                    date = daysFromNow(2),
                    value = 4_250_000.0,
                    status = "Pending",
                    cpo = "Yes",
                    cpoAmount = 85_000.0,
                    reminderDays = 3
                ),
                Tender(
                    customer = "Addis Ababa Water & Sewerage Authority",
                    no = "AAWSA/BID/2025/099",
                    product = "Consultancy",
                    date = daysFromNow(5),
                    value = 1_100_000.0,
                    status = "Participated",
                    cpo = "No",
                    reminderDays = 3
                ),
                Tender(
                    customer = "Ethiopian Airlines Group",
                    no = "ETG/PROC/2025/4471",
                    product = "Logistics",
                    date = daysFromNow(-1),
                    value = 7_800_000.0,
                    status = "Won",
                    cpo = "Yes",
                    cpoAmount = 156_000.0,
                    cpoCollected = false
                ),
                Tender(
                    customer = "Ministry of Health",
                    no = "MOH/SUPPLY/2025/318",
                    product = "Medical Supplies",
                    date = daysFromNow(9),
                    value = 2_950_000.0,
                    status = "Pending",
                    cpo = "Yes",
                    cpoAmount = 60_000.0,
                    reminderDays = 5
                ),
                Tender(
                    customer = "Ethio Telecom",
                    no = "ET/INFRA/2025/7782",
                    product = "IT Equipment",
                    date = daysFromNow(14),
                    value = 5_300_000.0,
                    status = "Pending",
                    cpo = "No",
                    reminderDays = 3
                ),
                Tender(
                    customer = "Commercial Bank of Ethiopia",
                    no = "CBE/FAC/2025/2055",
                    product = "Facility Maintenance",
                    date = daysFromNow(-3),
                    value = 850_000.0,
                    status = "Lost",
                    cpo = "Yes",
                    cpoAmount = 17_000.0,
                    cpoCollected = false
                )
            ).forEach { dao.upsert(it) }
        }
    }
}
