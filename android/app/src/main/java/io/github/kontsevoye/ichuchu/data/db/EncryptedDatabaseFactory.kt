package io.github.kontsevoye.ichuchu.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/** Defers Keystore access and legacy export until Room opens storage on its IO executor. */
internal class EncryptedDatabaseFactory(
    private val context: Context,
    private val keyStore: DatabaseKeyStore = DatabaseKeyStore(context.noBackupFilesDir),
) : SupportSQLiteOpenHelper.Factory {
    companion object {
        const val DATABASE_NAME = "ichuchu-encrypted.db"
        const val LEGACY_DATABASE_NAME = "ichuchu.db"
    }

    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration
    ): SupportSQLiteOpenHelper =
        object : SupportSQLiteOpenHelper {
            private var delegate: SQLiteOpenHelper? = null
            private var password: ByteArray? = null
            private var walEnabled = false
            override val databaseName: String?
                get() = configuration.name

            override val writableDatabase: SupportSQLiteDatabase
                get() = helper().writableDatabase

            override val readableDatabase: SupportSQLiteDatabase
                get() = helper().readableDatabase

            @Synchronized
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                walEnabled = enabled
                delegate?.setWriteAheadLoggingEnabled(enabled)
            }

            @Synchronized
            override fun close() {
                delegate?.close()
                delegate = null
                password?.fill(0)
                password = null
            }

            @Synchronized
            private fun helper(): SQLiteOpenHelper {
                delegate?.let {
                    return it
                }
                check(configuration.name == DATABASE_NAME) { "Unexpected encrypted database name" }
                System.loadLibrary("sqlcipher")
                val databaseFile = context.getDatabasePath(DATABASE_NAME)
                val key = keyStore.loadOrCreate(databaseFile.exists())
                try {
                    LegacyDatabaseMigration.prepare(
                        context.getDatabasePath(LEGACY_DATABASE_NAME),
                        databaseFile,
                        key,
                    )
                    return createHelper(configuration, key, walEnabled).also {
                        password = key
                        delegate = it
                    }
                } catch (error: Throwable) {
                    key.fill(0)
                    throw error
                }
            }
        }

    private fun createHelper(
        config: SupportSQLiteOpenHelper.Configuration,
        passphrase: ByteArray,
        wal: Boolean,
    ): SQLiteOpenHelper =
        object :
            SQLiteOpenHelper(
                config.context,
                config.name,
                passphrase,
                null,
                config.callback.version,
                0,
                LegacyDatabaseMigration.preserveOnCorruption,
                null,
                wal,
            ) {
            override fun onConfigure(db: SQLiteDatabase) = config.callback.onConfigure(db)

            override fun onCreate(db: SQLiteDatabase) = config.callback.onCreate(db)

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                config.callback.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                config.callback.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SQLiteDatabase) = config.callback.onOpen(db)
        }
}
