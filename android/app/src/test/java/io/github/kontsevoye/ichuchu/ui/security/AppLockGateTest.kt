package io.github.kontsevoye.ichuchu.ui.security

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockGateTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var callback: (VerificationResult) -> Unit
    private lateinit var owner: Owner

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private fun showGate() {
        compose.runOnUiThread {
            owner = Owner()
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                IchuTheme {
                    AppLockGate(enabled = true, verify = { callback = it }) {
                        IchuText("PROTECTED CONTENT")
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun cancellationAndUnavailableAuthCannotRevealContentAndAllowRetry() {
        showGate()
        compose.onNodeWithText("PROTECTED CONTENT").assertDoesNotExist()
        compose.runOnUiThread { callback(VerificationResult.Failed) }
        compose.onNodeWithText("PROTECTED CONTENT").assertDoesNotExist()
        compose.onNodeWithText("Unlock").performClick()
        compose.runOnUiThread { callback(VerificationResult.Unavailable) }
        compose.onNodeWithText("PROTECTED CONTENT").assertDoesNotExist()
        compose.onNodeWithText("Unlock").performClick()
        compose.runOnUiThread { callback(VerificationResult.Success) }
        compose.onNodeWithText("PROTECTED CONTENT").assertIsDisplayed()
    }

    @Test
    fun backgroundingRelocksAndLateSuccessCannotUnlockInBackground() {
        showGate()
        compose.runOnUiThread { callback(VerificationResult.Success) }
        compose.onNodeWithText("PROTECTED CONTENT").assertIsDisplayed()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithText("PROTECTED CONTENT").assertDoesNotExist()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("PROTECTED CONTENT").assertDoesNotExist()
        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            callback(VerificationResult.Success)
        }
        compose.onNodeWithText("PROTECTED CONTENT").assertDoesNotExist()
    }
}
