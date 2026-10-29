package io.github.kontsevoye.ichuchu.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.kontsevoye.ichuchu.data.backup.IchuchuBackupRepository
import io.github.kontsevoye.ichuchu.data.backup.buildBackupPayload
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.service.ssh.SshJumpHost
import io.github.kontsevoye.ichuchu.service.ssh.SshRouteSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JumpRouteStorageTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun host(id: Long, name: String) =
        HostProfile(
            id = id,
            name = name,
            host = "$name.internal",
            username = "fixture",
            password = "synthetic-password",
        )

    @Test
    fun importsForwardReferencesUsingNewIdsAndPreservesDependenciesOnEdit() =
        runBlocking(Dispatchers.IO) {
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val dao = db.hostProfileDao()
                dao.insert(host(20, "existing"))
                val target = host(10, "target").copy(jumpHostIds = listOf(20))
                val gateway = host(20, "gateway")
                IchuchuBackupRepository(db)
                    .importPayload(buildBackupPayload(emptyList(), listOf(target, gateway)))
                val imported = dao.getAll()
                val localTarget = imported.single { it.name == "target" }
                val localGateway = imported.single { it.name == "gateway" }
                assertNotEquals(20L, localGateway.id)
                assertEquals(listOf(localGateway.id), localTarget.jumpHostIds)
                dao.saveValidated(localGateway.copy(name = "renamed"))
                assertEquals(localTarget.jumpHostIds, dao.getById(localTarget.id)!!.jumpHostIds)
                assertTrue(runCatching { dao.deleteUnused(localGateway) }.isFailure)
                assertEquals(3, dao.getAll().size)
                val snapshot =
                    SshRouteSnapshot(
                        db,
                        localTarget,
                        SshJumpHost.resolve(localTarget, dao.getAll(), emptyList()),
                        emptyList(),
                    )
                snapshot.requireCurrent()
                dao.saveValidated(localGateway.copy(host = "changed.internal"))
                assertTrue(runCatching { snapshot.requireCurrent() }.isFailure)
            } finally {
                db.close()
            }
        }

    @Test
    fun version11MigrationPreservesCredentialsAndDefaultsToDirect() =
        runBlocking(Dispatchers.IO) {
            val name = "jump-migration-${System.nanoTime()}.db"
            val schemaFile =
                listOf("schemas", "app/schemas", "android/app/schemas")
                    .map { File(it, "io.github.kontsevoye.ichuchu.data.db.AppDatabase/11.json") }
                    .first { it.isFile }
            val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
            val helper =
                FrameworkSQLiteOpenHelperFactory()
                    .create(
                        SupportSQLiteOpenHelper.Configuration.builder(context)
                            .name(name)
                            .callback(
                                object : SupportSQLiteOpenHelper.Callback(11) {
                                    override fun onCreate(db: SupportSQLiteDatabase) {
                                        val entities = schema.getJSONArray("entities")
                                        for (i in 0 until entities.length()) {
                                            val entity = entities.getJSONObject(i)
                                            db.execSQL(
                                                entity
                                                    .getString("createSql")
                                                    .replace(
                                                        "\${TABLE_NAME}",
                                                        entity.getString("tableName"),
                                                    )
                                            )
                                            val indices = entity.getJSONArray("indices")
                                            for (j in 0 until indices.length()) {
                                                db.execSQL(
                                                indices
                                                    .getJSONObject(j)
                                                    .getString("createSql")
                                                    .replace(
                                                        "\${TABLE_NAME}",
                                                        entity.getString("tableName"),
                                                    )
                                            )
                                            }
                                        }
                                        val queries = schema.getJSONArray("setupQueries")
                                        for (i in 0 until queries.length()) {
                                            db.execSQL(
                                            queries.getString(i)
                                        )
                                        }
                                    }

                                    override fun onUpgrade(
                                        db: SupportSQLiteDatabase,
                                        oldVersion: Int,
                                        newVersion: Int,
                                    ) = error("Unexpected upgrade")
                                }
                            )
                            .build()
                    )
            try {
                helper.writableDatabase.execSQL(
                    "INSERT INTO host_profiles (id,name,host,port,username,password,keyId,keyPassphrase,transport,authMethod,requireAuthOnConnect,postConnectCommand,multiplexer) VALUES (1,'legacy','legacy.internal',2222,'fixture','synthetic-password',NULL,'synthetic-passphrase','SSH','Password',1,'echo ready','tmux')"
                )
                helper.close()
                val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
                try {
                    val profile = db.hostProfileDao().getById(1)!!
                    assertEquals(emptyList<Long>(), profile.jumpHostIds)
                    assertEquals("synthetic-password", profile.password)
                    assertEquals("synthetic-passphrase", profile.keyPassphrase)
                    assertEquals("echo ready", profile.postConnectCommand)
                    assertEquals(2222, profile.port)
                    assertTrue(profile.requireAuthOnConnect)
                } finally {
                    db.close()
                }
            } finally {
                helper.close()
                context.deleteDatabase(name)
            }
        }
}
