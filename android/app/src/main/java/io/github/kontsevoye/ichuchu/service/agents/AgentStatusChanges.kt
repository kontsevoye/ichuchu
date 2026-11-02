package io.github.kontsevoye.ichuchu.service.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class AgentStatusAlert(val session: DeckSession, val updateExisting: Boolean)

/** Initial/reconnected snapshots do not replay alerts; unknown samples cannot erase a baseline. */
class AgentStatusChanges {
    private val previous = mutableMapOf<String, AgentStatus>()
    private val polledAlerts = mutableSetOf<String>()
    private val seenTurns = linkedSetOf<String>()
    private var revision = 0L
    private val eventRevision = mutableMapOf<String, Long>()

    fun reset() {
        previous.clear()
        polledAlerts.clear()
        seenTurns.clear()
        eventRevision.clear()
    }

    fun startEventStream() = polledAlerts.clear()

    fun checkpoint(): Long = revision

    fun accept(sessions: List<DeckSession>, startedAt: Long = revision): List<DeckSession> {
        previous.keys.retainAll(sessions.map { it.id }.toSet())
        return sessions.filter { session ->
            if ((eventRevision[session.id] ?: 0L) > startedAt) return@filter false
            val next = session.status
            if (next == AgentStatus.Unknown) return@filter false
            val old = previous.put(session.id, next)
            val changed = old != null && !equivalent(old, next)
            if (next == AgentStatus.Running) polledAlerts.remove(session.id)
            if (changed && next in ALERTS) polledAlerts.add(session.id)
            changed
        }
    }

    /** A complete short turn can happen between two identical list snapshots. */
    fun transition(frame: JsonObject, sessions: List<DeckSession>): AgentStatusAlert? {
        if (frame.text("kind") != "session.transition") return null
        val data = frame["data"] as? JsonObject ?: return null
        val session = sessions.firstOrNull { it.id == frame.text("session_id") } ?: return null
        val destination = data.text("to_status")
        if (destination.isBlank()) return null
        val next =
            DeckSession(
                JsonObject(
                    session.raw +
                        mapOf(
                            "status" to JsonPrimitive(destination),
                            "substate" to JsonPrimitive(data.text("substate")),
                            "status_source" to JsonPrimitive("event"),
                        )
                )
            )
        if (next.status !in ALERTS) return null
        val fingerprint =
            data
                .text("turn_uuid")
                .ifBlank { data.text("turn_fingerprint") }
                .ifBlank {
                    data
                        .text("last_output_hash")
                        .takeUnless { data.flag("output_hash_stale") }
                        .orEmpty()
                }
                .ifBlank { data.text("timestamp") }
                .ifBlank { frame.text("cursor") }
        if (fingerprint.isBlank()) return null
        val key = "${session.id}:$fingerprint:$destination:${data.text("substate")}"
        if (!seenTurns.add(key)) return null
        while (seenTurns.size > 512) seenTurns.remove(seenTurns.first())
        eventRevision[session.id] = ++revision
        val alreadyPolled =
            polledAlerts.remove(session.id) &&
                previous[session.id]?.let { equivalent(it, next.status) } == true
        previous[session.id] = next.status
        // A poll has no turn ID. Refresh its existing card quietly, but never discard
        // a distinct turn: it may be a new quick reply or the prior card may be dismissed.
        return AgentStatusAlert(next, updateExisting = alreadyPolled)
    }

    private fun equivalent(first: AgentStatus, second: AgentStatus): Boolean =
        first == second || (first in INPUT && second in INPUT)

    companion object {
        private val INPUT = setOf(AgentStatus.Ready, AgentStatus.Waiting)
        private val ALERTS = INPUT + setOf(AgentStatus.Attention, AgentStatus.Error)
    }
}
