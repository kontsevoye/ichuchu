package io.github.kontsevoye.ichuchu.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kontsevoye.ichuchu.data.backup.IchuchuBackupRepository
import io.github.kontsevoye.ichuchu.data.backup.buildBackupPayload
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.service.backup.IchuchuBackupCodec
import io.github.kontsevoye.ichuchu.service.ssh.SshJumpHost
import io.github.kontsevoye.ichuchu.service.ssh.SshRouteSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostOrderingStorageTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun host(name: String) =
        HostProfile(name = name, host = "fixture.internal", username = "fixture")

    @Test
    fun creationOrderAndManualOrderSurviveReopenAndStaleEdits() =
        runBlocking(Dispatchers.IO) {
            val name = "host-order-${System.nanoTime()}.db"
            var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                var dao = db.hostProfileDao()
                val ids = listOf("Z", "A", "M").map { dao.saveValidated(host(it)) }
                assertEquals(listOf("Z", "A", "M"), dao.observeAll().first().map { it.name })
                val staleEditor = dao.getById(ids[2])!!
                dao.reorder(listOf(ids[2], ids[0], ids[1]))
                dao.saveValidated(staleEditor.copy(name = "renamed"))
                val addedId = dao.saveValidated(host("B"))
                assertEquals(
                    listOf(ids[2], ids[0], ids[1], addedId),
                    dao.observeAll().first().map { it.id },
                )
                db.close()
                db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
                dao = db.hostProfileDao()
                assertEquals(listOf(ids[2], ids[0], ids[1], addedId), dao.getAll().map { it.id })
                dao.deleteUnused(dao.getById(ids[0])!!)
                // A drop begun before delete/add must not resurrect deleted IDs or lose the new
                // host.
                dao.reorder(listOf(ids[1], ids[0], ids[2]))
                assertEquals(listOf(ids[1], ids[2], addedId), dao.getAll().map { it.id })
                assertTrue(runCatching { dao.reorder(listOf(ids[1], ids[1])) }.isFailure)
                assertEquals(listOf(ids[1], ids[2], addedId), dao.getAll().map { it.id })
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun backupKeepsManualOrderAndImportsAfterExistingHosts() =
        runBlocking(Dispatchers.IO) {
            val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val ids =
                    listOf("Z", "A", "M").map { source.hostProfileDao().saveValidated(host(it)) }
                source.hostProfileDao().reorder(ids.reversed())
                val bytes =
                    IchuchuBackupCodec.encodePayload(
                        buildBackupPayload(emptyList(), source.hostProfileDao().getAll())
                    )
                val payload = IchuchuBackupCodec.decodePayload(bytes)
                target.hostProfileDao().saveValidated(host("existing"))
                IchuchuBackupRepository(target).importPayload(payload)
                assertEquals(
                    listOf("existing", "M", "A", "Z"),
                    target.hostProfileDao().getAll().map { it.name },
                )
                target.hostProfileDao().saveValidated(host("new"))
                assertEquals("new", target.hostProfileDao().getAll().last().name)
            } finally {
                source.close()
                target.close()
            }
        }

    @Test
    fun reorderingDoesNotInvalidateAnOpenSshRoute() =
        runBlocking(Dispatchers.IO) {
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val dao = db.hostProfileDao()
                val jumpId = dao.saveValidated(host("jump"))
                val targetId =
                    dao.saveValidated(
                        host("target").copy(host = "target.internal", jumpHostIds = listOf(jumpId))
                    )
                val target = dao.getById(targetId)!!
                val snapshot =
                    SshRouteSnapshot(
                        db,
                        target,
                        SshJumpHost.resolve(target, dao.getAll(), emptyList()),
                        emptyList(),
                    )
                snapshot.requireCurrent()
                dao.reorder(listOf(targetId, jumpId))
                snapshot.requireCurrent()
            } finally {
                db.close()
            }
        }

    @Test
    fun version12MigrationUsesCreationIdsAndPreservesRouteAndCredentials() =
        runBlocking(Dispatchers.IO) {
            val name = "host-order-migration-${System.nanoTime()}.db"
            val schemaFile =
                listOf("schemas", "app/schemas", "android/app/schemas")
                    .map { File(it, "io.github.kontsevoye.ichuchu.data.db.AppDatabase/12.json") }
                    .first { it.isFile }
            val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
            context.openOrCreateDatabase(name, 0, null).use { old ->
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
                old.execSQL(
                    "INSERT INTO host_profiles (id,name,host,port,username,password,keyId,keyPassphrase,transport,authMethod,requireAuthOnConnect,jumpHostIds) VALUES (10,'Z','gateway',22,'fixture','synthetic-password',NULL,'','SSH','Password',0,'')"
                )
                old.execSQL(
                    "INSERT INTO host_profiles (id,name,host,port,username,password,keyId,keyPassphrase,transport,authMethod,requireAuthOnConnect,jumpHostIds) VALUES (30,'A','target',22,'fixture','synthetic-password',NULL,'','SSH','Password',0,'10')"
                )
                old.version = 12
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                val dao = db.hostProfileDao()
                assertEquals(listOf(10L, 30L), dao.observeAll().first().map { it.id })
                assertEquals(listOf(10L, 30L), dao.getAll().map { it.sortOrder })
                assertEquals(listOf(10L), dao.getById(30)!!.jumpHostIds)
                assertEquals("synthetic-password", dao.getById(30)!!.password)
                val newId = dao.saveValidated(host("new"))
                assertEquals(newId, dao.getAll().last().id)
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
}
