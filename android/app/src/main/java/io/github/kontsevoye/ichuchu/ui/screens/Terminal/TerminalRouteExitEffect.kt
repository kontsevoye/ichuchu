package io.github.kontsevoye.ichuchu.ui.screens.Terminal

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import io.github.kontsevoye.ichuchu.ui.terminal.TerminalInputView

/** Wait for the first tab to open; leave the route when its last tab closes. */
@Composable
internal fun TerminalRouteExitEffect(
    hostId: Long?,
    openLocalShell: Boolean,
    hasTabs: Boolean,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    var hadTabs by remember(hostId, openLocalShell) { mutableStateOf(false) }
    val navigateBack by rememberUpdatedState(onBack)
    LaunchedEffect(hostId, openLocalShell, hasTabs) {
        if (hasTabs) {
            hadTabs = true
        } else if (hadTabs) {
            hadTabs = false
            dismissTerminalKeyboard(view, keyboard)
            navigateBack()
        }
    }
}

/** Clear both native EditText and Compose IME ownership while the window token is valid. */
internal fun dismissTerminalKeyboard(
    view: View,
    keyboard: SoftwareKeyboardController?,
    inputView: TerminalInputView? = null,
) {
    keyboard?.hide()
    val imm = view.context.getSystemService(InputMethodManager::class.java)
    imm?.hideSoftInputFromWindow(view.windowToken, 0)
    val focused = inputView ?: view.rootView.findFocus()
    if (focused is TerminalInputView) {
        // EditText can immediately regain focus while navigation is still removing its screen.
        focused.isFocusable = false
        focused.isFocusableInTouchMode = false
    }
    focused?.clearFocus()
}
