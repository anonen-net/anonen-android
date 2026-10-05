package net.anonen.app.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HistoryEntry::class], version = 6, exportSchema = false)
@TypeConverters(RevisionConverters::class)
abstract class AnonenDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: AnonenDatabase? = null

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE history ADD COLUMN audioFileName TEXT")
                }
            }

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE history ADD COLUMN model TEXT")
                }
            }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE history ADD COLUMN previousText TEXT")
                    db.execSQL("ALTER TABLE history ADD COLUMN previousModel TEXT")
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `history_new` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, " +
                            "`text` TEXT NOT NULL, " +
                            "`durationMs` INTEGER NOT NULL, " +
                            "`audioFileName` TEXT, " +
                            "`model` TEXT, " +
                            "`revisions` TEXT NOT NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO `history_new` " +
                            "(`id`, `timestamp`, `text`, `durationMs`, `audioFileName`, `model`, `revisions`) " +
                            "SELECT `id`, `timestamp`, `text`, `durationMs`, `audioFileName`, `model`, '[]' " +
                            "FROM `history`",
                    )
                    db.execSQL("DROP TABLE `history`")
                    db.execSQL("ALTER TABLE `history_new` RENAME TO `history`")
                }
            }

        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE history ADD COLUMN transcribedAt INTEGER")
                }
            }

        private val SECURE_DELETE =
            object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    runCatching { db.execSQL("PRAGMA secure_delete = ON") }
                }
            }

        fun get(context: Context): AnonenDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnonenDatabase::class.java,
                    "handy.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .addCallback(SECURE_DELETE)
                    .build().also { instance = it }
            }
    }
}
