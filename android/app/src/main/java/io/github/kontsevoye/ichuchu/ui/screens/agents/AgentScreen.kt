package io.github.kontsevoye.ichuchu.ui.screens.agents

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kontsevoye.ichuchu.service.agents.AgentNotifications
import io.github.kontsevoye.ichuchu.service.agents.AgentStatus
import io.github.kontsevoye.ichuchu.service.agents.DeckModelSettings
import io.github.kontsevoye.ichuchu.service.agents.DeckNativeHistory
import io.github.kontsevoye.ichuchu.service.agents.DeckRow
import io.github.kontsevoye.ichuchu.service.agents.DeckSend
import io.github.kontsevoye.ichuchu.service.agents.DeckSessionAction
import io.github.kontsevoye.ichuchu.service.agents.flag
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
    var notificationsEnabled by remember { mutableStateOf(AgentNotifications.enabled(context)) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsEnabled = AgentNotifications.enabled(context)
        }
    val notificationPrefs = remember { context.getSharedPreferences("agent_notifications", 0) }
    LaunchedEffect(state.online) {
        if (
            state.online &&
                Build.VERSION.SDK_INT >= 33 &&
                !notificationsEnabled &&
                !notificationPrefs.getBoolean("asked", false)
        ) {
            notificationPrefs.edit().putBoolean("asked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                notificationsEnabled = AgentNotifications.enabled(context)
                vm.setForeground(true)
            }
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
        notificationsEnabled,
        {
            if (
                Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    !notificationPrefs.getBoolean("asked", false)
            ) {
                notificationPrefs.edit().putBoolean("asked", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(
                    Intent(
                            if (Build.VERSION.SDK_INT >= 26) {
                                Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            } else {
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            }
                        )
                        .apply {
                            if (Build.VERSION.SDK_INT >= 26) {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            } else {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                        }
                )
            }
        },
    )
}

@Composable
private fun AgentContent(
    state: AgentUiState,
    vm: AgentViewModel,
    onBack: () -> Unit,
    onEditHost: () -> Unit,
    onTerminal: () -> Unit,
    notificationsEnabled: Boolean,
    onNotifications: () -> Unit,
) {
    val colors = IchuColors.current
    val type = IchuTypography.current
    val compactActions = LocalDensity.current.fontScale >= 1.5f
    var recallDialog by remember { mutableStateOf(false) }
    var retryDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var attachmentTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val attachmentPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            attachmentTarget?.let { vm.attachFiles(it, uris) }
            attachmentTarget = null
        }
    state.modelSettings?.let { form ->
        AgentModelDialog(
            state,
            form,
            vm::updateModelSettings,
            vm::applyModelSettings,
            vm::dismissModelSettings,
            vm::showModelSettings,
            onTerminal,
        )
    }
    state.historyChoices?.let { choices ->
        AgentHistoryPicker(
            choices,
            state.historyChoicesLoading,
            state.linkingHistory,
            vm::linkHistory,
            vm::dismissHistoryChoices,
        )
    }
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
                AgentSessionMenu(state, onTerminal, vm::showModelSettings)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!state.online && !state.connecting) {
                AgentButton("reconnect", vm::reconnect)
            } else if (state.selectedId == null && !state.showCreate) {
                AgentButton(
                    if (compactActions) "↻" else "refresh",
                    vm::refresh,
                    state.online,
                    "Refresh sessions",
                )
                AgentButton(
                    if (compactActions) "×" else "disconnect",
                    vm::disconnect,
                    state.online,
                    "Disconnect from this Mac",
                )
            }
        }
        if (
            state.online && !notificationsEnabled && state.selectedId == null && !state.showCreate
        ) {
            AgentButton(
                "enable status notifications",
                onNotifications,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
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
                AgentCreate(
                    state,
                    vm::updateCreate,
                    vm::create,
                    vm::acknowledgeCreation,
                    vm::refreshProjects,
                )
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
                    vm::chooseHistory,
                    vm::retryHistory,
                    {
                        attachmentTarget = state.selectedId
                        attachmentPicker.launch(arrayOf("*/*"))
                    },
                    vm::removeAttachment,
                    vm::cancelUpload,
                )
            else ->
                AgentSessions(
                    state,
                    vm::openSession,
                    vm::showCreate,
                    { clearDialog = true },
                    { recallDialog = true },
                    vm::sessionAction,
                )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.AgentSessions(
    state: AgentUiState,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onClear: () -> Unit,
    onRecall: () -> Unit,
    onAction: (String, DeckSessionAction) -> Unit,
) {
    val colors = IchuColors.current
    val type = IchuTypography.current
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var actionSession by rememberSaveable { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    BackHandler(actionSession != null) { actionSession = null }
    LaunchedEffect(state.sessions) {
        if (state.sessions.none { it.id == actionSession }) actionSession = null
    }
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
        if (LocalDensity.current.fontScale >= 1.5f) {
            AgentPicker("Status", filter, listOf("all", "working", "attention")) { filter = it }
        } else {
            IchuSegmentedControl(
                listOf("all", "working", "attention"),
                labels = emptyMap(),
                selected = filter,
                onSelect = { filter = it },
            )
        }
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
                        "Sessions from this Deck profile will appear here. Choose a Deck project to create your first session."
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
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (actionSession == session.id) {
                            AgentSessionActions(
                                session,
                                state.online &&
                                    state.sessionMutationId == null &&
                                    !state.sending &&
                                    !state.changingModel &&
                                    state.upload?.sessionId != session.id,
                                onAction,
                                { actionSession = null },
                            )
                        }
                        IchuCard(
                            Modifier.fillMaxWidth()
                                .testTag("agent_session_${session.id}")
                                .combinedClickable(
                                    onClick = {
                                        actionSession = null
                                        onOpen(session.id)
                                    },
                                    onLongClickLabel = "Session actions",
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        actionSession = session.id
                                    },
                                )
                        ) {
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
    onRefreshProjects: () -> Unit,
) {
    val form = state.create
    val capabilities = state.capabilities
    val projects = state.projects
    val selectedProject =
        projects.firstOrNull {
            it.group == form.group && (it.path == form.path || it.path.isBlank())
        }
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
            selectedProject?.label.orEmpty(),
            projects.map { it.label },
            "No known projects in this profile",
        ) {
            projects
                .firstOrNull { project -> project.label == it }
                ?.let { project -> onChange(form.copy(path = project.path, group = project.group)) }
        }
        if (selectedProject?.path?.isBlank() == true) {
            IchuTextField(
                form.path,
                { onChange(form.copy(path = it)) },
                label = "Project directory on Mac",
                placeholder = "/absolute/path/to/project",
                singleLine = true,
                autoFocus = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IchuText(
            state.projectsError
                ?: if (state.projectsLoading) {
                    "Loading Deck projects…"
                } else {
                    "All Deck groups, including projects without sessions."
                },
            style = type.bodySmall,
            color = if (state.projectsError != null) colors.warning else colors.textMuted,
        )
        AgentButton("refresh projects", onRefreshProjects, state.online && !state.projectsLoading)
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
                selectedProject != null &&
                form.path.startsWith('/') &&
                tool != null,
            filled = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AgentHistoryPicker(
    choices: List<DeckNativeHistory>,
    loading: Boolean,
    linking: Boolean,
    onLink: (DeckNativeHistory) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(choices) { mutableStateOf<DeckNativeHistory?>(null) }
    IchuDialog(
        title = "Choose this session's history",
        confirmLabel =
            if (selected == null) {
                "Close"
            } else if (linking) {
                "Linking…"
            } else {
                "Link selected history"
            },
        dismissLabel = "Cancel",
        onConfirm = { if (!linking) selected?.let(onLink) ?: onDismiss() },
        onDismiss = { if (!linking) onDismiss() },
    ) {
        Column(
            Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IchuText(
                "Choose the exact conversation running in this session. The link is saved in Agent Deck.",
                style = IchuTypography.current.bodySmall,
            )
            if (loading) {
                IchuText("Loading conversations…")
            } else if (choices.isEmpty()) {
                IchuText("No indexed conversations found for this project yet.")
            }
            choices.forEach { history ->
                val date =
                    java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.SHORT,
                            java.text.DateFormat.SHORT,
                        )
                        .format(java.util.Date(history.startedAt * 1000))
                IchuCard(
                    Modifier.fillMaxWidth().clickable(enabled = !linking) { selected = history }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        IchuText((if (selected == history) "● " else "○ ") + history.title)
                        IchuText(
                            "$date\n${history.id}",
                            style = IchuTypography.current.bodySmall,
                            color = IchuColors.current.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentPicker(
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

@Composable
internal fun ColumnScope.AgentChat(
    state: AgentUiState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onOlder: () -> Unit,
    onCancel: (DeckSend) -> Unit,
    onRecover: (DeckSend) -> Unit,
    onTerminal: () -> Unit,
    onRecall: () -> Unit,
    onChooseHistory: () -> Unit,
    onRetryHistory: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onCancelUpload: () -> Unit,
    list: LazyListState = rememberLazyListState(),
) {
    val colors = IchuColors.current
    val type = IchuTypography.current
    val session = state.selected
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val largeFont = density.fontScale >= 1.5f
    val blocks = remember(state.chat.timeline.rows, state.chat.sends) { chatBlocks(state.chat) }
    val scroll =
        rememberAgentChatScroll(list, state.selectedId, !state.chatLoading && blocks.isNotEmpty())
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().nestedScroll(scroll).testTag("agent_chat_history"),
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
        if (session?.status == AgentStatus.Queued) {
            item {
                AgentNotice(
                    "Waiting for a session slot",
                    "Group ${session.group.ifBlank { "on this host" }} is at its concurrent session limit. " +
                        "This session has not started yet. Stop an unused session in Agent Deck to free a slot. " +
                        "You can write a draft here while waiting; choosing a model does not change this limit.",
                )
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
            item {
                IchuText(it, style = type.bodySmall, color = colors.warning)
                AgentButton("retry history", onRetryHistory, state.online)
            }
        }
        if (state.historyMissing && session?.tool == "codex") {
            item {
                AgentNotice(
                    "History is not linked yet",
                    "New conversations appear after the first message. If this session already has a conversation, choose its history.",
                ) {
                    AgentButton(
                        "choose existing history",
                        onChooseHistory,
                        state.online && !state.historyChoicesLoading,
                    )
                }
            }
        }
        if (blocks.isEmpty() && !state.chatLoading) {
            item {
                IchuText(
                    if (state.historyMissing && session?.tool == "claude") {
                        "No messages yet. Send your first request when the agent is ready."
                    } else {
                        "Messages and agent actions will appear here."
                    },
                    style = type.body,
                    color = colors.textMuted,
                )
            }
        }
        items(blocks, key = { it.key }) { block ->
            val send = block.send
            if (send != null) {
                IchuCard(
                    Modifier.fillMaxWidth(),
                    border =
                        if (send.uncertain || send.state == "failed") {
                            colors.warning
                        } else {
                            colors.border
                        },
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IchuText(
                            "you · ${send.label}",
                            style = type.bodySmall,
                            color = colors.textMuted,
                        )
                        SelectionContainer { IchuText(send.text) }
                        send.attachments.forEach { file ->
                            IchuText(
                                "+ ${file.name}",
                                style = type.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
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
            } else if (block.rows.size == 1 && block.rows.first().isMessage) {
                AgentMessage(block.rows.first(), session?.tool.orEmpty())
            } else {
                AgentActions(block.rows)
            }
        }
        state.chat.timeline.status
            ?.text("verb")
            ?.takeIf { it.isNotBlank() }
            ?.let { verb -> item { IchuText(verb, style = type.bodySmall, color = colors.accent) } }
    }
    if (!scroll.following && !atBottom && !keyboardVisible) {
        AgentButton(
            "↓ latest",
            { scope.launch { scroll.jumpToLatest() } },
            modifier = Modifier.align(Alignment.End).padding(end = 16.dp),
        )
    }
    AgentRule()
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.chat.attachments.isNotEmpty()) {
            AgentAttachments(
                state.chat.attachments,
                state.sending,
                compact = keyboardVisible && largeFont,
                onRemove = onRemoveAttachment,
            )
        }
        state.upload
            ?.takeIf { it.sessionId == state.selectedId }
            ?.let { upload ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val progress =
                        if (upload.total > 0) " ${upload.bytes * 100 / upload.total}%" else ""
                    IchuText(
                        "${upload.name} · uploading$progress",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = type.bodySmall,
                    )
                    AgentButton("×", onCancelUpload, description = "Cancel file upload")
                }
            }
        if (
            (!keyboardVisible && !largeFont) ||
                state.currentModel != null ||
                state.contextRemaining != null
        ) {
            AgentComposerStatus(state.currentModel, state.contextRemaining)
        }
        IchuTextField(
            state.chat.draft,
            onDraft,
            label = "Message",
            placeholder = "Message agent…",
            showLabel = false,
            minLines = if (keyboardVisible || largeFont) 1 else 2,
            maxLines = if (largeFont) 2 else 4,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AgentButton(
                "+ file",
                onAttach,
                state.canSend && state.chat.attachments.size < 4 && state.upload == null,
                description = "Attach photos or files",
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IchuText(
                    when {
                        !state.online -> "Draft saved"
                        keyboardVisible || largeFont -> ""
                        else -> "Enter adds a new line"
                    },
                    style = type.bodySmall,
                    color = colors.textMuted,
                )
            }
            AgentButton(
                if (state.sending) "sending…" else "send ↑",
                onSend,
                state.canSend &&
                    (state.chat.draft.isNotBlank() || state.chat.attachments.isNotEmpty()),
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
private fun AgentComposerStatus(model: DeckModelSettings?, percent: Int?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement =
                Arrangement.spacedBy(6.dp, if (model == null) Alignment.Start else Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IchuText(
                model?.model ?: "> Message",
                style = IchuTypography.current.labelSmall,
                color = IchuColors.current.textMuted,
                modifier =
                    Modifier.weight(1f, fill = false)
                        .then(
                            if (model != null) {
                                Modifier.testTag("agent_current_model").semantics {
                                    contentDescription =
                                        "Model: ${model.model}, reasoning: ${model.effort.ifBlank { "unknown" }}"
                                }
                            } else {
                                Modifier
                            }
                        ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            model
                ?.effort
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    IchuText(
                        it,
                        style = IchuTypography.current.labelSmall,
                        color = IchuColors.current.textMuted,
                        maxLines = 1,
                    )
                }
        }
        percent?.let { AgentContextIndicator(it) }
    }
}

@Composable
private fun AgentContextIndicator(percent: Int) {
    IchuText(
        "$percent%",
        style = IchuTypography.current.labelSmall,
        color = if (percent <= 20) IchuColors.current.warning else IchuColors.current.textMuted,
        modifier =
            Modifier.testTag("agent_context_remaining").semantics {
                contentDescription = "Context remaining: $percent percent"
            },
        maxLines = 1,
    )
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
        AgentMarkdown(row.body.ifBlank { row.title }, memoryCitations = row.kind == "assistant")
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
            if (row.body.isNotBlank()) AgentMarkdown(row.body, memoryCitations = false)
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
