package io.github.kontsevoye.ichuchu.service.terminal

import io.github.kontsevoye.ichuchu.model.Transport

internal enum class ReadLoopExitAction {
    Disconnect,
    RequireManualReconnect,
    AutomaticReconnect,
}

internal fun readLoopExitAction(
    transport: Transport?,
    shellExited: Boolean = false,
): ReadLoopExitAction =
    when {
        shellExited || transport == Transport.LocalShell -> ReadLoopExitAction.Disconnect
        transport == Transport.Mosh -> ReadLoopExitAction.RequireManualReconnect
        else -> ReadLoopExitAction.AutomaticReconnect
    }
