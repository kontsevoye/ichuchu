package io.github.kontsevoye.ichuchu.ui.screens.agents

import android.app.Application
import android.net.Uri
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.service.agents.AGENT_ATTACHMENT_COUNT
import io.github.kontsevoye.ichuchu.service.agents.AgentAttachment
import io.github.kontsevoye.ichuchu.service.agents.AgentChatState
import io.github.kontsevoye.ichuchu.service.agents.AgentConnection
import io.github.kontsevoye.ichuchu.service.agents.AgentConnections
import io.github.kontsevoye.ichuchu.service.agents.AgentNotifications
import io.github.kontsevoye.ichuchu.service.agents.AgentStateStore
import io.github.kontsevoye.ichuchu.service.agents.AgentStatus
import io.github.kontsevoye.ichuchu.service.agents.AgentStatusChanges
import io.github.kontsevoye.ichuchu.service.agents.DeckCapabilities
import io.github.kontsevoye.ichuchu.service.agents.DeckClient
import io.github.kontsevoye.ichuchu.service.agents.DeckCommandException
import io.github.kontsevoye.ichuchu.service.agents.DeckModelSettings
import io.github.kontsevoye.ichuchu.service.agents.DeckNativeHistory
import io.github.kontsevoye.ichuchu.service.agents.DeckProject
import io.github.kontsevoye.ichuchu.service.agents.DeckSend
import io.github.kontsevoye.ichuchu.service.agents.DeckSession
import io.github.kontsevoye.ichuchu.service.agents.DeckSessionAction
import io.github.kontsevoye.ichuchu.service.agents.DeckTimelineReducer
import io.github.kontsevoye.ichuchu.service.agents.DeckTransportException
import io.github.kontsevoye.ichuchu.service.agents.SshDeckTransport
import io.github.kontsevoye.ichuchu.service.agents.agentAttachmentMessage
import io.github.kontsevoye.ichuchu.service.agents.flag
import io.github.kontsevoye.ichuchu.service.agents.mergeDeckProjects
import io.github.kontsevoye.ichuchu.service.agents.objects
import io.github.kontsevoye.ichuchu.service.agents.stageAgentFile
import io.github.kontsevoye.ichuchu.service.agents.text
import io.github.kontsevoye.ichuchu.service.agents.uniqueDeckSends
import io.github.kontsevoye.ichuchu.service.ssh.SshConnectionException
import io.github.kontsevoye.ichuchu.service.terminal.HostKeyPrompt
import io.github.kontsevoye.ichuchu.service.terminal.TerminalSessionRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentCreateForm(
    val path: String = "",
    val tool: String = "claude",
    val title: String = "",
    val model: String = "",
    val effort: String = "",
    val account: String = "",
    val message: String = "",
    val group: String = "",
) {
    fun json(): JsonObject = buildJsonObject {
        put("path", path)
        put("tool", tool)
        put("title", title)
        put("model", model)
        put("effort", effort)
        put("account", account)
        put("message", message)
        put("group", group)
    }

    companion object {
        fun parse(value: JsonObject): AgentCreateForm =
            AgentCreateForm(
                value.text("path"),
                value.text("tool").ifBlank { "claude" },
                value.text("title"),
                value.text("model"),
                value.text("effort"),
                value.text("account"),
                value.text("message"),
                value.text("group"),
            )
    }
}

data class AgentUploadProgress(
    val sessionId: String,
    val batchId: String,
    val name: String = "Preparing file…",
    val bytes: Long = 0,
    val total: Long = 0,
)

data class AgentUiState(
    val hostName: String = "agents",
    val profile: String = "default",
    val online: Boolean = false,
    val connecting: Boolean = false,
    val authRequest: Long = 0,
    val capabilities: DeckCapabilities? = null,
    val sessions: List<DeckSession> = emptyList(),
    val projects: List<DeckProject> = emptyList(),
    val projectsLoading: Boolean = false,
    val projectsError: String? = null,
    val selectedId: String? = null,
    val chat: AgentChatState = AgentChatState(),
    val chatLoading: Boolean = false,
    val chatError: String? = null,
    val historyMissing: Boolean = false,
    val historyChoices: List<DeckNativeHistory>? = null,
    val historyChoicesLoading: Boolean = false,
    val linkingHistory: Boolean = false,
    val live: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val showCreate: Boolean = false,
    val create: AgentCreateForm = AgentCreateForm(),
    val creating: Boolean = false,
    val creationUnknown: Boolean = false,
    val sending: Boolean = false,
    val loadingOlder: Boolean = false,
    val upload: AgentUploadProgress? = null,
    val modelSettings: AgentModelSettings? = null,
    val changingModel: Boolean = false,
    val sessionMutationId: String? = null,
    val contextRemaining: Int? = null,
) {
    val selected: DeckSession?
        get() = sessions.firstOrNull { it.id == selectedId }

    val canSend: Boolean
        get() =
            online &&
                !sending &&
                !changingModel &&
                sessionMutationId != selectedId &&
                upload?.sessionId != selectedId &&
                capabilities?.guardedSend == true &&
                selected?.let {
                    it.local &&
                        it.supported &&
                        !it.needsTerminal &&
                        it.status !in
                            setOf(
                                AgentStatus.Stopped,
                                AgentStatus.Starting,
                                AgentStatus.Queued,
                                AgentStatus.Error,
                            )
                } == true
}

data class AgentModelSettings(
    val sessionId: String,
    val current: DeckModelSettings = DeckModelSettings(),
    val choice: DeckModelSettings = DeckModelSettings(),
    val loading: Boolean = true,
    val error: String? = null,
    val uncertain: Boolean = false,
    val models: Map<String, List<String>> = emptyMap(),
)

/** Connection lifetime is independent of navigation. Reads may retry; mutations never replay. */
class AgentWorkspace(
    private val application: Application,
    val hostId: Long,
    private val connections: AgentConnections,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val db = AppDatabase.getInstance(application)
    private val mutable = MutableStateFlow(AgentUiState())
    val state = mutable.asStateFlow()
    val hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    private var connection: AgentConnection? = null
    private var client: DeckClient? = null
    private var store: AgentStateStore? = null
    private var network: Job? = null
    private var chatJob: Job? = null
    private var eventsJob: Job? = null
    private var decision: CompletableDeferred<Boolean>? = null
    @Volatile private var foreground = false
    private val visibleClients = mutableSetOf<Any>()
    private var generation = 0L
    private var authenticationGeneration = 0L
    private val chatLock = Mutex()
    private var reducer = DeckTimelineReducer()
    @Volatile private var refreshRequested = false
    private val creationLock = Mutex()
    private var authorizedRoute: String? = null
    private var reconnectAttempt = 0
    private val statusChanges = AgentStatusChanges()
    private var groups = emptyList<DeckProject>()
    private var projectsJob: Job? = null
    private var uploadJob: Job? = null
    private var requestedSession: String? = null
    private val pendingDrafts = ConcurrentHashMap<String, String>()

    fun setForeground(owner: Any, visible: Boolean) {
        if (visible) visibleClients.add(owner) else visibleClients.remove(owner)
        val value = visibleClients.isNotEmpty()
        if (foreground == value) return
        foreground = value
        if (value) {
            if (mutable.value.online) {
                startChat()
            } else if (!mutable.value.connecting) {
                reconnect()
            }
        } else {
            chatJob?.cancel()
            mutable.update { it.copy(live = false, chatLoading = false) }
            if (!connections.isActive(hostId)) disconnect()
        }
    }

    fun authenticate(request: Long, accepted: Boolean) {
        if (request == mutable.value.authRequest) decision?.complete(accepted)
    }

    fun respondToHostKey(accepted: Boolean) {
        connection?.respond(accepted)
    }

    fun clearMessage() {
        mutable.update { it.copy(error = null, notice = null) }
    }

    fun reconnect() {
        reconnectAttempt = 0
        beginConnection(automatic = false)
    }

    private fun beginConnection(automatic: Boolean) {
        if (!foreground && !connections.isActive(hostId)) return
        val previous = connection.takeIf { automatic }
        stopNetwork()
        val attempt = generation
        mutable.update { it.copy(connecting = true, error = null) }
        network =
            scope.launch {
                var retry = false
                val route = AgentConnection(application, db, hostId) { foreground }
                connection = route
                val promptJob = launch { route.hostKeyPrompt.collect { hostKeyPrompt.value = it } }
                try {
                    previous?.spec?.routeSnapshot?.requireCurrent()
                    route.prepare()
                    mutable.update {
                        it.copy(hostName = route.host.name, profile = route.host.deckProfile)
                    }
                    val pointer = AgentStateStore(db, hostId, route.baseIdentity)
                    val cachedScope = pointer.get("identity")?.text("scope")
                    if (store == null && !cachedScope.isNullOrBlank()) {
                        store = AgentStateStore(db, hostId, cachedScope)
                        restoreStore()
                    }
                    if (
                        route.requiresVerification &&
                            !(automatic && authorizedRoute == route.baseIdentity)
                    ) {
                        check(foreground) { "Open this connection to authenticate again" }
                        val pending = CompletableDeferred<Boolean>()
                        decision = pending
                        mutable.update { it.copy(authRequest = ++authenticationGeneration) }
                        check(pending.await()) { "Connection authentication cancelled" }
                        mutable.update { it.copy(authRequest = 0) }
                        decision = null
                    }
                    authorizedRoute = route.baseIdentity
                    val deck =
                        DeckClient(
                            SshDeckTransport(route::connect),
                            route.host.deckProfile,
                            route.host.deckExecutable,
                        )
                    client = deck
                    val capabilities = deck.probe()
                    route.sealTrust()
                    if (store?.scope != route.identity) {
                        statusChanges.reset()
                        store = AgentStateStore(db, hostId, route.identity)
                        mutable.update {
                            it.copy(
                                selectedId = null,
                                chat = AgentChatState(),
                                sessions = emptyList(),
                            )
                        }
                        restoreStore()
                    }
                    pointer.put(
                        "identity",
                        buildJsonObject { put("scope", route.identity) },
                        protected = true,
                    )
                    mutable.update { it.copy(capabilities = capabilities, connecting = false) }
                    refreshSessions(deck)
                    connections.activate(hostId, mutable.value.hostName)
                    mutable.update { it.copy(online = true) }
                    reconnectAttempt = 0
                    refreshProjects()
                    openRequestedSession()
                    startChat()
                    if (capabilities.events) startEvents(deck, attempt)
                    var tick = 0
                    while (isActive) {
                        delay(1000)
                        if (++tick >= 5 || refreshRequested) {
                            refreshRequested = false
                            tick = 0
                            refreshSessions(deck)
                            reconcileSends(deck)
                            refreshContext(deck)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retry =
                        e is DeckTransportException || (e is SshConnectionException && e.retryable)
                    if (attempt == generation) {
                        mutable.update { it.copy(error = e.message ?: "Connection failed") }
                    }
                } finally {
                    promptJob.cancel()
                    route.close()
                    if (attempt == generation) {
                        client?.transport?.interrupt()
                        chatJob?.cancel()
                        eventsJob?.cancel()
                        mutable.update {
                            it.copy(
                                online = false,
                                contextRemaining = null,
                                sessionMutationId = null,
                                connecting = false,
                                live = false,
                                authRequest = 0,
                            )
                        }
                        hostKeyPrompt.value = null
                    }
                }
                if (
                    retry && (foreground || connections.isActive(hostId)) && attempt == generation
                ) {
                    mutable.update { it.copy(connecting = true) }
                    delay((1000L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(15_000L))
                    reconnectAttempt++
                    if ((foreground || connections.isActive(hostId)) && attempt == generation) {
                        beginConnection(automatic = true)
                    }
                } else if (attempt == generation) {
                    authorizedRoute = null
                    connections.deactivate(hostId)
                }
            }
    }

    fun disconnect() {
        authorizedRoute = null
        stopNetwork()
        statusChanges.reset()
        connections.deactivate(hostId)
    }

    private fun stopNetwork() {
        uploadJob?.cancel()
        generation++
        decision?.cancel()
        decision = null
        connection?.close()
        client?.transport?.interrupt()
        connection = null
        client = null
        network?.cancel()
        chatJob?.cancel()
        eventsJob?.cancel()
        projectsJob?.cancel()
        network = null
        chatJob = null
        eventsJob = null
        mutable.update {
            it.copy(
                online = false,
                connecting = false,
                live = false,
                authRequest = 0,
                contextRemaining = null,
                sessionMutationId = null,
            )
        }
    }

    private suspend fun restoreStore() {
        val cache = store ?: return
        val sessions = cache.sessions()
        val creation = cache.get("creation")
        groups =
            cache.get("projects")?.objects("projects").orEmpty().map {
                DeckProject(it.text("path"), it.text("group"))
            }
        mutable.update {
            it.copy(
                sessions = sessions,
                projects = mergeDeckProjects(groups, sessions),
                create = creation?.let(AgentCreateForm::parse) ?: it.create,
                creationUnknown = creation?.flag("unknown") == true,
            )
        }
    }

    private suspend fun refreshSessions(deck: DeckClient) {
        val observation = statusChanges.checkpoint()
        val sessions = deck.sessions()
        store?.saveSessions(sessions)
        mutable.update {
            it.copy(sessions = sessions, projects = mergeDeckProjects(groups, sessions))
        }
        statusChanges.accept(sessions, observation).forEach {
            AgentNotifications.show(application, hostId, mutable.value.hostName, it)
        }
    }

    private suspend fun refreshContext(deck: DeckClient) {
        val session = mutable.value.selected
        if (!foreground || session?.local != true || !session.supported) return
        val remaining =
            try {
                deck.contextRemaining(session.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        if (client === deck && mutable.value.selectedId == session.id) {
            mutable.update { it.copy(contextRemaining = remaining) }
        }
    }

    fun sessionAction(id: String, action: DeckSessionAction) {
        val ui = mutable.value
        val deck = client ?: return
        val session = ui.sessions.firstOrNull { it.id == id } ?: return
        if (
            !ui.online ||
                !session.local ||
                ui.sessionMutationId != null ||
                ui.sending ||
                ui.changingModel ||
                ui.upload?.sessionId == id
        ) {
            return
        }
        mutable.update { it.copy(sessionMutationId = id, error = null) }
        scope.launch {
            try {
                deck.sessionAction(session, action)
                if (client !== deck) return@launch
                refreshSessions(deck)
                if (action == DeckSessionAction.Delete) {
                    check(mutable.value.sessions.none { it.id == id }) {
                        "Deletion is not confirmed. Refresh the list before trying again."
                    }
                    if (mutable.value.selectedId == id) back()
                } else if (mutable.value.selectedId == id) {
                    mutable.update { it.copy(contextRemaining = null, modelSettings = null) }
                    startChat()
                }
                mutable.update { it.copy(notice = action.completed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (client === deck) {
                    mutable.update {
                        it.copy(
                            error =
                                "${action.label.replaceFirstChar { char -> char.uppercase() }} not confirmed. " +
                                    (e.message ?: "Refresh the list before trying again.")
                        )
                    }
                }
            } finally {
                if (client === deck) mutable.update { it.copy(sessionMutationId = null) }
                refreshRequested = true
            }
        }
    }

    fun refreshProjects() {
        val deck = client ?: return
        if (projectsJob?.isActive == true) return
        val cache = store ?: return
        projectsJob =
            scope.launch {
                mutable.update { it.copy(projectsLoading = true, projectsError = null) }
                try {
                    val loaded = deck.projects()
                    if (client !== deck || store?.scope != cache.scope) return@launch
                    groups = loaded
                    cache.put(
                        "projects",
                        buildJsonObject {
                            put(
                                "projects",
                                kotlinx.serialization.json.JsonArray(loaded.map { it.json() }),
                            )
                        },
                    )
                    if (client !== deck || store?.scope != cache.scope) return@launch
                    mutable.update { it.copy(projects = mergeDeckProjects(loaded, it.sessions)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(projectsError = e.message ?: "Could not load Deck groups")
                    }
                } finally {
                    mutable.update { it.copy(projectsLoading = false) }
                }
            }
    }

    fun requestOpenSession(id: String) {
        requestedSession = id
        if (mutable.value.online) openRequestedSession()
    }

    private fun openRequestedSession() {
        val id = requestedSession ?: return
        requestedSession = null
        if (mutable.value.sessions.any { it.id == id }) {
            openSession(id)
        } else {
            mutable.update {
                it.copy(notice = "This session is no longer available in this profile.")
            }
        }
    }

    fun refresh() {
        refreshRequested = true
        if (!mutable.value.online) reconnect() else refreshProjects()
    }

    private fun startEvents(deck: DeckClient, attempt: Long) {
        eventsJob?.cancel()
        eventsJob =
            scope.launch {
                var cursor: String? = null
                while (isActive && attempt == generation) {
                    try {
                        if (cursor == null) {
                            // Initial snapshots and an expired bus cursor must not replay old
                            // alerts.
                            cursor = deck.currentEventCursor()
                            statusChanges.startEventStream()
                        }
                        deck.events(cursor) { event ->
                            event
                                .text("cursor")
                                .ifBlank { event.text("seq") }
                                .takeIf { it.isNotBlank() }
                                ?.let { cursor = it }
                            withContext(Dispatchers.Main.immediate) {
                                if (attempt != generation) {
                                    throw CancellationException("Connection changed")
                                }
                                statusChanges.transition(event, mutable.value.sessions)?.let {
                                    AgentNotifications.show(
                                        application,
                                        hostId,
                                        mutable.value.hostName,
                                        it.session,
                                        it.updateExisting,
                                    )
                                }
                            }
                            refreshRequested = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        refreshRequested = true
                        cursor = null
                        delay(5000)
                    }
                    delay(250)
                }
            }
    }

    fun openSession(id: String) {
        if (mutable.value.selectedId == id) return
        chatJob?.cancel()
        mutable.update {
            it.copy(
                selectedId = id,
                contextRemaining = null,
                showCreate = false,
                chat = AgentChatState(),
                chatLoading = true,
                chatError = null,
                historyMissing = false,
                historyChoices = null,
                modelSettings = null,
                live = false,
            )
        }
        val cache = store
        refreshRequested = true
        scope.launch {
            chatLock.withLock {
                val cached = cache?.chat(id) ?: AgentChatState()
                if (mutable.value.selectedId != id || store?.scope != cache?.scope) return@withLock
                reducer = DeckTimelineReducer().also { it.snapshot(cached.timeline) }
                mutable.update { it.copy(chat = cached, chatLoading = false) }
            }
            if (mutable.value.selectedId == id) startChat()
        }
    }

    fun back(): Boolean {
        if (mutable.value.showCreate) {
            mutable.update { it.copy(showCreate = false) }
            return true
        }
        if (mutable.value.selectedId == null) return false
        chatJob?.cancel()
        chatJob = null
        mutable.update {
            it.copy(
                selectedId = null,
                contextRemaining = null,
                chat = AgentChatState(),
                live = false,
                chatError = null,
                modelSettings = null,
            )
        }
        return true
    }

    fun showModelSettings() {
        val ui = mutable.value
        val session = ui.selected ?: return
        val deck = client ?: return
        if (!ui.online || ui.changingModel || !session.local || !session.supported) return
        val form = AgentModelSettings(session.id)
        mutable.update { it.copy(modelSettings = form) }
        scope.launch {
            try {
                val current = deck.modelSettings(session.id)
                mutable.update {
                    if (it.modelSettings !== form || client !== deck) {
                        it
                    } else {
                        it.copy(
                            modelSettings =
                                form.copy(
                                    current = current.current,
                                    choice = current.current,
                                    models = current.models,
                                    loading = false,
                                )
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update {
                    if (it.modelSettings !== form || client !== deck) {
                        it
                    } else {
                        it.copy(modelSettings = form.copy(loading = false, error = e.message))
                    }
                }
            }
        }
    }

    fun updateModelSettings(model: String, effort: String) {
        mutable.update { ui ->
            if (ui.changingModel) {
                ui
            } else {
                ui.copy(
                    modelSettings =
                        ui.modelSettings?.copy(choice = DeckModelSettings(model, effort))
                )
            }
        }
    }

    fun dismissModelSettings() {
        if (!mutable.value.changingModel) mutable.update { it.copy(modelSettings = null) }
    }

    fun applyModelSettings() {
        val ui = mutable.value
        val form = ui.modelSettings ?: return
        val deck = client ?: return
        if (
            !ui.online ||
                ui.sending ||
                ui.changingModel ||
                form.loading ||
                form.error != null ||
                form.sessionId != ui.selectedId ||
                form.choice == form.current ||
                form.choice.model.isBlank()
        ) {
            return
        }
        mutable.update { it.copy(changingModel = true) }
        scope.launch {
            try {
                val applied =
                    deck.modelSettings(form.sessionId, form.choice.model, form.choice.effort)
                mutable.update {
                    if (it.modelSettings?.sessionId != form.sessionId || client !== deck) {
                        it
                    } else {
                        it.copy(modelSettings = null)
                    }
                }
                if (mutable.value.selectedId == form.sessionId && client === deck) {
                    android.widget.Toast.makeText(
                            application,
                            "${applied.current.model} · ${applied.current.effort.ifBlank { "default" }}",
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        .show()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update {
                    if (it.modelSettings?.sessionId != form.sessionId) {
                        it
                    } else {
                        it.copy(
                            modelSettings =
                                form.copy(
                                    error = e.message ?: "Change unconfirmed. Check the terminal.",
                                    uncertain = true,
                                )
                        )
                    }
                }
            } finally {
                mutable.update { it.copy(changingModel = false) }
            }
        }
    }

    private fun startChat() {
        chatJob?.cancel()
        val deck = client ?: return
        val id = mutable.value.selectedId ?: return
        val session = mutable.value.selected ?: return
        if (
            !foreground ||
                !mutable.value.online ||
                !session.local ||
                !session.supported ||
                !deck.capabilities.recallEnabled
        ) {
            return
        }
        val attempt = generation
        mutable.update {
            it.copy(
                chatLoading = it.chat.timeline.rows.isEmpty() && !it.historyMissing,
                chatError = null,
            )
        }
        chatJob =
            scope.launch {
                var snapshotNeeded = true
                while (isActive && mutable.value.selectedId == id && attempt == generation) {
                    try {
                        if (snapshotNeeded) {
                            val page = deck.timeline(id)
                            mutateChat(id) { old ->
                                reducer.snapshot(page)
                                old.copy(timeline = reducer.value())
                            }
                            snapshotNeeded = false
                        }
                        mutable.update {
                            it.copy(
                                chatLoading = false,
                                chatError = null,
                                historyMissing = false,
                                live = true,
                            )
                        }
                        // A native record may emit several frames; commit only the complete group.
                        val pending = mutableListOf<JsonObject>()
                        deck.follow(id, reducer.cursor) { frame ->
                            currentCoroutineContext().ensureActive()
                            if (attempt != generation || mutable.value.selectedId != id) {
                                throw CancellationException("Chat changed")
                            }
                            when (frame.text("frame")) {
                                "resync_required" -> throw DeckResync()
                                "delivery" -> refreshRequested = true
                                "status" ->
                                    mutateChat(id) { old ->
                                        reducer.apply(listOf(frame))
                                        old.copy(timeline = reducer.value())
                                    }
                                else -> {
                                    pending += frame
                                    check(pending.size <= 4096) { "Transcript record too large" }
                                    if (frame.text("cursor").isNotBlank()) {
                                        mutateChat(id) { old ->
                                            reducer.apply(pending)
                                            old.copy(timeline = reducer.value())
                                        }
                                        pending.clear()
                                    }
                                }
                            }
                        }
                        // Lease renewal starts from the last fully committed native record.
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: DeckResync) {
                        snapshotNeeded = true
                    } catch (e: Exception) {
                        val missing = e is DeckCommandException && e.code == "NOT_FOUND"
                        mutable.update {
                            it.copy(
                                chatLoading = false,
                                live = false,
                                historyMissing = missing,
                                chatError =
                                    if (missing) null else e.message ?: "Could not load history",
                            )
                        }
                        // A new agent has no JSONL until its first turn. Retry quietly without
                        // replacing the empty chat/composer with a loading state every few seconds.
                        if (!missing && e !is DeckTransportException) break
                        delay(5000)
                        snapshotNeeded = true
                    }
                    delay(250)
                }
            }
    }

    fun loadOlder() {
        val id = mutable.value.selectedId ?: return
        val before = mutable.value.chat.timeline.before ?: return
        val deck = client ?: return
        val expectedReducer = reducer
        if (!mutable.value.online || mutable.value.loadingOlder) return
        mutable.update { it.copy(loadingOlder = true) }
        scope.launch {
            try {
                val page = deck.timeline(id, before)
                if (
                    mutable.value.selectedId == id && client === deck && reducer === expectedReducer
                ) {
                    mutateChat(id) { old ->
                        reducer.prepend(page)
                        old.copy(timeline = reducer.value())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message) }
            } finally {
                mutable.update { it.copy(loadingOlder = false) }
            }
        }
    }

    fun updateDraft(text: String) {
        val id = mutable.value.selectedId ?: return
        val cache = store ?: return
        if (text.toByteArray().size > 128 * 1024) return
        val key = "${cache.scope}:$id"
        pendingDrafts[key] = text
        mutable.update { it.copy(chat = it.chat.copy(draft = text)) }
        scope.launch {
            withContext(NonCancellable) {
                mutateChat(id, cache) { it.copy(draft = pendingDrafts[key] ?: text) }
            }
        }
    }

    fun attachFiles(id: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ui = mutable.value
        val deck = client
        val cache = store
        if (ui.selectedId != id || !ui.online || !ui.canSend || deck == null || cache == null) {
            mutable.update {
                it.copy(notice = "Reconnect and open the chat before attaching files.")
            }
            return
        }
        if (uploadJob?.isActive == true) return
        if (ui.chat.attachments.size + uris.size > AGENT_ATTACHMENT_COUNT) {
            mutable.update { it.copy(notice = "Attach up to 4 files per message (20 MiB each).") }
            return
        }
        val batch = UUID.randomUUID().toString()
        mutable.update { it.copy(upload = AgentUploadProgress(id, batch)) }
        uploadJob =
            scope.launch {
                try {
                    for (uri in uris) {
                        stageAgentFile(application, uri).use { file ->
                            mutable.update {
                                it.copy(
                                    upload =
                                        AgentUploadProgress(
                                            id,
                                            batch,
                                            file.name,
                                            total = file.file.length(),
                                        )
                                )
                            }
                            val path =
                                deck.uploadFile(file.name, file.file) { bytes ->
                                    mutable.update {
                                        it.copy(
                                            upload =
                                                it.upload
                                                    ?.takeIf { it.batchId == batch }
                                                    ?.copy(bytes = bytes)
                                        )
                                    }
                                }
                            val attachment =
                                AgentAttachment(
                                    UUID.randomUUID().toString(),
                                    file.name,
                                    file.mimeType,
                                    file.file.length(),
                                    path,
                                )
                            mutateChat(id, cache) {
                                it.copy(attachments = it.attachments + attachment)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it.copy(notice = "Could not attach file: ${e.message}") }
                } finally {
                    mutable.update {
                        if (it.upload?.batchId == batch) it.copy(upload = null) else it
                    }
                }
            }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
    }

    fun removeAttachment(attachmentId: String) {
        val id = mutable.value.selectedId ?: return
        if (mutable.value.sending) return
        scope.launch {
            mutateChat(id) {
                it.copy(attachments = it.attachments.filterNot { file -> file.id == attachmentId })
            }
        }
    }

    private suspend fun mutateChat(
        id: String,
        cache: AgentStateStore? = store,
        change: (AgentChatState) -> AgentChatState,
    ) =
        chatLock.withLock {
            cache ?: return@withLock
            val currentScope = store?.scope == cache.scope
            val old =
                if (currentScope && mutable.value.selectedId == id) {
                    mutable.value.chat
                } else {
                    cache.chat(id)
                }
            val changed = change(old)
            val rowIds = changed.timeline.rows.mapTo(mutableSetOf()) { it.id }
            val receipts =
                uniqueDeckSends(changed.sends).filterNot {
                    it.state == "landed" && it.landedRowId.isNotBlank() && it.landedRowId in rowIds
                }
            val recentCompleted =
                receipts
                    .filter { it.state in setOf("landed", "cancelled") }
                    .takeLast(20)
                    .map { it.localId }
                    .toSet()
            val next =
                changed.copy(
                    sends =
                        receipts.filter {
                            it.state !in setOf("landed", "cancelled") ||
                                it.localId in recentCompleted
                        }
                )
            // Publish before Room suspends, so typing during a disk write cannot be overwritten.
            var published = next
            if (store?.scope == cache.scope && mutable.value.selectedId == id) {
                mutable.update {
                    published =
                        next.copy(
                            draft = if (it.chat.draft != old.draft) it.chat.draft else next.draft
                        )
                    it.copy(chat = published)
                }
            }
            cache.saveChat(id, published)
        }

    fun send() {
        val ui = mutable.value
        val id = ui.selectedId ?: return
        val session = ui.selected ?: return
        val deck = client ?: return
        if (!ui.canSend || (ui.chat.draft.isBlank() && ui.chat.attachments.isEmpty())) return
        val cache = store ?: return
        mutable.update { it.copy(sending = true) }
        val text = ui.chat.draft
        val localId = UUID.randomUUID().toString()
        val unknown =
            DeckSend(
                localId,
                text,
                buildJsonObject { put("state", "unknown") },
                ui.chat.timeline.rows.lastOrNull()?.id,
                System.currentTimeMillis(),
                ui.chat.attachments,
            )
        scope.launch {
            var started = false
            try {
                val receipt =
                    deck.send(session, agentAttachmentMessage(text, ui.chat.attachments)) {
                        mutateChat(id, cache) { it.copy(sends = it.sends + unknown) }
                        started = true
                    }
                pendingDrafts.replace("${cache.scope}:$id", text, "")
                mutateChat(id, cache) { old ->
                    old.copy(
                        draft = if (old.draft == text) "" else old.draft,
                        attachments =
                            old.attachments.filterNot { file ->
                                ui.chat.attachments.any { it.id == file.id }
                            },
                        sends =
                            old.sends.map {
                                if (it.localId == localId) it.copy(receipt = receipt) else it
                            },
                    )
                }
                refreshRequested = true
            } catch (e: Exception) {
                // The persisted unknown intent remains even if exec/response was interrupted.
                mutable.update {
                    it.copy(
                        error =
                            if (started) {
                                "Delivery is unconfirmed. Check the chat and queue before sending again."
                            } else {
                                e.message
                            }
                    )
                }
                if (e is CancellationException) throw e
            } finally {
                mutable.update { it.copy(sending = false) }
            }
        }
    }

    private suspend fun reconcileSends(deck: DeckClient) {
        val id = mutable.value.selectedId ?: return
        val cache = store ?: return
        val sends = mutable.value.chat.sends
        for (send in sends) {
            val native = send.receipt.text("delivery") == "codex_native"
            if (
                (send.sendId.isBlank() && !native) ||
                    (send.final && !send.uncertain) ||
                    (native && send.uncertain)
            ) {
                continue
            }
            val receipt =
                try {
                    if (native) {
                        deck.nativeSendStatus(id, send.receipt)
                    } else {
                        deck.sendStatus(send.sendId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
            mutateChat(id, cache) { old ->
                old.copy(
                    sends =
                        old.sends.map {
                            if (it.localId == send.localId) it.copy(receipt = receipt) else it
                        }
                )
            }
        }
        val session = mutable.value.sessions.firstOrNull { it.id == id }
        if (session?.local != true) return
        val queued =
            try {
                deck.queue(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
        for (item in queued) {
            val sendId = item.text("id")
            if (sendId.isBlank() || sends.any { it.sendId == sendId }) continue
            val receipt =
                try {
                    deck.sendStatus(sendId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
            mutateChat(id, cache) {
                // Navigation may have selected another chat while the SSH request was in flight.
                // Deduplicate against this session's state under the same lock as the write.
                if (it.sends.any { send -> send.sendId == sendId }) return@mutateChat it
                it.copy(
                    sends =
                        it.sends +
                            DeckSend(
                                "remote:$sendId",
                                receipt.text("message").ifBlank { item.text("text_preview") },
                                receipt,
                            )
                )
            }
        }
    }

    fun cancel(send: DeckSend) {
        val id = mutable.value.selectedId ?: return
        val deck = client ?: return
        if (!mutable.value.online || send.state != "queued" || send.sendId.isBlank()) return
        scope.launch {
            try {
                val result = deck.cancelSend(send.sendId)
                if (result.text("outcome") == "cancelled") {
                    mutateChat(id) { old ->
                        old.copy(
                            sends =
                                old.sends.map {
                                    if (it.localId == send.localId) {
                                        it.copy(
                                            receipt =
                                                JsonObject(
                                                    it.receipt +
                                                        ("state" to JsonPrimitive("cancelled"))
                                                )
                                        )
                                    } else {
                                        it
                                    }
                                }
                        )
                    }
                } else {
                    mutable.update {
                        it.copy(
                            notice =
                                "Cancellation not confirmed: ${result.text("outcome")}. Checking delivery status."
                        )
                    }
                }
                refreshRequested = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update {
                    it.copy(
                        notice = "Cancellation is unconfirmed. The message may still be delivered."
                    )
                }
            }
        }
    }

    fun recoverDraft(send: DeckSend) {
        updateDraft(send.text)
        val id = mutable.value.selectedId ?: return
        scope.launch { mutateChat(id) { it.copy(attachments = send.attachments) } }
    }

    fun showCreate() {
        val project = mutable.value.projects.firstOrNull()
        refreshProjects()
        mutable.update {
            it.copy(
                showCreate = true,
                selectedId = null,
                create =
                    it.create.let { form ->
                        if (form.path.isBlank() && form.group.isBlank()) {
                            form.copy(
                                path = project?.path.orEmpty(),
                                group = project?.group.orEmpty(),
                            )
                        } else {
                            form
                        }
                    },
            )
        }
        chatJob?.cancel()
    }

    fun updateCreate(form: AgentCreateForm) {
        if (
            mutable.value.creating ||
                form.message.toByteArray().size > 128 * 1024 ||
                form.title.length > 200
        ) {
            return
        }
        mutable.update { it.copy(create = form) }
        scope.launch { saveCreation() }
    }

    private suspend fun saveCreation(unknown: Boolean? = null) =
        creationLock.withLock {
            val value = mutable.value
            val preserve = unknown ?: value.creationUnknown
            store?.put(
                "creation",
                JsonObject(value.create.json() + ("unknown" to JsonPrimitive(preserve))),
                protected = preserve || value.create.message.isNotBlank(),
            )
        }

    fun acknowledgeCreation() {
        mutable.update { it.copy(creationUnknown = false) }
        scope.launch { saveCreation() }
    }

    fun create() {
        val ui = mutable.value
        val deck = client ?: return
        val cache = store ?: return
        val known =
            ui.projects.firstOrNull {
                it.group == ui.create.group && (it.path == ui.create.path || it.path.isBlank())
            } ?: return
        if (!ui.create.path.startsWith('/')) return
        val project = known.copy(path = ui.create.path)
        if (!ui.online || ui.creating || ui.creationUnknown) return
        mutable.update { it.copy(creating = true) }
        scope.launch {
            var started = false
            try {
                val form = ui.create
                val receipt =
                    deck.launch(
                        project,
                        form.tool,
                        form.title,
                        form.model,
                        form.effort,
                        form.account,
                    ) {
                        creationLock.withLock {
                            cache.put(
                                "creation",
                                JsonObject(form.json() + ("unknown" to JsonPrimitive(true))),
                                protected = true,
                            )
                        }
                        started = true
                        mutable.update { it.copy(creationUnknown = true) }
                    }
                val id = receipt.text("session_id").ifBlank { receipt.text("id") }
                check(id.isNotBlank()) {
                    "Creation returned no session ID. Check the session list."
                }
                // The first task is a saved draft. Sending is a separate, explicit operation.
                cache.saveChat(id, AgentChatState(draft = form.message))
                val reset = AgentCreateForm(path = form.path, tool = form.tool, group = form.group)
                creationLock.withLock { cache.put("creation", reset.json()) }
                if (store?.scope != cache.scope) return@launch
                mutable.update {
                    it.copy(
                        creationUnknown = false,
                        create = reset,
                        sessions =
                            it.sessions.filterNot { session -> session.id == id } +
                                DeckSession(
                                    JsonObject(
                                        receipt +
                                            mapOf(
                                                "id" to JsonPrimitive(id),
                                                "path" to JsonPrimitive(form.path),
                                                "tool" to JsonPrimitive(form.tool),
                                                "title" to
                                                    JsonPrimitive(
                                                        receipt.text("title").ifBlank {
                                                            form.title.ifBlank { id }
                                                        }
                                                    ),
                                            )
                                    )
                                ),
                    )
                }
                openSession(id)
                refreshRequested = true
                if (form.message.isNotBlank()) {
                    mutable.update {
                        it.copy(notice = "Session created. Your first message is ready to send.")
                    }
                }
            } catch (e: Exception) {
                mutable.update {
                    it.copy(
                        error =
                            if (started) {
                                "Creation outcome is unconfirmed. Refresh sessions before trying again."
                            } else {
                                e.message
                            }
                    )
                }
                refreshRequested = true
                if (e is CancellationException) throw e
            } finally {
                mutable.update { it.copy(creating = false) }
            }
        }
    }

    fun enableRecall() {
        val deck = client ?: return
        scope.launch {
            try {
                deck.enableRecall()
                mutable.update { it.copy(capabilities = deck.capabilities) }
                startChat()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    fun openTerminal(onOpened: () -> Unit) {
        val selected = mutable.value.selected ?: return
        val route = connection ?: return
        val deck = client ?: return
        if (!mutable.value.online || !selected.local) return
        scope.launch {
            try {
                route.spec.routeSnapshot?.requireCurrent()
                val repository = TerminalSessionRepository.getInstance(application)
                val spec =
                    route.spec.copy(
                        displayName = selected.title,
                        remoteStartupCommand = deck.attach(selected.id),
                        agentSessionId = selected.id,
                    )
                val existing =
                    repository.tabsForHost(hostId).firstOrNull {
                        it.spec.agentSessionId == selected.id &&
                            it.spec.remoteStartupCommand == spec.remoteStartupCommand
                    }
                if (existing != null) {
                    repository.selectTab(existing.id)
                } else {
                    check(repository.tryOpenTab(spec) != null) {
                        "An app update is being installed"
                    }
                }
                onOpened()
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearCachedHistory() {
        scope.launch {
            store?.clearHistory()
            mutable.update {
                it.copy(
                    notice =
                        "Unprotected cached history cleared. Drafts and delivery receipts are kept."
                )
            }
        }
    }

    fun dispose() {
        disconnect()
        scope.cancel()
    }

    fun retryHistory() = startChat()

    fun dismissHistoryChoices() {
        if (!mutable.value.linkingHistory) mutable.update { it.copy(historyChoices = null) }
    }

    fun chooseHistory() {
        val session = mutable.value.selected ?: return
        val deck = client ?: return
        if (!mutable.value.online || mutable.value.historyChoicesLoading) return
        mutable.update { it.copy(historyChoicesLoading = true, historyChoices = emptyList()) }
        scope.launch {
            try {
                val choices = deck.nativeHistories(session)
                if (mutable.value.selectedId == session.id && client === deck) {
                    mutable.update { it.copy(historyChoices = choices) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message, historyChoices = null) }
            } finally {
                mutable.update { it.copy(historyChoicesLoading = false) }
            }
        }
    }

    fun linkHistory(history: DeckNativeHistory) {
        val session = mutable.value.selected ?: return
        val deck = client ?: return
        if (
            !mutable.value.online ||
                mutable.value.linkingHistory ||
                history !in mutable.value.historyChoices.orEmpty()
        ) {
            return
        }
        mutable.update { it.copy(linkingHistory = true) }
        scope.launch {
            try {
                deck.linkHistory(session, history)
                if (mutable.value.selectedId == session.id && client === deck) {
                    mutable.update { it.copy(historyChoices = null, historyMissing = false) }
                    startChat()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(
                        error =
                            "History link could not be confirmed. Refresh before trying again. ${e.message.orEmpty()}"
                    )
                }
            } finally {
                mutable.update { it.copy(linkingHistory = false) }
            }
        }
    }

    private class DeckResync : Exception()
}
