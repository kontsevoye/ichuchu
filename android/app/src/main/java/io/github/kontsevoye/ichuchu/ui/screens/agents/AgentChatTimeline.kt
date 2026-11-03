package io.github.kontsevoye.ichuchu.ui.screens.agents

import io.github.kontsevoye.ichuchu.service.agents.AgentChatState
import io.github.kontsevoye.ichuchu.service.agents.DeckRow
import io.github.kontsevoye.ichuchu.service.agents.DeckSend
import io.github.kontsevoye.ichuchu.service.agents.text
import io.github.kontsevoye.ichuchu.service.agents.uniqueDeckSends
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

data class ChatBlock(val rows: List<DeckRow> = emptyList(), val send: DeckSend? = null) {
    val key: String
        get() = send?.let { "send:${it.localId}" } ?: "row:${rows.first().id}"
}

/** Preserve native source order; place local receipts at their original send boundary. */
fun chatBlocks(chat: AgentChatState): List<ChatBlock> {
    val rows = chat.timeline.rows
    val indices = rows.mapIndexed { index, row -> row.id to index }.toMap()
    val pending =
        uniqueDeckSends(chat.sends).filterNot {
            it.landedRowId.isNotBlank() && it.landedRowId in indices
        }
    val rowTimes by lazy { rows.map { parseTime(it.timestamp) } }
    val positions =
        pending.groupBy { send ->
            val anchor = send.anchorRowId?.let(indices::get)
            if (anchor != null) {
                anchor + 1
            } else {
                // Old cached receipts have server timestamps but no saved row anchor. Never sort
                // native rows or match repeated message text to guess delivery.
                val time =
                    listOf("created_at", "sent_at", "landed_at").firstNotNullOfOrNull {
                        parseTime(send.receipt.text(it))
                    } ?: send.createdAt.takeIf { it > 0 }
                if (time == null) {
                    rows.size
                } else {
                    rowTimes.indexOfFirst { it != null && it > time }.takeIf { it >= 0 }
                        ?: rows.size
                }
            }
        }
    val blocks = mutableListOf<ChatBlock>()
    val actions = mutableListOf<DeckRow>()
    fun flush() {
        if (actions.isNotEmpty()) {
            blocks += ChatBlock(actions.toList())
            actions.clear()
        }
    }
    for (index in 0..rows.size) {
        positions[index]?.let { sends ->
            flush()
            sends.forEach { blocks += ChatBlock(send = it) }
        }
        val row = rows.getOrNull(index) ?: break
        if (row.isMessage) {
            flush()
            blocks += ChatBlock(listOf(row))
        } else {
            actions += row
            if (actions.size == 20) flush()
        }
    }
    flush()
    return blocks
}

private val timestampPattern =
    Regex("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})")
private val timestampFormat =
    object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { isLenient = false }
    }

// java.time requires API 26; this application also supports Android 7 (API 24).
private fun parseTime(value: String): Long? {
    val match = timestampPattern.matchEntire(value) ?: return null
    val normalized =
        "${match.groupValues[1]}.${match.groupValues[2].padEnd(3, '0').take(3)}${match.groupValues[3]}"
    val position = ParsePosition(0)
    val date = timestampFormat.get()?.parse(normalized, position)
    return date?.time?.takeIf { position.index == normalized.length }
}
