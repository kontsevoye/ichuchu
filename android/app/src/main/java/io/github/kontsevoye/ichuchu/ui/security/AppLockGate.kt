package io.github.kontsevoye.ichuchu.ui.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kontsevoye.ichuchu.ui.components.IchuButton
import io.github.kontsevoye.ichuchu.ui.components.IchuText

/** Keeps protected UI out of composition until verification succeeds in the foreground. */
@Composable
fun AppLockGate(
    enabled: Boolean,
    verify: ((VerificationResult) -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    var unlocked by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var authenticating by remember { mutableStateOf(false) }
    var foreground by remember {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentVerify by rememberUpdatedState(verify)

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    unlocked = false
                    // Credential verification can temporarily stop our Activity. Its eventual
                    // callback is still accepted only if the app is back in the foreground.
                    if (!authenticating) attempted = false
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(enabled, foreground, attempted) {
        if (!enabled) {
            unlocked = false
            attempted = false
        } else if (foreground && !unlocked && !attempted && !authenticating) {
            attempted = true
            authenticating = true
            currentVerify { result ->
                authenticating = false
                unlocked = currentEnabled && foreground && result == VerificationResult.Success
            }
        }
    }

    if (!enabled || unlocked) {
        content()
    } else {
        BackHandler { /* Back cannot reveal protected navigation. */ }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IchuText("Ichuchu is locked")
            IchuText("Verify with your device lock to continue.")
            IchuButton(onClick = { attempted = false }, enabled = !authenticating) {
                IchuText("Unlock")
            }
        }
    }
}
