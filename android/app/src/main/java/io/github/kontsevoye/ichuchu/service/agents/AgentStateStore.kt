package io.github.kontsevoye.ichuchu.service.agents

import androidx.room.withTransaction
import io.github.kontsevoye.ichuchu.data.db.AgentStateEntity
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentChatState(
    val timeline: DeckTimeline = DeckTimeline(emptyList(), null, null),
    val draft: String = "",
    val sends: List<DeckSend> = emptyList(),
)

/**
 * Room encrypts snapshots, drafts and receipts together. Cursors never commit before their rows.
 */
class AgentStateStore(private val db: AppDatabase, private val hostId: Long, val scope: String) {
    private val dao = db.agentStateDao()

    suspend fun chat(id: String): AgentChatState {
        val data = get("chat:$id") ?: return AgentChatState()
        return AgentChatState(
            (data["timeline"] as? JsonObject)?.let(DeckTimeline::parse)
                ?: DeckTimeline(emptyList(), null, null),
            data.text("draft"),
            data.objects("sends").map {
                DeckSend(
                    it.text("localId"),
                    it.text("text"),
                    it["receipt"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            },
        )
    }

    suspend fun saveChat(id: String, state: AgentChatState) {
        // Bound rendered history independently of protected drafts/unknown mutations.
        var bytes = 0
        val recent =
            state.timeline.rows
                .asReversed()
                .take(1000)
                .takeWhile {
                    bytes += it.raw.toString().toByteArray().size
                    bytes <= 2 * 1024 * 1024
                }
                .asReversed()
        val cachedTimeline = state.timeline.copy(rows = recent)
        put(
            "chat:$id",
            buildJsonObject {
                put("timeline", timelineJson(cachedTimeline))
                put("draft", state.draft)
                put(
                    "sends",
                    JsonArray(
                        state.sends.map { send ->
                            buildJsonObject {
                                put("localId", send.localId)
                                put("text", send.text)
                                put("receipt", send.receipt)
                            }
                        }
                    ),
                )
            },
            state.draft.isNotBlank() ||
                state.sends.any { it.state !in setOf("landed", "cancelled") },
        )
    }

    suspend fun sessions(): List<DeckSession> =
        get("sessions")?.objects("sessions")?.map(::DeckSession).orEmpty()

    suspend fun saveSessions(sessions: List<DeckSession>) =
        put(
            "sessions",
            buildJsonObject {
                put("sessions", JsonArray(sessions.map { it.raw }))
                put("savedAt", System.currentTimeMillis())
            },
        )

    suspend fun get(name: String): JsonObject? =
        dao.get("$scope:$name")?.let { runCatching { parseDeckObject(it.payload) }.getOrNull() }

    suspend fun put(name: String, value: JsonObject, protected: Boolean = false) {
        db.withTransaction {
            dao.clearOrphans()
            // A pending draft write must not recreate private state after its host was deleted.
            if (db.hostProfileDao().getById(hostId) == null) return@withTransaction
            dao.put(
                AgentStateEntity(
                    "$scope:$name",
                    hostId,
                    value.toString(),
                    System.currentTimeMillis(),
                    protected,
                )
            )
            dao.trim(hostId)
            var bytes = dao.bytes(hostId)
            for (entry in dao.evictable(hostId)) {
                if (bytes <= 50L * 1024 * 1024) break
                dao.evict(entry.cacheKey)
                bytes -= entry.payload.toByteArray().size
            }
        }
    }

    suspend fun clearHistory() {
        // Explicit clearing removes cached history only; drafts and receipts are protected.
        db.withTransaction { dao.evictable(hostId).forEach { dao.evict(it.cacheKey) } }
    }

    companion object {
        fun identity(parts: List<String>): String =
            MessageDigest.getInstance("SHA-256")
                .digest(parts.joinToString("\u0000").toByteArray())
                .joinToString("") { "%02x".format(it) }

        fun timelineJson(value: DeckTimeline): JsonObject = buildJsonObject {
            put("schema", "agent-deck.recall.rows/v2")
            put("turns", JsonArray(value.rows.map { it.raw }))
            value.cursor?.let { put("through_cursor", it) }
            value.before?.let { put("before_cursor", it) }
            value.status?.let { put("status", it) }
        }
    }
}
