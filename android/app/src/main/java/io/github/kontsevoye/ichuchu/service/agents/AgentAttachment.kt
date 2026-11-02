package io.github.kontsevoye.ichuchu.service.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val AGENT_ATTACHMENT_LIMIT = 20L * 1024 * 1024
const val AGENT_ATTACHMENT_COUNT = 4

data class AgentAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val remotePath: String,
) {
    fun json(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("mimeType", mimeType)
        put("size", size)
        put("remotePath", remotePath)
    }

    companion object {
        fun parse(value: JsonObject) =
            AgentAttachment(
                value.text("id"),
                value.text("name"),
                value.text("mimeType"),
                value.text("size").toLongOrNull() ?: 0,
                value.text("remotePath"),
            )
    }
}

/** A running Codex cannot receive Deck's --image. Both harnesses can read host-local files. */
fun agentAttachmentMessage(text: String, attachments: List<AgentAttachment>): String {
    if (attachments.isEmpty()) return text
    return buildString {
        append(text.ifBlank { "Please inspect the attached files." })
        append("\n\nAttached files on this host:\n")
        for (file in attachments) {
            require(
                file.remotePath.startsWith('/') &&
                    '\n' !in file.remotePath &&
                    '\r' !in file.remotePath
            )
            append("- ")
                .append(JsonPrimitive(file.name))
                .append(" (")
                .append(file.mimeType)
                .append(", ")
                .append(file.size)
                .append(" bytes):\n")
            append(file.remotePath).append('\n')
        }
        append("Read these files as part of this request. For images, use your image-viewing tool.")
    }
}
