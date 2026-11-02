package io.github.kontsevoye.ichuchu.ui.screens.agents

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.service.agents.AgentChatState
import io.github.kontsevoye.ichuchu.service.agents.AgentConnection
import io.github.kontsevoye.ichuchu.service.agents.AgentStateStore
import io.github.kontsevoye.ichuchu.service.agents.AgentStatus
import io.github.kontsevoye.ichuchu.service.agents.DeckCapabilities
import io.github.kontsevoye.ichuchu.service.agents.DeckClient
import io.github.kontsevoye.ichuchu.service.agents.DeckCommandException
import io.github.kontsevoye.ichuchu.service.agents.DeckSend
import io.github.kontsevoye.ichuchu.service.agents.DeckSession
import io.github.kontsevoye.ichuchu.service.agents.DeckTimelineReducer
import io.github.kontsevoye.ichuchu.service.agents.DeckTransportException
import io.github.kontsevoye.ichuchu.service.agents.SshDeckTransport
import io.github.kontsevoye.ichuchu.service.agents.flag
import io.github.kontsevoye.ichuchu.service.agents.knownDeckProjects
import io.github.kontsevoye.ichuchu.service.agents.text
import io.github.kontsevoye.ichuchu.service.ssh.SshConnectionException
import io.github.kontsevoye.ichuchu.service.terminal.HostKeyPrompt
import io.github.kontsevoye.ichuchu.service.terminal.TerminalSessionRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
) {
    fun json(): JsonObject = buildJsonObject {
        put("path", path)
        put("tool", tool)
        put("title", title)
        put("model", model)
        put("effort", effort)
        put("account", account)
        put("message", message)
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
            )
    }
}

data class AgentUiState(
    val hostName: String = "agents",
    val profile: String = "default",
    val online: Boolean = false,
    val connecting: Boolean = false,
    val authRequest: Long = 0,
    val capabilities: DeckCapabilities? = null,
    val sessions: List<DeckSession> = emptyList(),
    val selectedId: String? = null,
    val chat: AgentChatState = AgentChatState(),
    val chatLoading: Boolean = false,
    val chatError: String? = null,
    val live: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val showCreate: Boolean = false,
    val create: AgentCreateForm = AgentCreateForm(),
    val creating: Boolean = false,
    val creationUnknown: Boolean = false,
    val sending: Boolean = false,
    val loadingOlder: Boolean = false,
) {
    val selected: DeckSession?
        get() = sessions.firstOrNull { it.id == selectedId }

    val canSend: Boolean
        get() =
            online &&
                !sending &&
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

/** Foreground-only coordinator. Reads may reconnect; mutations never replay automatically. */
class AgentViewModel(application: Application, val hostId: Long) : AndroidViewModel(application) {
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
    private var foreground = false
    private var generation = 0L
    private var authenticationGeneration = 0L
    private val chatLock = Mutex()
    private var reducer = DeckTimelineReducer()
    private var refreshRequested = false
    private val creationLock = Mutex()
    private var authorizedRoute: String? = null
    private var reconnectAttempt = 0
    private val pendingDrafts = ConcurrentHashMap<String, String>()

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) {
            reconnect()
        } else {
            authorizedRoute = null
            disconnect()
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
        if (!foreground) return
        val previous = connection.takeIf { automatic }
        disconnect()
        val attempt = generation
        mutable.update { it.copy(connecting = true, error = null) }
        network =
            viewModelScope.launch {
                var retry = false
                val route = AgentConnection(getApplication(), db, hostId)
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
                    mutable.update { it.copy(online = true) }
                    reconnectAttempt = 0
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
                                connecting = false,
                                live = false,
                                authRequest = 0,
                            )
                        }
                        hostKeyPrompt.value = null
                    }
                }
                if (retry && foreground && attempt == generation) {
                    mutable.update { it.copy(connecting = true) }
                    delay((1000L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(15_000L))
                    reconnectAttempt++
                    if (foreground && attempt == generation) beginConnection(automatic = true)
                }
            }
    }

    private fun disconnect() {
        generation++
        decision?.cancel()
        decision = null
        connection?.close()
        client?.transport?.interrupt()
        network?.cancel()
        chatJob?.cancel()
        eventsJob?.cancel()
        network = null
        chatJob = null
        eventsJob = null
        mutable.update {
            it.copy(online = false, connecting = false, live = false, authRequest = 0)
        }
    }

    private suspend fun restoreStore() {
        val cache = store ?: return
        val sessions = cache.sessions()
        val creation = cache.get("creation")
        mutable.update {
            it.copy(
                sessions = sessions,
                create = creation?.let(AgentCreateForm::parse) ?: it.create,
                creationUnknown = creation?.flag("unknown") == true,
            )
        }
    }

    private suspend fun refreshSessions(deck: DeckClient) {
        val sessions = deck.sessions()
        store?.saveSessions(sessions)
        mutable.update { it.copy(sessions = sessions) }
    }

    fun refresh() {
        refreshRequested = true
        if (!mutable.value.online) reconnect()
    }

    private fun startEvents(deck: DeckClient, attempt: Long) {
        eventsJob?.cancel()
        eventsJob =
            viewModelScope.launch {
                var cursor: String? = deck.eventCursor
                while (isActive && attempt == generation) {
                    try {
                        deck.events(cursor) { event ->
                            event
                                .text("cursor")
                                .ifBlank { event.text("seq") }
                                .takeIf { it.isNotBlank() }
                                ?.let { cursor = it }
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
                showCreate = false,
                chat = AgentChatState(),
                chatLoading = true,
                chatError = null,
                live = false,
            )
        }
        val cache = store
        viewModelScope.launch {
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
            it.copy(selectedId = null, chat = AgentChatState(), live = false, chatError = null)
        }
        return true
    }

    private fun startChat() {
        chatJob?.cancel()
        val deck = client ?: return
        val id = mutable.value.selectedId ?: return
        val session = mutable.value.selected ?: return
        if (
            !mutable.value.online ||
                !session.local ||
                !session.supported ||
                !deck.capabilities.recallEnabled
        ) {
            return
        }
        val attempt = generation
        chatJob =
            viewModelScope.launch {
                var snapshotNeeded = true
                while (isActive && mutable.value.selectedId == id && attempt == generation) {
                    try {
                        if (snapshotNeeded) {
                            mutable.update { it.copy(chatLoading = true) }
                            val page = deck.timeline(id)
                            mutateChat(id) { old ->
                                reducer.snapshot(page)
                                old.copy(timeline = reducer.value())
                            }
                            snapshotNeeded = false
                        }
                        mutable.update {
                            it.copy(chatLoading = false, chatError = null, live = true)
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
                        mutable.update {
                            it.copy(
                                chatLoading = false,
                                live = false,
                                chatError =
                                    if (e is DeckCommandException && e.code == "NOT_FOUND") {
                                        "Transcript is not available yet. The session may still be starting."
                                    } else {
                                        e.message
                                    },
                            )
                        }
                        delay(3000)
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            withContext(NonCancellable) {
                mutateChat(id, cache) { it.copy(draft = pendingDrafts[key] ?: text) }
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
                changed.sends.filterNot {
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
        val deck = client ?: return
        if (!ui.canSend || ui.chat.draft.isBlank()) return
        val cache = store ?: return
        mutable.update { it.copy(sending = true) }
        val text = ui.chat.draft
        val localId = UUID.randomUUID().toString()
        val unknown = DeckSend(localId, text, buildJsonObject { put("state", "unknown") })
        viewModelScope.launch {
            var started = false
            try {
                val receipt =
                    deck.send(id, text) {
                        mutateChat(id, cache) { it.copy(sends = it.sends + unknown) }
                        started = true
                    }
                pendingDrafts.replace("${cache.scope}:$id", text, "")
                mutateChat(id, cache) { old ->
                    old.copy(
                        draft = if (old.draft == text) "" else old.draft,
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
        for (send in mutable.value.chat.sends) {
            if (send.sendId.isBlank() || (send.final && !send.uncertain)) continue
            val receipt =
                try {
                    deck.sendStatus(send.sendId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
            mutateChat(id) { old ->
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
            if (sendId.isBlank() || mutable.value.chat.sends.any { it.sendId == sendId }) continue
            val receipt =
                try {
                    deck.sendStatus(sendId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
            mutateChat(id) {
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
        viewModelScope.launch {
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
    }

    fun showCreate() {
        val project = knownDeckProjects(mutable.value.sessions).firstOrNull()
        mutable.update {
            it.copy(
                showCreate = true,
                selectedId = null,
                create =
                    it.create.let { form ->
                        if (form.path.isBlank()) form.copy(path = project?.path.orEmpty()) else form
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
        viewModelScope.launch { saveCreation() }
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
        viewModelScope.launch { saveCreation() }
    }

    fun create() {
        val ui = mutable.value
        val deck = client ?: return
        val cache = store ?: return
        val project =
            knownDeckProjects(ui.sessions).firstOrNull { it.path == ui.create.path } ?: return
        if (!ui.online || ui.creating || ui.creationUnknown) return
        mutable.update { it.copy(creating = true) }
        viewModelScope.launch {
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
                val reset = AgentCreateForm(path = form.path, tool = form.tool)
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            try {
                route.spec.routeSnapshot?.requireCurrent()
                val repository = TerminalSessionRepository.getInstance(getApplication())
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
        viewModelScope.launch {
            store?.clearHistory()
            mutable.update {
                it.copy(
                    notice =
                        "Unprotected cached history cleared. Drafts and delivery receipts are kept."
                )
            }
        }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private class DeckResync : Exception()

    companion object {
        fun factory(application: Application, hostId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AgentViewModel(application, hostId) as T
                }
            }
    }
}
