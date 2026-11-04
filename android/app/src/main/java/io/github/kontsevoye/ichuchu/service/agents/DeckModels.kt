package io.github.kontsevoye.ichuchu.service.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal val deckJson = Json { ignoreUnknownKeys = true }

data class DeckModelSettings(val model: String = "", val effort: String = "")

data class DeckModelOptions(val current: DeckModelSettings, val models: Map<String, List<String>>)

internal fun JsonObject.text(key: String): String =
    (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.flag(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull == true

internal fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

internal fun JsonObject.objects(key: String): List<JsonObject> =
    (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun JsonObject.strings(key: String): List<String> =
    (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

enum class AgentStatus(val label: String) {
    Running("working"),
    Ready("ready for input"),
    Attention("needs attention"),
    Starting("starting"),
    Queued("waiting to start"),
    Stopped("stopped"),
    Error("error"),
    Waiting("waiting"),
    Unknown("unknown"),
}

data class DeckSession(val raw: JsonObject) {
    val id: String = raw.text("id").also { require(it.isNotBlank()) { "Session has no ID" } }
    val title: String
        get() = raw.text("title").ifBlank { id }

    val path: String
        get() = raw.text("path")

    val project: String
        get() = path.trimEnd('/').substringAfterLast('/').ifBlank { "unknown project" }

    val tool: String
        get() = raw.text("tool")

    val group: String
        get() = raw.text("group")

    val profile: String
        get() = raw.text("profile")

    val activity: String
        get() = raw.text("last_activity_at").ifBlank { raw.text("created_at") }

    val supported: Boolean
        get() = tool == "claude" || tool == "codex"

    val local: Boolean
        get() = raw.text("ssh_host").isBlank()

    val substate: String
        get() = raw.text("substate")

    val needsTerminal: Boolean
        get() =
            substate in
                setOf("interactive-menu", "auth-401", "account-blocked", "model-unavailable")

    val detail: String
        get() =
            raw.text("substate_detail").ifBlank {
                when (substate) {
                    "",
                    "running",
                    "idle-at-empty-prompt" -> ""
                    "interactive-menu" -> "Input needed in the terminal"
                    "auth-401" -> "Sign in again in the terminal"
                    "usage-limit",
                    "model-limit" -> "Agent usage limit reached"
                    "background-work" -> "Background work is still running"
                    else -> substate.replace('-', ' ')
                }
            }

    val status: AgentStatus
        get() =
            when {
                raw.text("status_source") == "cached" -> AgentStatus.Unknown
                substate == "process-exited" || raw.text("status") in setOf("stopped", "dead") ->
                    AgentStatus.Stopped
                raw.text("status") == "starting" -> AgentStatus.Starting
                raw.text("status") == "queued" -> AgentStatus.Queued
                needsTerminal || substate in setOf("usage-limit", "model-limit") ->
                    AgentStatus.Attention
                raw.text("status") == "error" -> AgentStatus.Error
                substate == "idle-at-empty-prompt" -> AgentStatus.Ready
                substate in setOf("running", "background-work") ||
                    raw.text("status") == "running" -> AgentStatus.Running
                raw.text("status") in setOf("waiting", "idle") -> AgentStatus.Waiting
                else -> AgentStatus.Unknown
            }
}

data class DeckProject(val path: String, val group: String) {
    val name: String
        get() = group.ifBlank { path.trimEnd('/').substringAfterLast('/') }

    val label: String
        get() = "$name · ${path.ifBlank { "choose directory" }}"

    fun json(): JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("path", JsonPrimitive(path))
            put("group", JsonPrimitive(group))
        }
}

/** Group paths are identifiers, never filesystem paths. Keep groups without a default directory. */
fun deckGroupPaths(value: JsonObject): List<String> {
    fun collect(groups: List<JsonObject>): List<String> =
        groups.flatMap { listOf(it.text("path")) + collect(it.objects("children")) }
    return collect(value.objects("groups")).filter { it.isNotBlank() }.distinct()
}

fun mergeDeckProjects(groups: List<DeckProject>, sessions: List<DeckSession>): List<DeckProject> =
    (groups + knownDeckProjects(sessions))
        .distinctBy { it.group to it.path }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

data class DeckNativeHistory(val raw: JsonObject) {
    val id: String
        get() = raw.text("native_id")

    val title: String
        get() = raw.text("title").ifBlank { id }

    val path: String
        get() = raw.text("cwd")

    val tool: String
        get() = raw.text("harness")

    val startedAt: Long
        get() = raw.text("started_at").toLongOrNull() ?: 0L
}

fun knownDeckProjects(sessions: List<DeckSession>): List<DeckProject> =
    sessions
        .filter { it.local && it.path.startsWith('/') }
        .sortedByDescending { it.activity }
        .distinctBy { it.path }
        .map { DeckProject(it.path, it.group) }

data class DeckTool(
    val name: String,
    val models: List<String>,
    val efforts: List<String>,
    val modelEfforts: Map<String, List<String>> = emptyMap(),
)

data class DeckCapabilities(
    val executable: String,
    val version: String,
    val recallEnabled: Boolean,
    val guardedSend: Boolean,
    val tools: List<DeckTool>,
    val accounts: List<String>,
    val launchFields: Set<String>,
    val events: Boolean,
    val codexComposerSend: Boolean = false,
)

data class DeckRow(val raw: JsonObject) {
    val id: String = raw.text("id").also { require(it.isNotBlank()) { "Transcript row has no ID" } }
    val kind: String
        get() = raw.text("kind").ifBlank { "other" }

    val body: String
        get() = raw.text("body")

    val title: String
        get() = raw.text("title")

    val detail: String
        get() = raw.text("detail")

    val summary: String
        get() = raw.text("summary")

    val timestamp: String
        get() = raw.text("ts")

    val error: Boolean
        get() = raw.flag("is_error")

    val finished: Boolean
        get() = raw.flag("finished")

    val meta: JsonObject
        get() = raw["meta"] as? JsonObject ?: JsonObject(emptyMap())

    val children: List<DeckRow>
        get() = raw.objects("children").map(::DeckRow)

    val isMessage: Boolean
        get() = kind == "user" || kind == "assistant"
}

data class DeckTimeline(
    val rows: List<DeckRow>,
    val cursor: String?,
    val before: String?,
    val status: JsonObject? = null,
) {
    companion object {
        fun parse(json: JsonObject): DeckTimeline {
            require(json.text("schema") == "agent-deck.recall.rows/v2") {
                "Unsupported Agent Deck transcript schema"
            }
            return DeckTimeline(
                json.objects("turns").map(::DeckRow),
                json.text("through_cursor").ifBlank { null },
                json.text("before_cursor").ifBlank { null },
                json["status"] as? JsonObject,
            )
        }
    }
}

/** Stable row IDs are the reconciliation key, never message text or timestamps. */
class DeckTimelineReducer {
    private val rows = LinkedHashMap<String, JsonObject>()
    var cursor: String? = null
        private set

    var before: String? = null
        private set

    var status: JsonObject? = null
        private set

    fun snapshot(page: DeckTimeline) {
        rows.clear()
        page.rows.forEach { rows[it.id] = it.raw }
        cursor = page.cursor
        before = page.before
        status = page.status
    }

    fun prepend(page: DeckTimeline) {
        val newer = rows.toMap()
        rows.clear()
        page.rows.forEach { rows[it.id] = it.raw }
        rows.putAll(newer)
        before = page.before
    }

    /** Apply a whole native-record group; only its final frame advances the checkpoint. */
    fun apply(frames: List<JsonObject>) {
        for (frame in frames) {
            when (frame.text("frame")) {
                "row" -> (frame["row"] as? JsonObject)?.let { rows[DeckRow(it).id] = it }
                "update" ->
                    (frame["row"] as? JsonObject)?.let { update ->
                        val id = update.text("id")
                        val old = rows[id] ?: return@let
                        val merged = old.toMutableMap().apply { putAll(update) }
                        val meta = update["meta"] as? JsonObject
                        if (meta != null) {
                            merged["meta"] =
                                JsonObject((old["meta"] as? JsonObject).orEmpty() + meta)
                        }
                        if (
                            update["queued"] == JsonPrimitive(false) &&
                                update.text("ts").isNotBlank()
                        ) {
                            rows.remove(id)
                        }
                        rows[id] = JsonObject(merged)
                    }
                "remove" -> rows.remove(frame.text("id"))
                "status" -> status = frame
            }
            frame.text("cursor").takeIf { it.isNotBlank() }?.let { cursor = it }
        }
    }

    fun value(): DeckTimeline = DeckTimeline(rows.values.map(::DeckRow), cursor, before, status)
}

data class DeckSend(
    val localId: String,
    val text: String,
    val receipt: JsonObject,
    val anchorRowId: String? = null,
    val createdAt: Long = 0,
    val attachments: List<AgentAttachment> = emptyList(),
) {
    val sendId: String
        get() = receipt.text("send_id").ifBlank { receipt.text("id") }

    val state: String
        get() = receipt.text("state").ifBlank { "unknown" }

    val landedRowId: String
        get() = receipt.text("landed_row_id")

    val uncertain: Boolean
        get() =
            state == "unknown" ||
                (state in setOf("typed", "submitted") &&
                    receipt.flag("settled") &&
                    !(state == "submitted" &&
                        receipt.flag("submitted") &&
                        receipt.text("confirmation") == "confirmed"))

    val final: Boolean
        get() =
            state in setOf("failed", "cancelled", "landed") ||
                uncertain ||
                (state == "submitted" && receipt.flag("settled"))

    val label: String
        get() =
            when {
                uncertain -> "delivery unconfirmed"
                state == "landed" -> "received by agent"
                state == "submitted" && final -> "submitted to agent"
                state == "failed" -> "not delivered"
                state == "cancelled" -> "cancelled"
                state == "queued" -> "queued"
                else -> "sending"
            }
}

internal fun parseDeckObject(value: String): JsonObject =
    deckJson.parseToJsonElement(value).jsonObject

/** Repair repeated queue imports by identity, preserving drafts' attachment and ordering data. */
fun uniqueDeckSends(sends: List<DeckSend>): List<DeckSend> {
    val result = mutableListOf<DeckSend>()
    val localIds = mutableMapOf<String, Int>()
    val sendIds = mutableMapOf<String, Int>()
    for (send in sends) {
        val index =
            localIds[send.localId] ?: send.sendId.takeIf { it.isNotBlank() }?.let(sendIds::get)
        if (index == null) {
            localIds[send.localId] = result.size
            if (send.sendId.isNotBlank()) sendIds[send.sendId] = result.size
            result += send
        } else {
            val old = result[index]
            result[index] =
                old.copy(
                    text = old.text.ifBlank { send.text },
                    receipt =
                        if (old.final && !old.uncertain && !send.final) {
                            old.receipt
                        } else {
                            send.receipt
                        },
                    anchorRowId = old.anchorRowId ?: send.anchorRowId,
                    createdAt = old.createdAt.takeIf { it > 0 } ?: send.createdAt,
                    attachments = (old.attachments + send.attachments).distinctBy { it.id },
                )
            localIds[send.localId] = index
            if (send.sendId.isNotBlank()) sendIds[send.sendId] = index
        }
    }
    return result
}
