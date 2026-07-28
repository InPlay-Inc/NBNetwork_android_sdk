package com.nanobeaconnetwork.internal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [PendingReport::class], version = 1, exportSchema = false)
internal abstract class SdkDatabase : RoomDatabase() {
    abstract fun pendingReportDao(): PendingReportDao

    companion object {
        @Volatile private var INSTANCE: SdkDatabase? = null

        fun getInstance(context: Context, passphrase: ByteArray): SdkDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, passphrase).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context, passphrase: ByteArray): SdkDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                SdkDatabase::class.java,
                "nbn_sdk.db"
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
