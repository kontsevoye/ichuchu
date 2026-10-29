package io.github.kontsevoye.ichuchu

import android.graphics.Bitmap
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.ui.screens.Keys.ImportKeyDialog
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses real Android IME insets and only synthetic key material. */
@SdkSuppress(minSdkVersion = 30)
class KeyImportDialogImeTest {
    @get:Rule val compose = createComposeRule()
    private val pem =
        "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            (1..70).joinToString("\n") { "SYNTHETICKEYLINENOTFORAUTHENTICATION" } +
            "\n-----END OPENSSH PRIVATE KEY-----"

    private fun showAndPaste(onImport: (String) -> Unit) {
        compose.setContent {
            IchuTheme { ImportKeyDialog(onConfirm = { _, key -> onImport(key) }, onDismiss = {}) }
        }
        compose.onAllNodes(hasSetTextAction())[1].performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitUntil(8_000) {
            var visible = false
            instrumentation.runOnMainSync {
                visible =
                    WindowInspector.getGlobalWindowViews().any {
                        it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                    }
            }
            visible
        }
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(pem)
        compose.waitForIdle()
    }

    @Test
    fun importButtonRemainsVisibleAboveTheKeyboardAfterLongPaste() {
        var imported: String? = null
        showAndPaste { imported = it }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "key-import-with-ime.png")
                .outputStream()
                .use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            bitmap.recycle()
        }
        val importButton = compose.onNodeWithText("import").assertIsDisplayed()
        val buttonBottom = importButton.fetchSemanticsNode().boundsInWindow.bottom
        instrumentation.runOnMainSync {
            val windows = WindowInspector.getGlobalWindowViews()
            // A floating dialog resized above the IME has a zero local IME inset.
            // Use the host window to locate the keyboard in screen coordinates.
            val keyboardTop =
                windows
                    .mapNotNull { view ->
                        val insets = view.rootWindowInsets ?: return@mapNotNull null
                        val imeHeight = insets.getInsets(WindowInsets.Type.ime()).bottom
                        if (!insets.isVisible(WindowInsets.Type.ime()) || imeHeight == 0)
                            return@mapNotNull null
                        val location = IntArray(2)
                        view.getLocationOnScreen(location)
                        location[1] + view.height - imeHeight
                    }
                    .minOrNull()
            assertTrue("Software keyboard must be open", keyboardTop != null)
            val dialogLocation = IntArray(2)
            windows.last().getLocationOnScreen(dialogLocation)
            assertTrue(
                "Import button must be above the keyboard ($keyboardTop)",
                buttonBottom + dialogLocation[1] <= keyboardTop!!,
            )
        }
        compose.onNodeWithText("imported-key").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText(
                "paste or choose an OpenSSH private key. add a passphrase on the server if it's encrypted."
            )
            .performScrollTo()
            .assertIsDisplayed()
        importButton.assertIsDisplayed()
        importButton.performClick()
        compose.runOnIdle { assertEquals(pem, imported) }
    }

    @Test
    fun keyboardDoneSubmitsTheEntirePastedKey() {
        var imported: String? = null
        showAndPaste { imported = it }
        compose.onAllNodes(hasSetTextAction())[1].performImeAction()
        compose.runOnIdle { assertEquals(pem, imported) }
    }
}
