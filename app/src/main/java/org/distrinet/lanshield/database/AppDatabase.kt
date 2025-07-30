package org.distrinet.lanshield.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.distrinet.lanshield.database.dao.FlowDao
import org.distrinet.lanshield.database.dao.InetSocketAddressConverter
import org.distrinet.lanshield.database.dao.LANShieldSessionDao
import org.distrinet.lanshield.database.dao.LanAccessPolicyDao
import org.distrinet.lanshield.database.dao.OpenPortsDao
import org.distrinet.lanshield.database.dao.SortedIntSetConverter
import org.distrinet.lanshield.database.dao.StringListConverter
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.database.model.LANShieldSession
import org.distrinet.lanshield.database.model.LanAccessPolicy
import org.distrinet.lanshield.database.model.OpenPorts

@Database(entities = [LanAccessPolicy::class, LANFlow::class, LANShieldSession::class, OpenPorts::class], version = 3, exportSchema = true)
@TypeConverters(SortedIntSetConverter::class, StringListConverter::class, InetSocketAddressConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun LanAccessPolicyDao(): LanAccessPolicyDao
    abstract fun FlowDao(): FlowDao
    abstract fun LANShieldSessionDao(): LANShieldSessionDao
    abstract fun OpenPortsDao(): OpenPortsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            val tempInstance = INSTANCE
            if (tempInstance != null) {
                return tempInstance
            }
            synchronized(this) {
                val instance = Room.databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    "lanshield_database"
                ).addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .build()
                INSTANCE = instance
                return instance
            }
        }
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE `lanshield_session` (`uuid` BLOB NOT NULL, `timeStart` INTEGER NOT NULL, `timeEnd` INTEGER NOT NULL, `timeEndAtLastSync` INTEGER NOT NULL, PRIMARY KEY(`uuid`))")
        db.execSQL("ALTER TABLE `flow` ADD COLUMN `scheduledForDeletion` INTEGER NOT NULL DEFAULT undefined")
        db.execSQL("UPDATE `flow` SET `scheduledForDeletion` = 0 WHERE `scheduledForDeletion` = 'undefined'")

        db.execSQL("ALTER TABLE `flow` ADD COLUMN `dpiReport` TEXT")
        db.execSQL("ALTER TABLE `flow` ADD COLUMN `dpiProtocol` TEXT")

    }

}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `open_ports` (
                `uuid` BLOB NOT NULL,
                `packageName` TEXT NOT NULL,
                `packageLabel` TEXT NOT NULL,
                `udpPorts` TEXT NOT NULL,
                `tcpPorts` TEXT NOT NULL,
                `timeOpenPortsObserved` INTEGER NOT NULL,
                `shouldSync` INTEGER NOT NULL,
                `scheduledForDeletion` INTEGER NOT NULL,
                PRIMARY KEY(`uuid`)
            )
        """.trimIndent())
    }

}

