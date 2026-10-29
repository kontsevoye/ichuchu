package io.github.kontsevoye.ichuchu.service.terminal

import io.github.kontsevoye.ichuchu.model.Transport

internal enum class ReadLoopExitAction {
    Disconnect,
    RequireManualReconnect,
    AutomaticReconnect,
}

internal fun readLoopExitAction(transport: Transport?): ReadLoopExitAction =
    when (transport) {
        Transport.LocalShell -> ReadLoopExitAction.Disconnect
        Transport.Mosh -> ReadLoopExitAction.RequireManualReconnect
        else -> ReadLoopExitAction.AutomaticReconnect
    }
