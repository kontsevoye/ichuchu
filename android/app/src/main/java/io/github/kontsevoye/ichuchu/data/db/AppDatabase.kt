package io.github.kontsevoye.ichuchu.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.SshKey

@Database(
    entities = [HostProfile::class, SshKey::class],
    version = 13,
    autoMigrations =
        [
            AutoMigration(from = 5, to = 6),
            AutoMigration(from = 6, to = 7, spec = AppDatabase.Migration6To7::class),
            AutoMigration(from = 7, to = 8),
            AutoMigration(from = 8, to = 9),
            AutoMigration(from = 9, to = 10, spec = AppDatabase.Migration9To10::class),
            AutoMigration(from = 10, to = 11),
            AutoMigration(from = 11, to = 12),
            AutoMigration(from = 12, to = 13, spec = AppDatabase.Migration12To13::class),
        ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostProfileDao(): HostProfileDao

    abstract fun sshKeyDao(): SshKeyDao

    @DeleteColumn(tableName = "host_profiles", columnName = "keyPath")
    class Migration6To7 : AutoMigrationSpec

    @RenameColumn(
        tableName = "host_profiles",
        fromColumnName = "postConnectActionId",
        toColumnName = "postConnectCommand",
    )
    class Migration9To10 : AutoMigrationSpec

    class Migration12To13 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            // Legacy profiles have no timestamp; autoincrement IDs retain creation order.
            db.execSQL("UPDATE host_profiles SET sortOrder = id")
        }
    }

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                AppDatabase::class.java,
                                EncryptedDatabaseFactory.DATABASE_NAME,
                            )
                            .openHelperFactory(EncryptedDatabaseFactory(context.applicationContext))
                            .build()
                            .also { instance = it }
                }
    }
}
