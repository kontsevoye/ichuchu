package io.github.kontsevoye.ichuchu.ui.screens.agents

internal data class MemorySource(val location: String, val note: String)

internal data class AgentMarkdownPart(
    val text: String = "",
    val sources: List<MemorySource>? = null,
    val receiving: Boolean = false,
)

/** Only standalone assistant annotations are metadata. Quoted examples and code stay literal. */
internal fun agentMarkdownParts(text: String): List<AgentMarkdownPart> {
    val result = mutableListOf<AgentMarkdownPart>()
    val plain = StringBuilder()
    val lines = text.splitToSequence('\n').toList()
    var fence: Char? = null
    var fenceSize = 0
    var inlineTicks = 0
    var index = 0
    fun flush() {
        if (plain.isNotEmpty()) result += AgentMarkdownPart(text = plain.toString())
        plain.clear()
    }
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        val marker = trimmed.firstOrNull()
        val size = trimmed.takeWhile { it == marker }.length
        if (marker in listOf('`', '~') && size >= 3 && line.length - trimmed.length <= 3) {
            if (fence == null) {
                fence = marker
                fenceSize = size
            } else if (fence == marker && size >= fenceSize) {
                fence = null
            }
        }
        if (
            fence == null &&
                inlineTicks == 0 &&
                line.trim() == "<oai-mem-citation>" &&
                !line.startsWith("    ") &&
                !line.startsWith('\t')
        ) {
            val scanLimit = minOf(lines.size, index + 258)
            val end =
                (index + 1 until scanLimit).firstOrNull {
                    lines[it].trim() == "</oai-mem-citation>"
                }
            val limit = end ?: lines.lastIndex
            val block = lines.subList(index + 1, end ?: scanLimit)
            val parsed =
                if (end == null && scanLimit < lines.size) {
                    null
                } else {
                    parseMemorySources(block, complete = end != null)
                }
            if (parsed != null) {
                flush()
                result += AgentMarkdownPart(sources = parsed, receiving = end == null)
                index = limit + 1
                continue
            }
        }
        if (fence == null && !(marker == '`' && size >= 3)) {
            for (run in Regex("`+").findAll(line)) {
                val escapes = line.take(run.range.first).takeLastWhile { it == '\\' }.length
                if (escapes % 2 == 0) {
                    if (inlineTicks == 0) {
                        inlineTicks = run.value.length
                    } else if (inlineTicks == run.value.length) {
                        inlineTicks = 0
                    }
                }
            }
        }
        plain.append(line)
        if (index < lines.lastIndex) plain.append('\n')
        index++
    }
    flush()
    return result
}

private fun parseMemorySources(lines: List<String>, complete: Boolean): List<MemorySource>? {
    if (lines.size > 256 || lines.sumOf { it.length } > 32_768) return null
    val source = Regex("(.+):(\\d+)(?:-(\\d+))?\\|note=\\[(.*)]")
    val uuid = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    var section = 0
    val sources = mutableListOf<MemorySource>()
    for ((index, raw) in lines.withIndex()) {
        val line = raw.trim()
        if (line.isBlank()) continue
        when {
            section == 0 && line == "<citation_entries>" -> section = 1
            section == 1 && line == "</citation_entries>" -> section = 2
            section == 2 && line == "<rollout_ids>" -> section = 3
            section == 3 && line == "</rollout_ids>" -> section = 4
            section == 1 && source.matches(line) -> {
                val match = source.matchEntire(line)!!
                val (file, start, end, note) = match.destructured
                if (file.any { it == '<' || it == '>' }) return null
                sources += MemorySource("$file:$start${if (end.isEmpty()) "" else "–$end"}", note)
            }
            section == 3 && uuid.matches(line) -> Unit
            // A streaming annotation may end in a partial tag/source, never consume later prose.
            !complete &&
                index == lines.lastIndex &&
                (line.startsWith("<") ||
                    (section == 1 && ":" in line) ||
                    (section == 3 && line.matches(Regex("[0-9a-fA-F-]+")))) -> Unit
            else -> return null
        }
    }
    return if ((!complete && section >= 1) || (complete && section in setOf(2, 4))) {
        sources
    } else {
        null
    }
}
