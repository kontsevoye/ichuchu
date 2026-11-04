package io.github.kontsevoye.ichuchu.ui.screens.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kontsevoye.ichuchu.service.agents.AgentStatus
import io.github.kontsevoye.ichuchu.service.agents.DeckSession
import io.github.kontsevoye.ichuchu.service.agents.DeckSessionAction
import io.github.kontsevoye.ichuchu.ui.components.IchuDialog
import io.github.kontsevoye.ichuchu.ui.components.IchuText

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentSessionActions(
    session: DeckSession,
    enabled: Boolean,
    onAction: (String, DeckSessionAction) -> Unit,
    onCancel: () -> Unit,
) {
    var confirmation by remember(session.id) { mutableStateOf<DeckSessionAction?>(null) }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, androidx.compose.ui.Alignment.End),
    ) {
        AgentButton("cancel", onCancel)
        DeckSessionAction.entries.forEach { action ->
            AgentButton(
                action.label,
                { confirmation = action },
                enabled &&
                    session.local &&
                    (action != DeckSessionAction.Stop || session.status != AgentStatus.Stopped),
                description =
                    "${action.label.replaceFirstChar { it.uppercase() }} session ${session.title}",
            )
        }
    }
    confirmation?.let { action ->
        IchuDialog(
            "${action.label.replaceFirstChar { it.uppercase() }} session?",
            action.label,
            dismissLabel = "Cancel",
            confirmEnabled = enabled,
            onConfirm = {
                confirmation = null
                onAction(session.id, action)
                onCancel()
            },
            onDismiss = { confirmation = null },
        ) {
            IchuText(session.title)
            IchuText(
                when (action) {
                    DeckSessionAction.Restart ->
                        "Current work will stop and the agent will restart in this session."
                    DeckSessionAction.Stop ->
                        "Current work will stop. The session will remain in the list and can be restarted."
                    DeckSessionAction.Delete ->
                        "Stop the agent and remove this session from the list. Project files and native conversation history are kept."
                }
            )
        }
    }
}
