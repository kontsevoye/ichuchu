package io.github.kontsevoye.ichuchu.data.db

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** Publishes a verified encrypted copy before removing any legacy plaintext files. */
internal object LegacyDatabaseMigration {
    // SQLCipher's default corruption handler deletes files. Never use it for credential storage.
    val preserveOnCorruption = DatabaseErrorHandler { _, exception -> throw exception }

    fun prepare(legacy: File, encrypted: File, passphrase: ByteArray) {
        val staging = File(encrypted.path + ".migrating")
        if (encrypted.exists()) {
            verify(encrypted, passphrase)
            deleteDatabaseFiles(legacy)
            deleteDatabaseFiles(staging)
            return
        }
        if (!legacy.exists()) {
            check(!staging.exists()) {
                "Incomplete encrypted database; restore an encrypted backup"
            }
            return
        }
        check(encrypted.parentFile!!.mkdirs() || encrypted.parentFile!!.isDirectory)
        deleteDatabaseFiles(staging)
        val snapshot = export(legacy, staging, passphrase)
        val copied = verify(staging, passphrase)
        check(snapshot == copied) { "Encrypted database verification failed" }
        FileOutputStream(staging, true).use { it.fd.sync() }
        Os.rename(staging.absolutePath, encrypted.absolutePath)
        syncDirectory(encrypted.parentFile!!)
        deleteDatabaseFiles(legacy)
    }

    private data class Snapshot(val version: Int, val counts: Map<String, Long>)

    private fun export(legacy: File, staging: File, passphrase: ByteArray): Snapshot {
        // SQLite propagates this connection's CREATE flag to ATTACH. Without it the
        // encrypted destination cannot be created, even though the legacy file exists.
        open(
                legacy,
                byteArrayOf(),
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            )
            .use { database ->
                database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                        "Legacy database is busy"
                    }
                }
                database.execSQL(
                    "ATTACH DATABASE ? AS encrypted KEY ?",
                    arrayOf(staging.absolutePath, passphrase),
                )
                try {
                    database.beginTransaction()
                    try {
                        val snapshot = snapshot(database)
                        check(snapshot.version > 0 && snapshot.counts.isNotEmpty()) {
                            "Legacy database has no application schema"
                        }
                        database.rawQuery("SELECT sqlcipher_export('encrypted')", null).use {
                            it.moveToFirst()
                        }
                        database.execSQL("PRAGMA encrypted.user_version = ${snapshot.version}")
                        database.setTransactionSuccessful()
                        return snapshot
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    database.execSQL("DETACH DATABASE encrypted")
                }
            }
    }

    private fun verify(file: File, passphrase: ByteArray): Snapshot {
        check(file.length() > 0) { "Encrypted database is empty" }
        // Recovery of a hot rollback journal needs write access. Omit CREATE so a
        // missing authoritative file can never silently become a new database.
        return open(file, passphrase, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.rawQuery("PRAGMA cipher_integrity_check", null).use {
                check(!it.moveToFirst()) { "Encrypted database authentication failed" }
            }
            database.rawQuery("PRAGMA integrity_check", null).use {
                check(it.moveToFirst() && it.getString(0) == "ok") {
                    "Database integrity check failed"
                }
            }
            snapshot(database).also {
                check(it.version > 0 && it.counts.isNotEmpty()) {
                    "Encrypted database has no application schema"
                }
            }
        }
    }

    private fun snapshot(database: SQLiteDatabase): Snapshot {
        val tables = mutableListOf<String>()
        database
            .rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                null,
            )
            .use {
                while (it.moveToNext()) tables.add(it.getString(0))
            }
        val counts = tables.associateWith { name ->
            val identifier = name.replace("\"", "\"\"")
            database.rawQuery("SELECT COUNT(*) FROM \"$identifier\"", null).use {
                check(it.moveToFirst())
                it.getLong(0)
            }
        }
        return Snapshot(database.version, counts)
    }

    private fun open(file: File, passphrase: ByteArray, flags: Int): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            flags,
            preserveOnCorruption,
            null,
        )

    private fun deleteDatabaseFiles(file: File) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val part = File(file.path + suffix)
            check(!part.exists() || part.delete()) { "Cannot remove obsolete database file" }
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}
