package io.github.kontsevoye.ichuchu.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.kontsevoye.ichuchu.ui.screens.Terminal.TerminalRouteExitEffect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalRouteExitEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun lastTabHidesKeyboardBeforeNavigation() {
        val events = mutableListOf<String>()
        val hasTabs = mutableStateOf(true)
        val keyboard =
            object : SoftwareKeyboardController {
                override fun show() {
                    events += "show"
                }

                override fun hide() {
                    events += "hide"
                }
            }
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                TerminalRouteExitEffect(42, false, hasTabs.value, onBack = { events += "back" })
            }
        }
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        compose.runOnIdle { hasTabs.value = false }
        compose.runOnIdle { assertEquals(listOf("hide", "back"), events) }
    }
}
