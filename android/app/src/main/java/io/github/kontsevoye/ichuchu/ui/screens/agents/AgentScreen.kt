package io.github.kontsevoye.ichuchu.ui.screens.agents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kontsevoye.ichuchu.service.agents.AgentStatus
import io.github.kontsevoye.ichuchu.service.agents.DeckRow
import io.github.kontsevoye.ichuchu.service.agents.DeckSend
import io.github.kontsevoye.ichuchu.service.agents.flag
import io.github.kontsevoye.ichuchu.service.agents.knownDeckProjects
import io.github.kontsevoye.ichuchu.service.agents.text
import io.github.kontsevoye.ichuchu.ui.components.IchuButton
import io.github.kontsevoye.ichuchu.ui.components.IchuButtonVariant
import io.github.kontsevoye.ichuchu.ui.components.IchuCard
import io.github.kontsevoye.ichuchu.ui.components.IchuDialog
import io.github.kontsevoye.ichuchu.ui.components.IchuSegmentedControl
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.components.IchuTextField
import io.github.kontsevoye.ichuchu.ui.security.VerificationResult
import io.github.kontsevoye.ichuchu.ui.security.requireUserVerification
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography
import kotlinx.coroutines.launch

@Composable
fun AgentScreen(
    vm: AgentViewModel,
    onBack: () -> Unit,
    onEditHost: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) vm.setForeground(false)
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.setForeground(true)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            vm.setForeground(false)
        }
    }
    LaunchedEffect(state.authRequest) {
        val request = state.authRequest
        if (request != 0L) {
            requireUserVerification(
                context,
                "Connect to ${state.hostName}",
                "Authenticate to use this SSH route",
            ) {
                vm.authenticate(request, it == VerificationResult.Success)
            }
        }
    }
    BackHandler { if (!vm.back()) onBack() }
    prompt?.let {
        IchuDialog(
            title =
                if (it.previousFingerprint == null) {
                    "Verify host key"
                } else {
                    "Replace changed host key?"
                },
            confirmLabel = if (it.previousFingerprint == null) "Accept" else "Replace trusted key",
            dismissLabel = "Reject",
            onConfirm = { vm.respondToHostKey(true) },
            onDismiss = { vm.respondToHostKey(false) },
        ) {
            IchuText(
                "${it.routeLabel}\n${it.host}:${it.port}\n${it.algorithm}\n" +
                    (it.previousFingerprint?.let { old -> "WARNING: key changed!\nOld: $old\n" }
                        ?: "") +
                    "New: ${it.fingerprint}"
            )
        }
    }
    AgentContent(
        state,
        vm,
        { if (!vm.back()) onBack() },
        onEditHost,
        { vm.openTerminal(onOpenTerminal) },
    )
}

@Composable
private fun AgentContent(
    state: AgentUiState,
    vm: AgentViewModel,
    onBack: () -> Unit,
    onEditHost: () -> Unit,
    onTerminal: () -> Unit,
) {
    val colors = IchuColors.current
    val type = IchuTypography.current
    var recallDialog by remember { mutableStateOf(false) }
    var retryDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    if (recallDialog) {
        IchuDialog(
            title = "Enable chat history on this Mac?",
            confirmLabel = "Enable Recall",
            dismissLabel = "Cancel",
            onConfirm = {
                recallDialog = false
                vm.enableRecall()
            },
            onDismiss = { recallDialog = false },
        ) {
            IchuText(
                "Agent Deck Recall is a global setting for all Deck profiles on this Mac. Enabling it may index existing conversations. Your agents' permissions and hooks stay under your existing settings."
            )
        }
    }
    if (retryDialog) {
        IchuDialog(
            title = "A previous delivery is unconfirmed",
            confirmLabel = "Send anyway",
            dismissLabel = "Review chat",
            onConfirm = {
                retryDialog = false
                vm.send()
            },
            onDismiss = { retryDialog = false },
        ) {
            IchuText(
                "The agent may already have received your earlier message. Sending again can create a duplicate request. Check the history and queue first."
            )
        }
    }
    if (clearDialog) {
        IchuDialog(
            title = "Clear cached history on this phone?",
            confirmLabel = "Clear history",
            dismissLabel = "Cancel",
            onConfirm = {
                clearDialog = false
                vm.clearCachedHistory()
            },
            onDismiss = { clearDialog = false },
        ) {
            IchuText("Remote sessions are kept. Local drafts and delivery receipts are kept too.")
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentButton("‹", onBack, description = "Back")
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                IchuText(
                    if (state.showCreate) "$ new session" else state.selected?.title ?: "$ agents",
                    style = type.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IchuText(
                    "${state.hostName} · ${state.profile}",
                    style = type.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.selectedId != null) {
                AgentButton(
                    ">_",
                    onTerminal,
                    state.online && state.selected?.local == true,
                    "Open this session in terminal",
                )
            } else {
                AgentButton("⋯", onEditHost, description = "Connection settings")
            }
        }
        AgentRule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IchuText(
                when {
                    state.connecting -> "○ connecting"
                    !state.online -> "○ offline · cached"
                    state.selectedId != null && state.live -> "● live"
                    else -> "● connected"
                },
                style = type.bodySmall,
                color = if (state.online) colors.success else colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (!state.online && !state.connecting) {
                AgentButton("reconnect", vm::reconnect)
            } else if (state.selectedId == null && !state.showCreate) {
                AgentButton("refresh", vm::refresh, state.online)
            }
        }
        (state.error ?: state.notice)?.let { message ->
            Row(
                Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IchuText(
                    message,
                    style = type.bodySmall,
                    color = if (state.error != null) colors.warning else colors.textSecondary,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                )
                AgentButton("×", vm::clearMessage, description = "Dismiss message")
            }
        }
        when {
            state.showCreate ->
                AgentCreate(state, vm::updateCreate, vm::create, vm::acknowledgeCreation)
            state.selectedId != null ->
                AgentChat(
                    state,
                    vm::updateDraft,
                    {
                        if (state.chat.sends.any { it.uncertain }) retryDialog = true else vm.send()
                    },
                    vm::loadOlder,
                    vm::cancel,
                    vm::recoverDraft,
                    onTerminal,
                    { recallDialog = true },
                )
            else ->
                AgentSessions(
                    state,
                    vm::openSession,
                    vm::showCreate,
                    { clearDialog = true },
                    { recallDialog = true },
                )
        }
    }
}

@Composable
private fun ColumnScope.AgentSessions(
    state: AgentUiState,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onClear: () -> Unit,
    onRecall: () -> Unit,
) {
    val colors = IchuColors.current
    val type = IchuTypography.current
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    val filtered =
        state.sessions.filter {
            (query.isBlank() ||
                "${it.title} ${it.path} ${it.tool}".contains(query, ignoreCase = true)) &&
                when (filter) {
                    "working" -> it.status == AgentStatus.Running
                    "attention" ->
                        it.status in
                            setOf(AgentStatus.Attention, AgentStatus.Error, AgentStatus.Waiting)
                    else -> true
                }
        }
    Column(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IchuText(
                "SESSIONS · ${state.sessions.size}",
                style = type.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            AgentButton(
                "+ new",
                onCreate,
                state.online && state.capabilities?.launchFields?.contains("no-parent") == true,
                filled = true,
            )
        }
        IchuTextField(
            query,
            { query = it },
            label = "Search",
            placeholder = "Find session or project…",
            singleLine = true,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        IchuSegmentedControl(
            listOf("all", "working", "attention"),
            labels = emptyMap(),
            selected = filter,
            onSelect = { filter = it },
        )
    }
    LazyColumn(
        Modifier.weight(1f),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.capabilities?.recallEnabled == false) {
            item {
                AgentNotice(
                    "Chat history is off",
                    "Enable Agent Deck Recall to read conversations in the app.",
                ) {
                    AgentButton("enable history", onRecall, state.online)
                }
            }
        }
        if (state.creationUnknown) {
            item {
                AgentNotice(
                    "Creation outcome unconfirmed",
                    "Check the list before creating another session. Your first message is saved in New session.",
                )
            }
        }
        if (filtered.isEmpty()) {
            item {
                AgentNotice(
                    if (state.sessions.isEmpty()) "No sessions yet" else "No matching sessions",
                    if (state.sessions.isEmpty()) {
                        "Sessions from this Deck profile will appear here. Create a session on the Mac first to make its project available."
                    } else {
                        "Try another filter or project name."
                    },
                )
            }
        }
        filtered
            .groupBy { it.path }
            .forEach { (path, sessions) ->
                item(key = "project:$path") {
                    IchuText(
                        path.ifBlank { "unknown project" },
                        style = type.bodySmall,
                        color = colors.textMuted,
                    )
                }
                items(sessions, key = { it.id }) { session ->
                    IchuCard(Modifier.fillMaxWidth().clickable { onOpen(session.id) }) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IchuText(
                                    session.title,
                                    style = type.body.copy(fontWeight = FontWeight.Medium),
                                    modifier = Modifier.weight(1f),
                                )
                                IchuText("›", color = colors.textMuted)
                            }
                            IchuText(
                                "${session.tool} · ${if (state.online) session.status.label else "cached · ${session.status.label}"}",
                                style = type.bodySmall,
                                color = statusColor(session.status),
                            )
                            if (session.detail.isNotBlank()) {
                                IchuText(
                                    session.detail,
                                    style = type.bodySmall,
                                    color = colors.textSecondary,
                                )
                            }
                            if (!session.local) {
                                IchuText(
                                    "Remote-owned session · open it on its owning host",
                                    style = type.bodySmall,
                                    color = colors.warning,
                                )
                            } else if (!session.supported) {
                                IchuText(
                                    "Terminal session · native chat unavailable",
                                    style = type.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        item { AgentButton("clear phone history", onClear) }
    }
}

@Composable
private fun ColumnScope.AgentCreate(
    state: AgentUiState,
    onChange: (AgentCreateForm) -> Unit,
    onCreate: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val form = state.create
    val capabilities = state.capabilities
    val projects = knownDeckProjects(state.sessions)
    val tool = capabilities?.tools?.firstOrNull { it.name == form.tool }
    val type = IchuTypography.current
    val colors = IchuColors.current
    var acknowledge by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    if (acknowledge) {
        IchuDialog(
            "Allow another creation?",
            "I checked the list",
            dismissLabel = "Cancel",
            onConfirm = {
                acknowledge = false
                onAcknowledge()
            },
            onDismiss = { acknowledge = false },
        ) {
            IchuText(
                "The previous session may already exist. Creating another can start a second agent. Your saved first message will remain a draft until you send it."
            )
        }
    }
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.creationUnknown) {
            AgentNotice("Previous creation unconfirmed", "Review sessions before trying again.") {
                AgentButton("I reviewed the sessions", { acknowledge = true })
            }
        }
        AgentPicker(
            "Project",
            form.path,
            projects.map { it.path },
            "No known projects in this profile",
        ) {
            onChange(form.copy(path = it))
        }
        IchuText(
            "Projects come from existing Deck sessions on this Mac.",
            style = type.bodySmall,
            color = colors.textMuted,
        )
        AgentPicker("Agent", form.tool, capabilities?.tools?.map { it.name }.orEmpty()) {
            onChange(form.copy(tool = it, model = "", effort = "", account = ""))
        }
        IchuTextField(
            form.title,
            { onChange(form.copy(title = it)) },
            label = "Session name",
            placeholder = "Optional",
            singleLine = true,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        AgentButton(
            if (options) {
                "▾ model & options"
            } else {
                "▸ model & options · ${form.model.ifBlank { "default" }}"
            },
            { options = !options },
        )
        if (options) {
            if (capabilities?.launchFields?.contains("model") == true) {
                AgentPicker("Model", form.model, listOf("") + tool?.models.orEmpty()) {
                    onChange(form.copy(model = it, effort = ""))
                }
            }
            if (capabilities?.launchFields?.contains("effort") == true) {
                AgentPicker(
                    "Reasoning effort",
                    form.effort,
                    listOf("") + (tool?.modelEfforts?.get(form.model) ?: tool?.efforts.orEmpty()),
                ) {
                    onChange(form.copy(effort = it))
                }
            }
            if (
                capabilities?.accounts?.isNotEmpty() == true &&
                    "account" in capabilities.launchFields
            ) {
                AgentPicker("Account", form.account, listOf("") + capabilities.accounts) {
                    onChange(form.copy(account = it))
                }
            }
        }
        IchuTextField(
            form.message,
            { onChange(form.copy(message = it)) },
            label = "First message",
            placeholder = "What should the agent work on?",
            minLines = 4,
            maxLines = 8,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        IchuText(
            "Saved as a draft in the new chat. Send it when the agent is ready; any first-run setup opens in the same session's terminal.",
            style = type.bodySmall,
            color = colors.textMuted,
        )
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        AgentButton(
            if (state.creating) "creating…" else "create session",
            onCreate,
            state.online &&
                !state.creating &&
                !state.creationUnknown &&
                projects.any { it.path == form.path } &&
                tool != null,
            filled = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AgentPicker(
    label: String,
    value: String,
    choices: List<String>,
    empty: String = "No options available",
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val type = IchuTypography.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IchuText("> $label", style = type.bodySmall, color = IchuColors.current.textMuted)
        IchuCard(
            Modifier.fillMaxWidth().clickable(enabled = choices.isNotEmpty()) { expanded = true }
        ) {
            IchuText(
                if (choices.isEmpty()) empty else "${value.ifBlank { "default" }}  ▾",
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    if (expanded) {
        IchuDialog(
            title = label,
            confirmLabel = "Close",
            onConfirm = { expanded = false },
            onDismiss = { expanded = false },
        ) {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                choices.distinct().forEach { option ->
                    AgentButton(
                        (if (option == value) "● " else "○ ") + option.ifBlank { "default" },
                        {
                            onSelect(option)
                            expanded = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private data class ChatBlock(val rows: List<DeckRow>) {
    val key: String
        get() = rows.first().id
}

private fun chatBlocks(rows: List<DeckRow>): List<ChatBlock> {
    val blocks = mutableListOf<ChatBlock>()
    val actions = mutableListOf<DeckRow>()
    fun flush() {
        if (actions.isNotEmpty()) {
            blocks += ChatBlock(actions.toList())
            actions.clear()
        }
    }
    rows.forEach {
        if (it.isMessage) {
            flush()
            blocks += ChatBlock(listOf(it))
        } else {
            actions += it
            if (actions.size == 20) flush()
        }
    }
    flush()
    return blocks
}

@Composable
private fun ColumnScope.AgentChat(
    state: AgentUiState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onOlder: () -> Unit,
    onCancel: (DeckSend) -> Unit,
    onRecover: (DeckSend) -> Unit,
    onTerminal: () -> Unit,
    onRecall: () -> Unit,
) {
    val colors = IchuColors.current
    val type = IchuTypography.current
    val session = state.selected
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val largeFont = density.fontScale >= 1.5f
    var initialScrollDone by remember(state.selectedId) { mutableStateOf(false) }
    val blocks = remember(state.chat.timeline.rows) { chatBlocks(state.chat.timeline.rows) }
    val rowIds =
        remember(state.chat.timeline.rows) { state.chat.timeline.rows.map { it.id }.toSet() }
    val sends =
        state.chat.sends.filterNot { it.landedRowId.isNotBlank() && it.landedRowId in rowIds }
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(state.selectedId, blocks.isNotEmpty(), state.chatLoading) {
        if (!initialScrollDone && blocks.isNotEmpty() && !state.chatLoading) {
            list.scrollToItem(Int.MAX_VALUE)
            initialScrollDone = true
        }
    }
    LaunchedEffect(blocks.lastOrNull(), sends.lastOrNull()) {
        if (atBottom && list.layoutInfo.totalItemsCount > 0) {
            list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            IchuText(
                "${session?.tool.orEmpty()} · ${session?.project.orEmpty()} · ${session?.status?.label ?: "session unavailable"}",
                style = type.bodySmall,
                color = session?.let { statusColor(it.status) } ?: colors.textMuted,
            )
        }
        if (session?.local == false) {
            item {
                AgentNotice(
                    "Owned by another host",
                    "Connect to the session's owning Mac to use native chat. This connection does not forward chat through Deck SSH proxies.",
                )
            }
        } else if (session?.supported == false) {
            item {
                AgentNotice(
                    "Open in terminal",
                    "Native conversations currently support Claude and Codex.",
                ) {
                    AgentButton("open session terminal", onTerminal, state.online)
                }
            }
        } else if (state.capabilities?.recallEnabled == false) {
            item {
                AgentNotice(
                    "History needs Recall",
                    "Enable history on the Mac to read this conversation.",
                ) {
                    AgentButton("enable history", onRecall, state.online)
                }
            }
        }
        if (
            session?.needsTerminal == true ||
                !state.chat.timeline.status?.text("permission").isNullOrBlank()
        ) {
            item {
                AgentNotice(
                    "Action needed in terminal",
                    session?.detail?.ifBlank { "The agent is showing an interactive prompt." }
                        ?: "The agent is showing an interactive prompt.",
                ) {
                    AgentButton("open same session", onTerminal, state.online)
                }
            }
        }
        if (state.chat.timeline.before != null) {
            item {
                AgentButton(
                    if (state.loadingOlder) "loading…" else "load earlier messages",
                    onOlder,
                    state.online && !state.loadingOlder,
                )
            }
        }
        if (state.chatLoading) {
            item { IchuText("Loading history…", style = type.bodySmall, color = colors.textMuted) }
        }
        state.chatError?.let {
            item { IchuText(it, style = type.bodySmall, color = colors.warning) }
        }
        if (blocks.isEmpty() && !state.chatLoading) {
            item {
                IchuText(
                    "Messages and agent actions will appear here.",
                    style = type.body,
                    color = colors.textMuted,
                )
            }
        }
        items(blocks, key = { "row:${it.key}" }) { block ->
            if (block.rows.size == 1 && block.rows.first().isMessage) {
                AgentMessage(block.rows.first(), session?.tool.orEmpty())
            } else {
                AgentActions(block.rows)
            }
        }
        items(sends, key = { "send:${it.localId}" }) { send ->
            IchuCard(
                Modifier.fillMaxWidth(),
                border =
                    if (send.uncertain || send.state == "failed") colors.warning else colors.border,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IchuText(
                        "you · ${send.label}",
                        style = type.bodySmall,
                        color = colors.textMuted,
                    )
                    SelectionContainer { IchuText(send.text) }
                    send.receipt
                        .text("reason")
                        .takeIf { it.isNotBlank() }
                        ?.let { IchuText(it, style = type.bodySmall, color = colors.warning) }
                    if (send.state == "queued") {
                        AgentButton("cancel queued message", { onCancel(send) }, state.online)
                    }
                    if (send.state in setOf("failed", "cancelled") || send.uncertain) {
                        AgentButton("copy to draft", { onRecover(send) })
                    }
                }
            }
        }
        state.chat.timeline.status
            ?.text("verb")
            ?.takeIf { it.isNotBlank() }
            ?.let { verb -> item { IchuText(verb, style = type.bodySmall, color = colors.accent) } }
    }
    if (!atBottom) {
        AgentButton(
            "↓ latest",
            {
                scope.launch {
                    if (list.layoutInfo.totalItemsCount > 0) {
                        list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
                    }
                }
            },
            modifier = Modifier.align(Alignment.End).padding(end = 16.dp),
        )
    }
    AgentRule()
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IchuTextField(
            state.chat.draft,
            onDraft,
            label = "Message",
            placeholder = "Message agent…",
            showLabel = !keyboardVisible && !largeFont,
            minLines = if (keyboardVisible || largeFont) 1 else 2,
            maxLines = if (largeFont) 2 else 4,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IchuText(
                when {
                    !state.online -> "Draft saved"
                    keyboardVisible || largeFont -> ""
                    else -> "Enter adds a new line"
                },
                style = type.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            AgentButton(
                if (state.sending) "sending…" else "send ↑",
                onSend,
                state.canSend && state.chat.draft.isNotBlank(),
                filled = true,
            )
        }
        if (state.capabilities?.guardedSend == false) {
            IchuText(
                "This Deck version needs an update for guarded chat input.",
                style = type.bodySmall,
                color = colors.warning,
            )
        }
    }
}

@Composable
private fun AgentMessage(row: DeckRow, tool: String) {
    val colors = IchuColors.current
    val type = IchuTypography.current
    Column(
        Modifier.fillMaxWidth()
            .then(
                if (row.kind == "user") {
                    Modifier.background(colors.surface).padding(12.dp)
                } else {
                    Modifier
                }
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IchuText(
            if (row.kind == "user") "you" else tool.ifBlank { "agent" },
            style = type.bodySmall,
            color = if (row.kind == "user") colors.accent else colors.textMuted,
        )
        AgentMarkdown(row.body.ifBlank { row.title })
    }
}

@Composable
private fun AgentActions(rows: List<DeckRow>) {
    var expanded by remember(rows.first().id) { mutableStateOf(false) }
    IchuCard(Modifier.fillMaxWidth()) {
        AgentButton(
            "${if (expanded) "▾" else "▸"} ${rows.size} actions · ${rows.last().title.ifBlank { rows.last().kind }}",
            { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded) rows.forEach { AgentAction(it) }
    }
}

@Composable
private fun AgentAction(row: DeckRow, depth: Int = 0) {
    var expanded by remember(row.id) { mutableStateOf(false) }
    val colors = IchuColors.current
    val type = IchuTypography.current
    AgentRule()
    Column(
        Modifier.fillMaxWidth().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IchuText(
            "${if (expanded) "▾" else "▸"} ${row.title.ifBlank { row.kind }}${if (row.error) " · failed" else ""}",
            style = type.bodySmall,
            color = if (row.error) colors.error else colors.textSecondary,
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded }
                    .padding(vertical = 10.dp),
        )
        if (row.summary.isNotBlank()) {
            IchuText(row.summary, style = type.bodySmall, color = colors.textMuted)
        }
        if (expanded) {
            if (row.detail.isNotBlank()) {
                SelectionContainer { IchuText(row.detail, style = type.bodySmall) }
            }
            if (row.body.isNotBlank()) AgentMarkdown(row.body)
            if (row.meta.flag("truncated")) {
                IchuText(
                    "Output truncated by Agent Deck",
                    style = type.bodySmall,
                    color = colors.warning,
                )
            }
            if (depth < 4) row.children.take(100).forEach { AgentAction(it, depth + 1) }
            if (row.kind == "question") {
                IchuText(
                    "Answer interactive questions in the session terminal.",
                    style = type.bodySmall,
                    color = colors.warning,
                )
            }
        }
    }
}

@Composable
private fun AgentNotice(
    title: String,
    body: String,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = IchuColors.current
    val type = IchuTypography.current
    IchuCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IchuText(title, style = type.body)
            IchuText(body, style = type.bodySmall, color = colors.textSecondary)
            actions()
        }
    }
}

@Composable
internal fun AgentButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String? = null,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    IchuButton(
        onClick,
        modifier.heightIn(min = 48.dp),
        enabled,
        variant = if (filled) IchuButtonVariant.Filled else IchuButtonVariant.Ghost,
        contentDescription = description,
    ) {
        IchuText(
            label,
            style = IchuTypography.current.label,
            color =
                if (!enabled) {
                    IchuColors.current.disabledText
                } else if (filled) {
                    IchuColors.current.onAccent
                } else {
                    IchuColors.current.accent
                },
        )
    }
}

@Composable
private fun AgentRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(IchuColors.current.border))
}

@Composable
private fun statusColor(status: AgentStatus) =
    when (status) {
        AgentStatus.Running -> IchuColors.current.accent
        AgentStatus.Ready -> IchuColors.current.success
        AgentStatus.Attention,
        AgentStatus.Waiting -> IchuColors.current.warning
        AgentStatus.Error -> IchuColors.current.error
        else -> IchuColors.current.textMuted
    }
