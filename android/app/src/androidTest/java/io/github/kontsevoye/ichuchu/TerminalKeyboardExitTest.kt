package io.github.kontsevoye.ichuchu

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.service.terminal.SessionStatus
import io.github.kontsevoye.ichuchu.service.terminal.TabSpec
import io.github.kontsevoye.ichuchu.service.terminal.TerminalSessionRepository
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.screens.Terminal.TerminalScreen
import io.github.kontsevoye.ichuchu.ui.screens.Terminal.TerminalViewModel
import io.github.kontsevoye.ichuchu.ui.terminal.TerminalInputView
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Full TerminalScreen and real IME; synthetic network sessions on a disposable emulator only. */
class TerminalKeyboardExitTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ctrlDClosesKeyboardOnLastSshTab() = checkExit("\u0004")

    @Test fun exitClosesKeyboardOnLastSshTab() = checkExit("exit\n")

    @Test fun manualBackClosesKeyboard() = checkExit(null)

    @Test fun closingOneTabKeepsKeyboardForTheRemainingTab() = checkExit("\u0004", twoTabs = true)

    @Test fun ctrlDClosesKeyboardOnLocalShell() = checkExit("\u0004", localShell = true)

    @Test
    fun ctrlDClosesKeyboardOnMosh() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("moshExitFixture") == "true")
        checkExit("\u0004", transport = Transport.Mosh)
    }

    private fun checkExit(
        command: String?,
        twoTabs: Boolean = false,
        localShell: Boolean = false,
        transport: Transport = Transport.SSH,
    ) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shellExitFixture") == "true")
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val vm = TerminalViewModel(app)
        val repo = TerminalSessionRepository.getInstance(app)
        var showTerminal by mutableStateOf(true)
        lateinit var root: View
        compose.runOnUiThread {
            repeat(if (twoTabs) 2 else 1) {
                vm.openTab(
                    TabSpec(
                        hostId = if (localShell) null else 42,
                        host = "10.0.2.2",
                        port = if (transport == Transport.Mosh) 22226 else 22225,
                        username = "synthetic-shell",
                        authMethod = AuthMethod.None,
                        transport = if (localShell) Transport.LocalShell else transport,
                    )
                )
            }
        }
        compose.setContent {
            root = LocalView.current
            IchuTheme {
                if (showTerminal) {
                    TerminalScreen(
                        vm,
                        if (localShell) null else 42,
                        onOpenSettings = {},
                        onBack = { showTerminal = false },
                        openLocalShell = localShell,
                    )
                } else {
                    IchuText("hosts after exit")
                }
            }
        }
        val tab = repo.tabs.value.last()
        try {
            compose.waitUntil(10_000) {
                repo.tabs.value.forEach { current ->
                    current.hostKeyPrompt.value?.let { current.engine.respondToHostKey(true) }
                }
                repo.tabs.value.all { it.sessionState.value.status == SessionStatus.Connected }
            }
            if (transport == Transport.Mosh) {
                compose.waitUntil(10_000) {
                    tab.sessionState.value.snapshot
                        ?.codepoints
                        ?.map { it.toChar() }
                        ?.joinToString("")
                        ?.contains("MOSH_FIXTURE_READY") == true
                }
            }
            fun input(view: View): TerminalInputView? {
                if (view is TerminalInputView) return view
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        input(view.getChildAt(i))?.let {
                            return it
                        }
                    }
                }
                return null
            }
            compose.waitUntil(5000) { input(root.rootView) != null }
            compose.runOnIdle {
                input(root.rootView)!!.showKeyboard(
                    app.getSystemService(InputMethodManager::class.java)
                )
            }
            compose.waitUntil(5000) {
                ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) ==
                    true
            }
            assertTrue(
                "Real keyboard was visible before exit",
                ViewCompat.getRootWindowInsets(root)!!.isVisible(WindowInsetsCompat.Type.ime()),
            )
            if (command != null) {
                tab.engine.writeText(command)
            } else {
                compose.runOnIdle { showTerminal = false }
            }
            if (twoTabs) {
                compose.waitUntil(6000) { repo.tabs.value.size == 1 }
                compose.runOnIdle {
                    assertTrue(showTerminal)
                    assertTrue(
                        ViewCompat.getRootWindowInsets(root)!!.isVisible(
                            WindowInsetsCompat.Type.ime()
                        )
                    )
                }
                repo.tabs.value.single().engine.writeText("exit\n")
            }
            compose.waitUntil(6000) { !showTerminal }
            compose.waitUntil(5000) {
                ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) ==
                    false
            }
            android.os.SystemClock.sleep(400)
            compose.runOnIdle {
                assertFalse(
                    ViewCompat.getRootWindowInsets(root)!!.isVisible(WindowInsetsCompat.Type.ime())
                )
                assertFalse(root.rootView.findFocus() is TerminalInputView)
            }
        } finally {
            compose.runOnIdle { repo.tabs.value.toList().forEach { repo.closeTab(it.id) } }
        }
    }
}
