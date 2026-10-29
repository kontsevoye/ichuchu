package io.github.kontsevoye.ichuchu.data.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateRepositoryTest {
    private lateinit var app: Application
    private lateinit var store: UpdateStore
    private lateinit var server: MockWebServer
    private lateinit var http: UpdateHttp
    private val inspector =
        object : UpdateApkInspector {
            override fun installed() = identity()

            override fun verify(file: File, candidate: UpdateCandidate, config: UpdateConfig) {}
        }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("ichuchu_updates", 0).edit().clear().commit()
        File(app.cacheDir, "updates").deleteRecursively()
        store = UpdateStore(app)
        server = MockWebServer().apply { start() }
        http = UpdateHttp(allowed = { it.host == "localhost" && it.port == server.port })
    }

    @After
    fun cleanup() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun repository(timeout: Long = 45_000): UpdateRepository {
        val base = server.url("/").toString().removeSuffix("/")
        return UpdateRepository(
            app,
            config,
            store,
            GitHubUpdateSource(config, store, http, base, base, timeout),
            UpdateDownloader(http),
            inspector,
        )
    }

    @Test
    fun concurrentChecksShareOneRequestAndKeepThePreviousCandidateOnError() =
        runBlocking<Unit> {
            val candidate =
                UpdateCandidate(
                    manifest(),
                    manifest().artifacts.single(),
                    server.url("/apk").toString(),
                    "https://github.com",
                    "notes",
                )
            store.candidate = candidate
            val updates = repository()
            server.enqueue(
                MockResponse().setResponseCode(404).setHeadersDelay(150, TimeUnit.MILLISECONDS)
            )
            listOf(async { updates.check() }, async { updates.check() }).awaitAll()
            assertEquals(1, server.requestCount)
            assertEquals(UpdatePhase.Error, updates.state.value.phase)
            assertEquals(candidate, updates.state.value.candidate)
        }

    @Test
    fun aTimedOutCheckIsAnErrorRatherThanUpToDateOrSilentCancellation() =
        runBlocking<Unit> {
            val updates = repository(timeout = 100)
            server.enqueue(
                MockResponse().setResponseCode(404).setHeadersDelay(500, TimeUnit.MILLISECONDS)
            )
            updates.check()
            assertEquals(UpdatePhase.Error, updates.state.value.phase)
            assertEquals(UpdateFailure.Network, updates.state.value.failure)
            assertEquals("Update check timed out", updates.state.value.message)
        }

    @Test
    fun cancellationRemovesPartialFilesAndRetainsTheCandidate() =
        runBlocking<Unit> {
            val candidate =
                UpdateCandidate(
                    manifest(),
                    manifest().artifacts.single(),
                    server.url("/apk").toString(),
                    "https://github.com",
                    "notes",
                )
            store.candidate = candidate
            val updates = repository()
            server.enqueue(
                MockResponse().setBody("0123456789").throttleBody(1, 200, TimeUnit.MILLISECONDS)
            )
            updates.download()
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            updates.cancelDownload()
            assertEquals(UpdatePhase.Available, updates.state.value.phase)
            assertEquals(candidate, updates.state.value.candidate)
            assertNull(updates.readyFile())
        }

    @Test
    fun callbackMustMatchTheDurableSessionAndNonceAndCannotBypassLockedUi() {
        val updates = repository()
        store.saveSession(43, 1000002, "private-callback")
        val confirm = Intent("android.intent.action.CONFIRM_INSTALL")
        fun callback(action: String, id: Int) =
            Intent(action)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                .putExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_PENDING_USER_ACTION,
                )
                .putExtra(Intent.EXTRA_INTENT, confirm)
        updates.receiveInstallResult(callback("wrong", 43))
        assertEquals(UpdatePhase.Idle, updates.state.value.phase)
        updates.receiveInstallResult(callback("private-callback", 44))
        assertEquals(UpdatePhase.Idle, updates.state.value.phase)
        updates.receiveInstallResult(callback("private-callback", 43))
        val generation = updates.state.value.confirmationGeneration
        updates.receiveInstallResult(callback("private-callback", 43))
        assertEquals(generation + 1, updates.state.value.confirmationGeneration)
        assertTrue(store.waitingForUser)
        assertEquals(UpdatePhase.UserConfirmation, updates.state.value.phase)
        assertNull(updates.takeConfirmation())
        updates.interactive = true
        assertEquals(confirm.action, updates.takeConfirmation()?.action)
        assertNull(updates.takeConfirmation())
    }
    @Test fun optingOutCancelsBackgroundChecksButLeavesExplicitManualChecksRunning() = runBlocking<Unit> {
        for (background in listOf(true, false)) {
            val updates = repository()
            updates.setAutomatic(true)
            server.enqueue(MockResponse().setResponseCode(404).setHeadersDelay(500, TimeUnit.MILLISECONDS))
            val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { updates.check(background) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            updates.setAutomatic(false)
            pending.await()
            assertEquals(if (background) UpdatePhase.Idle else UpdatePhase.Error, updates.state.value.phase)
            assertFalse(updates.automatic.value)
        }
        assertEquals(2, server.requestCount)
    }

}
