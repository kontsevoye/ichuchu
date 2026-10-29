package io.github.kontsevoye.ichuchu

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.service.ssh.HostKeyStore
import io.github.kontsevoye.ichuchu.service.ssh.SshJumpHost
import io.github.kontsevoye.ichuchu.service.ssh.sshTrustHost
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.AddServerScreen
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.AddServerViewModel
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.ConnectionTestStatus
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit opt-in because it writes synthetic profiles in the disposable app. */
class JumpHostEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectReorderAndSaveExplicitRoute() = exerciseRoute(changedKeys = false)

    @Test fun changedHostKeysRequireExplicitReplacement() = exerciseRoute(changedKeys = true)

    private fun exerciseRoute(changedKeys: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sshJumpFixture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val dao = AppDatabase.getInstance(app).hostProfileDao()
        fun profile(name: String, address: String, port: Int, password: String) =
            HostProfile(
                name = name,
                host = address,
                port = port,
                username = "fixture",
                password = password,
            )
        val ids = runBlocking {
            listOf(
                dao.insert(profile("Jump root", "10.0.2.2", 22227, "fixture-0")),
                dao.insert(profile("Jump gateway", "jump-1.internal", 2201, "fixture-1")),
                dao.insert(profile("Jump target", "jump-2.internal", 2202, "fixture-2")),
            )
        }
        val profiles = runBlocking { ids.map { dao.getById(it)!! } }
        val hops = profiles.take(2).map { SshJumpHost(it) }
        val prefs =
            app.getSharedPreferences(HostKeyStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val store = HostKeyStore(prefs)
        profiles.forEachIndexed { index, profile ->
            val trust = sshTrustHost(profile.host, profile.port, profile.username, hops.take(index))
            if (changedKeys) {
                store.saveKey(trust, profile.port, "ED25519", byteArrayOf(1, 2, 3))
            } else {
                prefs.edit().remove("$trust:${profile.port}:ED25519").commit()
            }
        }
        val vm = AddServerViewModel(app, ids[2])
        var saved = false
        try {
            compose.setContent { IchuTheme { AddServerScreen(vm, onBack = { saved = true }) } }
            compose.waitUntil(5000) { vm.hosts.value.size >= 3 && vm.form.value.id == ids[2] }
            compose.onNodeWithText("add jump host").performScrollTo().performClick()
            compose
                .onNodeWithText("Jump root · fixture@10.0.2.2:22227")
                .performScrollTo()
                .performClick()
            compose.onNodeWithText("add jump host").performScrollTo().performClick()
            compose
                .onNodeWithText("Jump gateway · fixture@jump-1.internal:2201")
                .performScrollTo()
                .performClick()
            assertEquals(ids.take(2), vm.form.value.jumpHostIds)
            compose.onAllNodesWithText("↑")[1].performScrollTo().performClick()
            assertEquals(ids.take(2).reversed(), vm.form.value.jumpHostIds)
            compose.onAllNodesWithText("↑")[1].performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(ids.take(2), vm.form.value.jumpHostIds)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(app.cacheDir, "jump-editor.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            compose.onNodeWithText("test connection").performScrollTo().performClick()
            for (node in 1..3) {
                compose.waitUntil(10_000) {
                    vm.hostKeyPrompt.value?.routeLabel?.startsWith("$node/3") == true ||
                        vm.testState.value.status == ConnectionTestStatus.Success ||
                        vm.testState.value.status == ConnectionTestStatus.Error
                }
                val prompt =
                    vm.hostKeyPrompt.value
                        ?: error("Expected host key prompt for $node/3; ${vm.testState.value}")
                assertTrue(prompt.routeLabel, prompt.routeLabel.startsWith("$node/3"))
                assertEquals(changedKeys, prompt.previousFingerprint != null)
                compose
                    .onNodeWithText(
                        if (prompt.previousFingerprint == null) "Accept" else "Replace trusted key"
                    )
                    .performClick()
            }
            compose.waitUntil(10_000) { vm.testState.value.status != ConnectionTestStatus.Running }
            assertEquals(
                vm.testState.value.message,
                ConnectionTestStatus.Success,
                vm.testState.value.status,
            )
            compose.onNodeWithText("save").performScrollTo().performClick()
            compose.waitUntil(5000) { saved }
            assertEquals(ids.take(2), runBlocking { dao.getById(ids[2])!!.jumpHostIds })
        } finally {
            vm.cancelTest()
            runBlocking {
                ids.asReversed().forEach { dao.getById(it)?.let { host -> dao.delete(host) } }
            }
        }
    }
}
