package io.github.kontsevoye.ichuchu.ui.screens.Settings

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kontsevoye.ichuchu.data.update.UpdatePhase
import io.github.kontsevoye.ichuchu.service.update.UpdateServices
import io.github.kontsevoye.ichuchu.ui.components.IchuDialog
import io.github.kontsevoye.ichuchu.ui.components.IchuText

/**
 * Composed only inside AppLockGate: neither permissions nor installer results can bypass
 * authentication.
 */
@Composable
fun UpdateInteractionHost() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val updates = remember(application) { UpdateServices.get(application) }
    val owner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val state by updates.state.collectAsStateWithLifecycle()
    DisposableEffect(owner, updates) {
        updates.interactive = resumed
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumed = true
                updates.interactive = true
            }
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                resumed = false
                updates.interactive = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            updates.interactive = false
            owner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(resumed, state.phase, state.confirmationGeneration) {
        if (resumed && state.phase == UpdatePhase.UserConfirmation) {
            updates.takeConfirmation()?.let { intent ->
                runCatching {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    .onFailure { updates.confirmationLaunchFailed() }
            }
        }
    }
    if (resumed && state.phase in setOf(UpdatePhase.Installing, UpdatePhase.UserConfirmation)) {
        IchuDialog(
            title = "Installing update",
            confirmLabel = "continue",
            confirmEnabled = state.phase == UpdatePhase.UserConfirmation,
            onConfirm = {
                val intent = updates.takeConfirmation()
                if (intent != null)
                    runCatching { context.startActivity(intent) }
                        .onFailure { updates.confirmationLaunchFailed() }
                else updates.resumeInstallation()
            },
            onDismiss = updates::cancelInstallation,
            dismissLabel = "cancel",
        ) {
            IchuText(
                state.message
                    ?: "Android will ask you to confirm. Terminal connections are paused until installation finishes or is cancelled."
            )
        }
    }
}
