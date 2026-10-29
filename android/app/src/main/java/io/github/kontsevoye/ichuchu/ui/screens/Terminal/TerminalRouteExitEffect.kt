package io.github.kontsevoye.ichuchu.ui.screens.Terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/** Wait for the first tab to open; leave the route when its last tab closes. */
@Composable
internal fun TerminalRouteExitEffect(
    hostId: Long?,
    openLocalShell: Boolean,
    hasTabs: Boolean,
    onBack: () -> Unit,
) {
    var hadTabs by remember(hostId, openLocalShell) { mutableStateOf(false) }
    val navigateBack by rememberUpdatedState(onBack)
    LaunchedEffect(hostId, openLocalShell, hasTabs) {
        if (hasTabs) {
            hadTabs = true
        } else if (hadTabs) {
            hadTabs = false
            navigateBack()
        }
    }
}
