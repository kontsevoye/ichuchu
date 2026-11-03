package io.github.kontsevoye.ichuchu

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.data.repository.ImportResult
import io.github.kontsevoye.ichuchu.data.repository.KeyGenerationResult
import io.github.kontsevoye.ichuchu.data.repository.SshKeyRepository
import io.github.kontsevoye.ichuchu.ui.components.IchuSwitch
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.AddServerScreen
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.AddServerViewModel
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.ConnectionTestStatus
import io.github.kontsevoye.ichuchu.ui.screens.Settings.ThemeSelectorSection
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import io.github.kontsevoye.ichuchu.ui.theme.ThemeMode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic writes only; opt in on a disposable emulator with qaFixture=true. */
class QaRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app
        get() = instrumentation.targetContext.applicationContext as Application

    @Before
    fun requireDisposableApp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("qaFixture") == "true")
    }

    private fun withEditor(test: (AddServerViewModel) -> Unit) {
        val store = ViewModelStore()
        val vm = AddServerViewModel(app, null)
        store.put("qa", vm)
        try {
            compose.setContent { IchuTheme { AddServerScreen(vm, onBack = {}) } }
            compose.runOnIdle {
                vm.updateName("qa-${UUID.randomUUID()}")
                vm.updateHost("10.0.2.2")
                vm.updatePort("22227")
                vm.updateUsername("fixture")
                vm.updatePassword("fixture-0")
            }
            test(vm)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test
    fun repeatedSaveCreatesOnlyOneProfileAndNavigatesOnce() = withEditor { vm ->
        val name = vm.form.value.name
        val dao = AppDatabase.getInstance(app).hostProfileDao()
        var completed = 0
        try {
            compose.runOnIdle { repeat(8) { vm.save { completed++ } } }
            compose.waitUntil(5_000) { completed > 0 }
            val saved = runBlocking { dao.getAll().filter { it.name == name } }
            assertEquals("Repeated taps must not insert duplicate profiles", 1, saved.size)
            compose.runOnIdle { assertEquals(1, completed) }
        } finally {
            runBlocking { dao.getAll().filter { it.name == name }.forEach { dao.delete(it) } }
        }
    }

    @Test
    fun invalidPortDisablesConnectionTest() = withEditor { vm ->
        for (port in listOf("", "0", "65536", "-1", "abc", "999999999999")) {
            compose.runOnIdle { vm.updatePort(port) }
            compose.onNodeWithText("test connection").performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun changingConnectionFieldsCancelsThePendingHostKeyPrompt() = withEditor { vm ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("sshJumpFixture") == "true")
        compose.runOnIdle { vm.testConnection() }
        compose.waitUntil(10_000) {
            vm.hostKeyPrompt.value != null ||
                vm.testState.value.status == ConnectionTestStatus.Success
        }
        compose.runOnIdle {
            vm.updateHost("different.example.invalid")
            assertEquals(ConnectionTestStatus.Idle, vm.testState.value.status)
            assertNull(vm.hostKeyPrompt.value)
        }
    }

    @Test
    fun arbitraryTextContainingPrivateKeyIsRejected() = runBlocking {
        val dao = AppDatabase.getInstance(app).sshKeyDao()
        val name = "qa-invalid-${UUID.randomUUID()}"
        try {
            assertEquals(
                ImportResult.NotAPrivateKey,
                SshKeyRepository(dao).import(name, "This PRIVATE KEY is just arbitrary text"),
            )
        } finally {
            dao.getAll().filter { it.name.startsWith(name) }.forEach { dao.deleteById(it.id) }
        }
    }

    @Test
    fun importsGeneratedPlainAndEncryptedOpenSshKeys() = runBlocking {
        val dao = AppDatabase.getInstance(app).sshKeyDao()
        val repository = SshKeyRepository(dao)
        val name = "qa-import-${UUID.randomUUID()}"
        try {
            for (passphrase in listOf("", "synthetic passphrase")) {
                val generated = repository.generate(name, passphrase) as KeyGenerationResult.Success
                val result =
                    repository.import(name, generated.key.privateKeyPem) as ImportResult.Success
                assertEquals(generated.key.privateKeyPem.trim(), result.key.privateKeyPem)
                org.junit.Assert.assertTrue(result.publicKeyDerived)
                assertEquals(
                    generated.key.publicKeyOpenSsh.split(' ').take(2),
                    result.key.publicKeyOpenSsh.split(' ').take(2),
                )
                assertEquals(
                    ImportResult.NotAPrivateKey,
                    repository.import(name, generated.key.privateKeyPem.dropLast(35)),
                )
            }
        } finally {
            dao.getAll().filter { it.name.startsWith(name) }.forEach { dao.deleteById(it.id) }
        }
    }

    @Test
    fun switchesExposeCheckedStateToAccessibility() {
        compose.setContent {
            var checked by remember { mutableStateOf(false) }
            IchuTheme {
                IchuSwitch(checked, { checked = it }, modifier = Modifier.testTag("switch"))
            }
        }
        compose.onNodeWithTag("switch").assertIsOff().performClick().assertIsOn()
    }

    @Test
    fun narrowThemePickerKeepsDisclosureVisibleWithLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                IchuTheme {
                    Column(Modifier.width(288.dp)) {
                        ThemeSelectorSection(
                            darkThemeName = "Catppuccin Mocha",
                            onDarkThemeSelected = {},
                            lightThemeName = "Catppuccin Latte",
                            onLightThemeSelected = {},
                            themeMode = ThemeMode.Light,
                            onThemeModeChanged = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("▼", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("]", useUnmergedTree = true).assertIsDisplayed()
        compose.waitForIdle()
        capture("theme-large-narrow")
        compose.onNodeWithText("Catppuccin Latte").performClick()
        compose.onNodeWithText("▲", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun largeFontSettingsKeepsBackupManageActionVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppRoot()
            }
        }
        compose.onNodeWithText("settings").performClick()
        compose.onNodeWithText("manage").performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("manage", useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult
        ) {
            it(layouts)
        }
        capture("settings-large")
        assertEquals(
            "Backup button text must fit on one readable line",
            1,
            layouts.single().lineCount,
        )
        val layout = layouts.single()
        // Compose rounds glyph layout to integer pixels; allow only that rounding.
        org.junit.Assert.assertTrue(
            "Backup text exceeds its width: $layout",
            layout.getLineRight(0) <= layout.size.width + 1f,
        )
        org.junit.Assert.assertTrue(
            "Backup text exceeds its height: $layout",
            layout.multiParagraph.height <= layout.size.height + 1f,
        )
        compose.onNodeWithText("manage").performClick()
        compose.onNodeWithText("export").assertExists()
    }

    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(app.cacheDir, "qa-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
