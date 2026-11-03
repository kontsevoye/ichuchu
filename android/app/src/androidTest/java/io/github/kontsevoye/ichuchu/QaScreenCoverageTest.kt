package io.github.kontsevoye.ichuchu

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.screens.Files.FileBrowserEntry
import io.github.kontsevoye.ichuchu.ui.screens.Files.FileBrowserScreen
import io.github.kontsevoye.ichuchu.ui.screens.Files.FileBrowserUiState
import io.github.kontsevoye.ichuchu.ui.screens.Files.FileEntryType
import io.github.kontsevoye.ichuchu.ui.screens.Files.FileSort
import io.github.kontsevoye.ichuchu.ui.security.AppLockGate
import io.github.kontsevoye.ichuchu.ui.security.VerificationResult
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QaScreenCoverageTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fileStatesFilteringNavigationAndAccessibleActions() {
        var state by mutableStateOf(FileBrowserUiState(isLoading = true))
        var refreshed = 0
        var uploaded = 0
        var copied = ""
        var terminal = 0
        compose.setContent {
            IchuTheme {
                FileBrowserScreen(
                    state,
                    onGoUp = { state = state.copy(currentPath = "/") },
                    onRefresh = { refreshed++ },
                    onSelectSort = { state = state.copy(sort = it) },
                    onOpenPath = { state = state.copy(currentPath = it) },
                    onBackToTerminal = { terminal++ },
                    onCopyPath = { copied = it },
                    onImportFile = { uploaded++ },
                    onOpenFile = {},
                    onDownloadFile = {},
                    onDeleteFile = {},
                )
            }
        }
        compose.onNodeWithText("Loading...").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(isLoading = false, error = "Permission denied") }
        compose.onNodeWithText("Permission denied").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(error = null) }
        compose.onNodeWithText("Empty directory").assertIsDisplayed()
        compose.runOnIdle {
            state =
                state.copy(
                    entries =
                        listOf(
                            FileBrowserEntry("Папка α", "/Папка α", FileEntryType.Directory),
                            FileBrowserEntry(
                                "space 'quote'.bin",
                                "/space 'quote'.bin",
                                FileEntryType.File,
                                0,
                            ),
                        )
                )
        }
        compose.onNode(hasSetTextAction()).performTextInput("missing")
        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("пАПКА")
        compose.onNodeWithText("Папка α").performClick()
        compose.waitForIdle()
        assertEquals("/Папка α", state.currentPath)
        compose.onNodeWithText("space 'quote'.bin").assertExists()
        capture("files")
        compose.onNodeWithContentDescription("Refresh files").performClick()
        compose.onNodeWithContentDescription("Upload files").performClick()
        compose.onNodeWithContentDescription("Copy path: /space 'quote'.bin").performClick()
        compose.onNodeWithContentDescription("Sort files").performClick()
        compose.onNodeWithText("size").performClick()
        compose.onNodeWithContentDescription("Back to terminal").performClick()
        assertEquals(1, refreshed)
        assertEquals(1, uploaded)
        assertEquals(1, terminal)
        assertEquals("/space 'quote'.bin", copied)
        assertEquals(FileSort.Size, state.sort)
    }

    @Test
    fun settingsTerminalEditorsAndKeysOpenAndDismiss() {
        compose.setContent { AppRoot() }
        compose.onNodeWithText("settings").performClick()
        capture("settings-general")
        compose.onNodeWithText("terminal").performClick()
        compose.onNodeWithText("font size").assertExists()
        capture("settings-terminal")
        compose.onAllNodesWithText("customize").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("reset").assertExists()
        capture("accessory-editor")
        compose.onAllNodesWithText("x").onLast().performClick()
        compose.onNodeWithText("keys").performScrollTo().performClick()
        compose.onNodeWithText("import key").performScrollTo().performClick()
        compose.onNodeWithText("choose file").assertExists()
        capture("key-import")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("+ generate key").performClick()
        capture("key-generate")
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun lockedUiOnlyRevealsContentAfterSuccessfulVerification() {
        var result: ((VerificationResult) -> Unit)? = null
        compose.setContent {
            IchuTheme {
                AppLockGate(enabled = true, verify = { result = it }) {
                    IchuText("protected canary")
                }
            }
        }
        compose.onNodeWithText("protected canary").assertDoesNotExist()
        compose.onNodeWithText("Ichuchu is locked").assertIsDisplayed()
        capture("app-lock")
        compose.runOnIdle { result!!(VerificationResult.Failed) }
        compose.onNodeWithText("protected canary").assertDoesNotExist()
        compose.onNodeWithText("Unlock").performClick()
        compose.runOnIdle { result!!(VerificationResult.Success) }
        compose.onNodeWithText("protected canary").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "qa-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
