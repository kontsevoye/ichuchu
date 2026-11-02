package io.github.kontsevoye.ichuchu.data.db

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.model.HostOpenMode
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.SshKey
import io.github.kontsevoye.ichuchu.service.agents.AgentAttachment
import io.github.kontsevoye.ichuchu.service.agents.AgentChatState
import io.github.kontsevoye.ichuchu.service.agents.AgentStateStore
import io.github.kontsevoye.ichuchu.service.agents.DeckSend
import io.github.kontsevoye.ichuchu.service.agents.DeckTimeline
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Device-only coverage: exercises real SQLCipher JNI and Android Keystore with synthetic data. */
class EncryptedStorageTest {
    private lateinit var directory: File
    private lateinit var alias: String
    private lateinit var keys: DatabaseKeyStore
    private val legacy
        get() = File(directory, "legacy.db")

    private val encrypted
        get() = File(directory, "encrypted.db")

    @Before
    fun setup() {
        System.loadLibrary("sqlcipher")
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "encrypted-storage-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        alias = "ichuchu.test.${UUID.randomUUID()}"
        keys = DatabaseKeyStore(File(directory, "keys"), alias)
    }

    @After
    fun cleanup() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }

    private fun seedLegacy() {
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacy, null).use {
            it.execSQL("CREATE TABLE secrets (id INTEGER PRIMARY KEY, secret TEXT NOT NULL)")
            it.execSQL("INSERT INTO secrets VALUES (1, 'SYNTHETIC_PRIVATE_KEY')")
            it.version = 11
        }
    }

    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getDatabasePath(name: String): File = File(directory, name)

            override fun getNoBackupFilesDir(): File =
                File(directory, "agent-keys").apply { mkdirs() }
        }
    }

    @Test
    fun version13MigratesToTerminalDefaultsAndCreatesEncryptedAgentCache() = runBlocking {
        val isolated = isolatedContext()
        val schema =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("io.github.kontsevoye.ichuchu.data.db.AppDatabase/13.json")
                .bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                isolated.getDatabasePath(EncryptedDatabaseFactory.LEGACY_DATABASE_NAME),
                null,
            )
            .use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun sql(value: String) =
                        value.replace("\${TABLE_NAME}", entity.getString("tableName"))
                    old.execSQL(sql(entity.getString("createSql")))
                    val indices = entity.getJSONArray("indices")
                    for (j in 0 until indices.length()) {
                        old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL(
                    "INSERT INTO host_profiles (id,name,host,port,username,password,keyPassphrase,transport,authMethod) VALUES (1,'old host','example.invalid',22,'test','','','SSH','Password')"
                )
                old.version = 13
            }
        val database =
            Room.databaseBuilder(
                    isolated,
                    AppDatabase::class.java,
                    EncryptedDatabaseFactory.DATABASE_NAME,
                )
                .openHelperFactory(
                    EncryptedDatabaseFactory(
                        isolated,
                        DatabaseKeyStore(isolated.noBackupFilesDir, alias),
                    )
                )
                .build()
        try {
            val host = database.hostProfileDao().getById(1)!!
            assertEquals(HostOpenMode.Terminal, host.openMode)
            assertEquals("default", host.deckProfile)
            assertEquals("", host.deckExecutable)
            val cache = AgentStateStore(database, 1, "fixture")
            cache.saveChat("full-id", AgentChatState(draft = "SYNTHETIC_AGENT_DRAFT"))
            assertEquals("SYNTHETIC_AGENT_DRAFT", cache.chat("full-id").draft)
        } finally {
            database.close()
        }
        assertFalse(
            String(
                    isolated.getDatabasePath(EncryptedDatabaseFactory.DATABASE_NAME).readBytes(),
                    Charsets.ISO_8859_1,
                )
                .contains("SYNTHETIC_AGENT_DRAFT")
        )
    }

    @Test
    fun encryptedDraftCursorAndUnknownReceiptSurviveReopenRenameAndCacheEviction() = runBlocking {
        val isolated = isolatedContext()
        fun openRoom() =
            Room.databaseBuilder(
                    isolated,
                    AppDatabase::class.java,
                    EncryptedDatabaseFactory.DATABASE_NAME,
                )
                .openHelperFactory(
                    EncryptedDatabaseFactory(
                        isolated,
                        DatabaseKeyStore(isolated.noBackupFilesDir, alias),
                    )
                )
                .build()
        val attachment =
            AgentAttachment(
                "file-id",
                "SYNTHETIC_PRIVATE_FILE.png",
                "image/png",
                42,
                "/tmp/ichuchu-attachment.fixture/file.png",
            )
        val value =
            AgentChatState(
                timeline = DeckTimeline(emptyList(), "opaque-native-record-checkpoint", null),
                draft = "SYNTHETIC_AGENT_DRAFT",
                sends =
                    listOf(
                        DeckSend(
                            "local-intent",
                            "SYNTHETIC_AGENT_PROMPT",
                            Json.parseToJsonElement("{\"state\":\"unknown\"}").jsonObject,
                            anchorRowId = "native-row",
                            createdAt = 123456,
                            attachments = listOf(attachment),
                        )
                    ),
                attachments = listOf(attachment),
            )
        val database = openRoom()
        val hostId =
            database
                .hostProfileDao()
                .insert(HostProfile(name = "fixture", host = "example.invalid", username = "test"))
        try {
            val cache = AgentStateStore(database, hostId, "fixture")
            cache.saveChat("full-id", value)
            cache.saveChat("attachment-only", AgentChatState(attachments = listOf(attachment)))
            val host = database.hostProfileDao().getById(hostId)!!
            database.hostProfileDao().saveValidated(host.copy(name = "renamed"))
            repeat(25) { cache.saveChat("other-$it", AgentChatState()) }
            cache.clearHistory()
            assertEquals(value, cache.chat("full-id"))
            assertEquals(listOf(attachment), cache.chat("attachment-only").attachments)
        } finally {
            database.close()
        }
        val reopened = openRoom()
        try {
            assertEquals(value, AgentStateStore(reopened, hostId, "fixture").chat("full-id"))
            assertEquals(
                "",
                AgentStateStore(reopened, hostId, "different-host-key").chat("full-id").draft,
            )
        } finally {
            reopened.close()
        }
        val bytes =
            String(
                isolated.getDatabasePath(EncryptedDatabaseFactory.DATABASE_NAME).readBytes(),
                Charsets.ISO_8859_1,
            )
        assertFalse(bytes.contains("SYNTHETIC_AGENT_DRAFT"))
        assertFalse(bytes.contains("SYNTHETIC_AGENT_PROMPT"))
    }

    private fun open(password: ByteArray) =
        SQLiteDatabase.openDatabase(
            encrypted.absolutePath,
            password,
            null,
            SQLiteDatabase.OPEN_READONLY,
            LegacyDatabaseMigration.preserveOnCorruption,
            null,
        )

    @Test
    fun migratesRecordsVersionAndRemovesPlaintextIncludingSidecars() {
        seedLegacy()
        val passphrase = keys.loadOrCreate(false)
        LegacyDatabaseMigration.prepare(legacy, encrypted, passphrase)
        assertFalse(legacy.exists())
        assertFalse(File(legacy.path + "-wal").exists())
        assertFalse(
            String(encrypted.readBytes(), Charsets.ISO_8859_1).contains("SYNTHETIC_PRIVATE_KEY")
        )
        open(passphrase).use {
            assertEquals(11, it.version)
            it.rawQuery("SELECT secret FROM secrets WHERE id=1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("SYNTHETIC_PRIVATE_KEY", cursor.getString(0))
            }
        }
        assertArrayEquals(
            passphrase,
            DatabaseKeyStore(File(directory, "keys"), alias).loadOrCreate(true),
        )
        LegacyDatabaseMigration.prepare(legacy, encrypted, passphrase)
        passphrase.fill(0)
    }

    @Test
    fun roomMigratesRealCredentialSchemaAndReopensWithTheSameWrappedKey() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated =
            object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this

                override fun getDatabasePath(name: String): File = File(directory, name)

                override fun getNoBackupFilesDir(): File =
                    File(directory, "room-keys").apply { mkdirs() }
            }
        val key =
            SshKey(
                name = "synthetic",
                privateKeyPem = "SYNTHETIC_PRIVATE_KEY",
                publicKeyOpenSsh = "synthetic-public",
                createdAtEpochMs = 1,
            )
        val source =
            Room.databaseBuilder(
                    isolated,
                    AppDatabase::class.java,
                    EncryptedDatabaseFactory.LEGACY_DATABASE_NAME,
                )
                .build()
        val keyId =
            try {
                val id = source.sshKeyDao().insert(key)
                source
                    .hostProfileDao()
                    .insert(
                        HostProfile(
                            name = "synthetic-host",
                            host = "example.invalid",
                            username = "test",
                            password = "SYNTHETIC_PASSWORD",
                            keyId = id,
                        )
                    )
                id
            } finally {
                source.close()
            }
        repeat(2) {
            val destination =
                Room.databaseBuilder(
                        isolated,
                        AppDatabase::class.java,
                        EncryptedDatabaseFactory.DATABASE_NAME,
                    )
                    .openHelperFactory(
                        EncryptedDatabaseFactory(
                            isolated,
                            DatabaseKeyStore(isolated.noBackupFilesDir, alias),
                        )
                    )
                    .build()
            try {
                assertEquals(
                    "SYNTHETIC_PRIVATE_KEY",
                    destination.sshKeyDao().getById(keyId)!!.privateKeyPem,
                )
                assertEquals(
                    "SYNTHETIC_PASSWORD",
                    destination.hostProfileDao().getAll().single().password,
                )
                assertFalse(
                    isolated.getDatabasePath(EncryptedDatabaseFactory.LEGACY_DATABASE_NAME).exists()
                )
            } finally {
                destination.close()
            }
        }
    }

    @Test
    fun interruptedExportRetriesFromTheIntactLegacyDatabase() {
        seedLegacy()
        File(encrypted.path + ".migrating").writeText("incomplete synthetic export")
        val passphrase = keys.loadOrCreate(false)
        LegacyDatabaseMigration.prepare(legacy, encrypted, passphrase)
        open(passphrase).use { assertEquals(11, it.version) }
        assertFalse(legacy.exists())
        passphrase.fill(0)
    }

    @Test
    fun committedWalRowsAreIncludedBeforePlaintextSidecarsAreRemoved() {
        seedLegacy()
        val source =
            android.database.sqlite.SQLiteDatabase.openDatabase(legacy.absolutePath, null, 0)
        source.use {
            assertTrue(source.enableWriteAheadLogging())
            source.execSQL("INSERT INTO secrets VALUES (2, 'SYNTHETIC_WAL_SECRET')")
            assertTrue(File(legacy.path + "-wal").length() > 0)
            val passphrase = keys.loadOrCreate(false)
            LegacyDatabaseMigration.prepare(legacy, encrypted, passphrase)
            open(passphrase).use { destination ->
                destination.rawQuery("SELECT secret FROM secrets WHERE id=2", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("SYNTHETIC_WAL_SECRET", it.getString(0))
                }
            }
            assertFalse(File(legacy.path + "-wal").exists())
            passphrase.fill(0)
        }
    }

    @Test
    fun recoversHotRollbackJournalWithoutLosingCommittedCredentials() {
        seedLegacy()
        val passphrase = keys.loadOrCreate(false)
        LegacyDatabaseMigration.prepare(legacy, encrypted, passphrase)
        val recovered = File(directory, "crashed-encrypted.db")
        SQLiteDatabase.openDatabase(
                encrypted.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                LegacyDatabaseMigration.preserveOnCorruption,
                null,
            )
            .use { writer ->
                writer.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
                writer.execSQL("PRAGMA cache_size=5")
                writer.beginTransaction()
                try {
                    writer.execSQL(
                        "UPDATE secrets SET secret=? WHERE id=1",
                        arrayOf("uncommitted".repeat(100_000)),
                    )
                    val journal = File(encrypted.path + "-journal")
                    assertTrue(journal.length() > 0)
                    // The writer is idle here: copying both files captures a reproducible crash
                    // image.
                    encrypted.copyTo(recovered)
                    journal.copyTo(File(recovered.path + "-journal"))
                } finally {
                    writer.endTransaction()
                }
            }
        LegacyDatabaseMigration.prepare(legacy, recovered, passphrase)
        SQLiteDatabase.openDatabase(
                recovered.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READONLY,
                LegacyDatabaseMigration.preserveOnCorruption,
                null,
            )
            .use { database ->
                database.rawQuery("SELECT secret FROM secrets WHERE id=1", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("SYNTHETIC_PRIVATE_KEY", it.getString(0))
                }
            }
        passphrase.fill(0)
    }

    @Test
    fun corruptAuthoritativeDatabaseDoesNotDeleteItOrFallBackToLegacy() {
        seedLegacy()
        val passphrase = keys.loadOrCreate(false)
        encrypted.writeText("synthetic corrupt database")
        val before = encrypted.readBytes()
        try {
            LegacyDatabaseMigration.prepare(legacy, encrypted, passphrase)
            fail("Corrupt encrypted storage must fail closed")
        } catch (_: android.database.sqlite.SQLiteException) {
            assertTrue(legacy.exists())
            assertArrayEquals(before, encrypted.readBytes())
        }
        passphrase.fill(0)
    }

    @Test
    fun emptyAuthoritativeDatabaseIsNotRecreatedFromLegacy() {
        seedLegacy()
        val passphrase = keys.loadOrCreate(false)
        encrypted.createNewFile()
        try {
            LegacyDatabaseMigration.prepare(legacy, encrypted, passphrase)
            fail("Empty authoritative storage must fail closed")
        } catch (_: IllegalStateException) {
            assertTrue(legacy.exists())
            assertEquals(0L, encrypted.length())
        } catch (_: android.database.sqlite.SQLiteException) {
            assertTrue(legacy.exists())
            assertEquals(0L, encrypted.length())
        }
        passphrase.fill(0)
    }

    @Test
    fun losingWrappedKeyOrKeystoreKeyDoesNotCreateReplacementCredentials() {
        val passphrase = keys.loadOrCreate(false)
        val file = File(directory, "keys/database-key.v1")
        val original = file.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
        try {
            keys.loadOrCreate(true)
            fail("Missing Keystore key must fail")
        } catch (_: IllegalStateException) {
            assertArrayEquals(original, file.readBytes())
        }
        file.delete()
        try {
            keys.loadOrCreate(true)
            fail("Missing wrapped key must fail")
        } catch (_: IllegalStateException) {
            assertFalse(file.exists())
        }
        passphrase.fill(0)
    }
}
