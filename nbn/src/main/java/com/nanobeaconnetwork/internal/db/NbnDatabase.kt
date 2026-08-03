package com.nanobeaconnetwork.internal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [PendingReport::class], version = 3, exportSchema = false)
internal abstract class NbnDatabase : RoomDatabase() {
    abstract fun pendingReportDao(): PendingReportDao

    companion object {
        @Volatile private var INSTANCE: NbnDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE pending_reports_v2 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        observationId TEXT NOT NULL,
                        sourceKey TEXT NOT NULL,
                        slot TEXT NOT NULL,
                        batchId TEXT,
                        eidHex TEXT NOT NULL,
                        payloadBase64 TEXT NOT NULL,
                        rssi INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        clientSeenAt TEXT NOT NULL,
                        failedAttempts INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        nextAttemptAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // The SDK and API have not been released yet, but preserving development
                // observations makes upgrades predictable. SQL generates valid UUIDv4 values.
                db.execSQL(
                    """
                    INSERT INTO pending_reports_v2
                      (id,observationId,sourceKey,slot,batchId,eidHex,payloadBase64,rssi,
                       latitude,longitude,clientSeenAt,failedAttempts,createdAt,expiresAt,nextAttemptAt)
                    SELECT id,
                      lower(hex(randomblob(4)))||'-'||lower(hex(randomblob(2)))||'-4'||
                      substr(lower(hex(randomblob(2))),2)||'-a'||
                      substr(lower(hex(randomblob(2))),2)||'-'||lower(hex(randomblob(6))),
                      'eid:'||lower(eidHex),'pending_latest',NULL,eidHex,payloadHex,rssi,
                      latitude,longitude,timestamp,retryCount,createdAt,createdAt+3600000,createdAt
                    FROM pending_reports
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE pending_reports")
                db.execSQL("ALTER TABLE pending_reports_v2 RENAME TO pending_reports")
                db.execSQL("CREATE UNIQUE INDEX index_pending_reports_observationId ON pending_reports(observationId)")
                db.execSQL("CREATE UNIQUE INDEX index_pending_reports_sourceKey_slot ON pending_reports(sourceKey,slot)")
                db.execSQL("CREATE INDEX index_pending_reports_slot_nextAttemptAt ON pending_reports(slot,nextAttemptAt)")
                db.execSQL("CREATE INDEX index_pending_reports_batchId ON pending_reports(batchId)")
            }
        }

        fun getInstance(context: Context, passphrase: ByteArray): NbnDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, passphrase).also { INSTANCE = it }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE pending_reports_v3 (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        observationId TEXT NOT NULL,
                        sourceKey TEXT NOT NULL,
                        slot TEXT NOT NULL,
                        batchId TEXT,
                        eidHex TEXT NOT NULL,
                        payloadBase64 TEXT NOT NULL,
                        rssi INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        clientSeenAt TEXT NOT NULL,
                        failedAttempts INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        nextAttemptAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO pending_reports_v3
                      (id,observationId,sourceKey,slot,batchId,eidHex,payloadBase64,rssi,
                       latitude,longitude,clientSeenAt,failedAttempts,createdAt,expiresAt,nextAttemptAt)
                    SELECT id,observationId,sourceKey,slot,batchId,eidHex,payloadBase64,rssi,
                           latitude,longitude,clientSeenAt,failedAttempts,createdAt,expiresAt,nextAttemptAt
                    FROM pending_reports
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE pending_reports")
                db.execSQL("ALTER TABLE pending_reports_v3 RENAME TO pending_reports")
                db.execSQL("CREATE UNIQUE INDEX index_pending_reports_observationId ON pending_reports(observationId)")
                db.execSQL("CREATE UNIQUE INDEX index_pending_reports_sourceKey_slot ON pending_reports(sourceKey,slot)")
                db.execSQL("CREATE INDEX index_pending_reports_slot_nextAttemptAt ON pending_reports(slot,nextAttemptAt)")
                db.execSQL("CREATE INDEX index_pending_reports_batchId ON pending_reports(batchId)")
            }
        }

        private fun buildDatabase(context: Context, passphrase: ByteArray): NbnDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context.applicationContext, NbnDatabase::class.java, "nbn.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}
