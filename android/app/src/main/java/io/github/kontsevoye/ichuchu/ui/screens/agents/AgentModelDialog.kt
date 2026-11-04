package io.github.kontsevoye.ichuchu.ui.screens.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.kontsevoye.ichuchu.ui.components.IchuDialog
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography

/** Reuses the previous terminal button's slot; no persistent chat/composer row is added. */
@Composable
internal fun AgentSessionMenu(state: AgentUiState, onTerminal: () -> Unit, onModel: () -> Unit) {
    var expanded by remember(state.selectedId) { mutableStateOf(false) }
    Box {
        AgentButton("⋯", { expanded = true }, description = "Session options")
        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(LocalDensity.current) { 48.dp.roundToPx() }),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.widthIn(max = 280.dp)
                        .heightIn(max = 350.dp)
                        .background(IchuColors.current.surfaceVariant)
                        .border(1.dp, IchuColors.current.border)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    AgentButton(
                        "Model & reasoning",
                        {
                            expanded = false
                            onModel()
                        },
                        enabled =
                            state.online &&
                                state.selected?.local == true &&
                                state.selected?.supported == true &&
                                !state.changingModel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AgentButton(
                        "Open terminal",
                        {
                            expanded = false
                            onTerminal()
                        },
                        enabled = state.online && state.selected?.local == true,
                        description = "Open this session in terminal",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AgentModelDialog(
    state: AgentUiState,
    form: AgentModelSettings,
    onChoice: (String, String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onTerminal: () -> Unit,
) {
    val tool = state.capabilities?.tools?.firstOrNull { it.name == state.selected?.tool }
    val models = if (tool?.name == "codex") form.models.keys.toList() else tool?.models.orEmpty()
    val efforts =
        if (tool?.name == "codex") form.models[form.choice.model].orEmpty() else emptyList()
    val valid =
        form.choice.model in models &&
            (form.choice.effort.isBlank() || form.choice.effort in efforts)
    IchuDialog(
        title = "Model & reasoning",
        confirmLabel = if (state.changingModel) "Wait…" else "Apply",
        confirmEnabled =
            state.online &&
                !state.sending &&
                !state.changingModel &&
                !form.loading &&
                form.error == null &&
                valid &&
                form.choice != form.current,
        onConfirm = onApply,
        onDismiss = { if (!state.changingModel) onDismiss() },
        dismissLabel = "Close",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IchuText(
                "Applies to the next message in this conversation.",
                style = IchuTypography.current.bodySmall,
            )
            when {
                form.loading -> IchuText("Reading current model…")
                form.error != null -> {
                    IchuText(form.error, color = IchuColors.current.warning)
                    if (form.uncertain) IchuText("Check the terminal before applying again.")
                    AgentButton(
                        "Read current settings",
                        onReload,
                        enabled = state.online && !state.changingModel,
                    )
                    AgentButton(
                        "Open terminal",
                        {
                            onDismiss()
                            onTerminal()
                        },
                        enabled = state.online && !state.changingModel,
                    )
                }
                else -> {
                    AgentPicker(
                        "Model",
                        form.choice.model,
                        if (state.changingModel) emptyList() else models,
                        empty =
                            if (state.changingModel) {
                                form.choice.model
                            } else {
                                "No models advertised by this server"
                            },
                    ) { model ->
                        val supported = form.models[model].orEmpty()
                        onChoice(model, form.choice.effort.takeIf { it in supported }.orEmpty())
                    }
                    if (tool?.name == "codex") {
                        AgentPicker(
                            "Reasoning",
                            form.choice.effort,
                            if (state.changingModel) emptyList() else listOf("") + efforts,
                            empty = form.choice.effort.ifBlank { "default" },
                        ) {
                            onChoice(form.choice.model, it)
                        }
                        IchuText(
                            "For Ultra, use the terminal.",
                            style = IchuTypography.current.bodySmall,
                        )
                    } else {
                        IchuText(
                            "Reasoning settings are available in the Claude terminal.",
                            style = IchuTypography.current.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
