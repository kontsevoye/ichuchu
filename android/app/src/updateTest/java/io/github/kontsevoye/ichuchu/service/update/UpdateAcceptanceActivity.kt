package io.github.kontsevoye.ichuchu.service.update

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import io.github.kontsevoye.ichuchu.MainActivity
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.data.repository.SettingsRepository
import io.github.kontsevoye.ichuchu.data.update.*
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.SshKey
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.service.terminal.SessionStatus
import io.github.kontsevoye.ichuchu.service.terminal.TabSpec
import io.github.kontsevoye.ichuchu.service.terminal.TerminalSessionRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Synthetic acceptance only; never compiled into debug/release or shipped by CI. */
class UpdateAcceptanceActivity : FragmentActivity() {
    private val fixture
        get() = File(cacheDir, "next-update.apk")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { text = "Running isolated updater acceptance" }
        setContentView(label)
        lifecycleScope.launch {
            try {
                when (intent.getStringExtra("mode")) {
                    "verify" -> withContext(Dispatchers.IO) { verifyRetainedData() }
                    else -> {
                        withContext(Dispatchers.IO) { prepare() }
                        testSessionGuard()
                        withContext(Dispatchers.IO) { testSchedule() }
                        startActivity(
                            Intent(this@UpdateAcceptanceActivity, MainActivity::class.java)
                                .putExtra(UpdateNotifications.OPEN_UPDATES, true)
                        )
                    }
                }
                label.text = "PASS"
                File(filesDir, "updater-acceptance-result").writeText("PASS")
            } catch (error: Exception) {
                label.text = "FAIL: ${error.javaClass.simpleName}: ${error.message}"
                File(filesDir, "updater-acceptance-result").writeText(label.text.toString())
            }
        }
    }

    private fun candidate(file: File): UpdateCandidate {
        val identity = ApkVerifier(this).installed()
        val artifact =
            UpdateArtifact("arm64-v8a", "ichuchu.apk", file.length(), ApkVerifier.digest(file))
        val metadata =
            UpdateManifest(
                1,
                "github",
                "stable",
                packageName,
                1000002,
                "0.0.2",
                24,
                "b".repeat(40),
                identity.certificates.single(),
                listOf(artifact),
            )
        return UpdateCandidate(
            metadata,
            artifact,
            "https://github.com/kontsevoye/ichuchu/releases/download/v0.0.2/ichuchu.apk",
            "https://github.com/kontsevoye/ichuchu/releases/tag/v0.0.2",
            "Synthetic upgrade acceptance",
        )
    }

    private suspend fun prepare() {
        check(packageName.endsWith(".updatetest"))
        check(ApkVerifier(this).installed().versionCode == 1000001L)
        assets.open("next-update.apk").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        val good = candidate(fixture)
        val verifier = ApkVerifier(this)
        val config = UpdateConfig(true, "kontsevoye/ichuchu", "github")
        verifier.verify(fixture, good, config)
        fun reject(file: File, value: UpdateCandidate) {
            check(
                runCatching { verifier.verify(file, value, config) }.exceptionOrNull()
                    is UpdateException
            )
        }
        reject(fixture, good.copy(manifest = good.manifest.copy(packageName = "different.package")))
        reject(
            fixture,
            good.copy(manifest = good.manifest.copy(versionName = "0.0.3", versionCode = 1000003)),
        )
        reject(
            fixture,
            good.copy(manifest = good.manifest.copy(versionName = "0.0.1", versionCode = 1000001)),
        )
        reject(fixture, good.copy(manifest = good.manifest.copy(minSdk = 25)))
        val truncated =
            File(cacheDir, "truncated.apk").apply {
                writeBytes(fixture.readBytes().take(100).toByteArray())
            }
        reject(truncated, good)
        truncated.delete()
        val wrong = File(cacheDir, "wrong-signer.apk")
        assets.open("wrong-signer.apk").use { input ->
            wrong.outputStream().use { input.copyTo(it) }
        }
        reject(wrong, candidate(wrong))
        wrong.delete()

        val db = AppDatabase.getInstance(this)
        val keyId =
            db.sshKeyDao()
                .insert(
                    SshKey(
                        name = "update-acceptance",
                        algorithm = "TEST",
                        privateKeyPem = "synthetic-private-value",
                        publicKeyOpenSsh = "synthetic-public-value",
                        createdAtEpochMs = 1,
                    )
                )
        val hostId =
            db.hostProfileDao()
                .insert(
                    HostProfile(
                        name = "update-acceptance",
                        host = "invalid.example",
                        username = "synthetic",
                        password = "synthetic-password",
                        keyId = keyId,
                        keyPassphrase = "synthetic-passphrase",
                    )
                )
        getSharedPreferences("updater-acceptance", 0)
            .edit()
            .putLong("key", keyId)
            .putLong("host", hostId)
            .putString("envelope", ApkVerifier.digest(File(noBackupFilesDir, "database-key.v1")))
            .commit()
        SettingsRepository.getInstance(this).setKeepScreenAwake(true)
        val ready = File(File(cacheDir, "updates").apply { mkdirs() }, "${UUID.randomUUID()}.apk")
        fixture.copyTo(ready, overwrite = true)
        val store = UpdateStore(this)
        store.candidate = good
        store.filename = ready.name
        fixture.delete()
    }

    private suspend fun testSessionGuard() {
        val sessions = TerminalSessionRepository.getInstance(application)
        val spec =
            TabSpec(
                null,
                "acceptance shell",
                "localhost",
                username = "android",
                authMethod = AuthMethod.None,
                transport = Transport.LocalShell,
            )
        val tab = sessions.openTab(spec)
        withTimeout(20_000) { tab.sessionState.first { it.status == SessionStatus.Connected } }
        check(!sessions.beginUpdateInstallation(false))
        check(sessions.tabs.value.any { it.id == tab.id })
        check(sessions.beginUpdateInstallation(true))
        check(sessions.tabs.value.isEmpty())
        check(sessions.tryOpenTab(spec) == null)
        sessions.endUpdateInstallation()
    }

    private fun testSchedule() {
        val store = UpdateStore(this)
        check(!store.automatic)
        store.automatic = true
        UpdateScheduler.reconcile(this)
        val work =
            WorkManager.getInstance(this)
                .getWorkInfosForUniqueWork("ichuchu-stable-update-check")
                .get(10, TimeUnit.SECONDS)
        check(work.any { !it.state.isFinished })
        store.automatic = false
        UpdateScheduler.reconcile(this)
    }

    private suspend fun verifyRetainedData() {
        check(ApkVerifier(this).installed().versionCode == 1000002L)
        val prefs = getSharedPreferences("updater-acceptance", 0)
        val db = AppDatabase.getInstance(this)
        val host = db.hostProfileDao().getById(prefs.getLong("host", -1))!!
        val key = db.sshKeyDao().getById(prefs.getLong("key", -1))!!
        check(
            host.password == "synthetic-password" &&
                host.keyPassphrase == "synthetic-passphrase" &&
                host.keyId == key.id
        )
        check(key.privateKeyPem == "synthetic-private-value")
        check(SettingsRepository.getInstance(this).keepScreenAwake.value)
        check(
            ApkVerifier.digest(File(noBackupFilesDir, "database-key.v1")) ==
                prefs.getString("envelope", null)
        )
        val raw = getDatabasePath("ichuchu-encrypted.db").readBytes()
        check(!String(raw.take(16).toByteArray()).startsWith("SQLite format 3"))
        check(!String(raw).contains("synthetic-private-value"))
        UpdateServices.get(this).let { check(it.store.sessionId == -1) }
    }
}
