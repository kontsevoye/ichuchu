package io.github.kontsevoye.ichuchu.service.agents

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DeckCommandException(val code: String, message: String) : IllegalStateException(message)

/** CLI boundary. Message bodies only travel through stdin; no prompt is shell source. */
class DeckClient(
    val transport: DeckTransport,
    val profile: String,
    private val configuredExecutable: String,
) {
    lateinit var capabilities: DeckCapabilities
        private set

    private lateinit var executable: String
    var eventCursor: String? = null
        private set

    suspend fun probe(): DeckCapabilities {
        val discovery = transport.execute(DeckCommands.discover(configuredExecutable))
        requireSuccess(discovery)
        executable =
            discovery.stdout.trim().also {
                check(it.isNotEmpty() && it.none { c -> c == '\n' || c == '\r' || c == '\u0000' }) {
                    "Agent Deck executable not found. Set its full path in the server settings."
                }
            }
        val version = run("version").stdout.trim()
        val catalog = obj("launch", "--capabilities", "--json")
        check(catalog.number("version") == 1) {
            "Unsupported Agent Deck capabilities. Tested with v1.16.35."
        }
        val help = run("session", "send", "--help").stdout
        val fields =
            (catalog["commands"] as? JsonObject)
                ?.objects("launch")
                .orEmpty()
                .map { it.text("name") }
                .toSet()
        val recall = obj("config", "get", "recall.enabled", "--json").flag("value")
        val harnesses =
            deckJson.parseToJsonElement(run("harness", "list", "--json").stdout) as? JsonArray
                ?: error("Invalid Agent Deck harness list")
        val installed =
            harnesses
                .mapNotNull { it as? JsonObject }
                .filter { it.flag("installed") }
                .map { it.text("name") }
                .toSet()
        val events =
            optional {
                val stats = obj("events", "stats", "--read-only", "--json")
                eventCursor = stats.text("cursor").ifBlank { "0" }
                stats.flag("enabled")
            } ?: false
        capabilities =
            DeckCapabilities(
                executable,
                version,
                recall,
                listOf("require-input-prompt", "queue", "message-file", "no-tag", "json").all {
                    help.contains("-$it")
                },
                catalog
                    .objects("tools")
                    .filter {
                        it.text("name") in setOf("claude", "codex") && it.text("name") in installed
                    }
                    .map {
                        DeckTool(
                            it.text("name"),
                            it.strings("models"),
                            it.strings("reasoning_efforts"),
                            (it["model_efforts"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                                (value as? JsonArray)
                                    ?.mapNotNull { entry -> (entry as? JsonPrimitive)?.content }
                                    .orEmpty()
                            },
                        )
                    },
                (catalog["accounts"] as? JsonArray).orEmpty().mapNotNull { account ->
                    when (account) {
                        is JsonPrimitive -> account.content
                        is JsonObject -> account.text("name").ifBlank { null }
                        else -> null
                    }
                },
                fields,
                events,
            )
        return capabilities
    }

    suspend fun sessions(): List<DeckSession> {
        val result = run("list", "--json")
        val rows =
            deckJson.parseToJsonElement(result.stdout) as? JsonArray
                ?: error("Invalid Agent Deck session list")
        return rows
            .map { DeckSession(it as? JsonObject ?: error("Invalid session row")) }
            .filterNot { it.raw.flag("archived") }
    }

    suspend fun timeline(id: String, before: String? = null): DeckTimeline {
        val window =
            if (before == null) {
                listOf("--tail", "80")
            } else {
                listOf("--before", before, "--limit", "80")
            }
        return DeckTimeline.parse(
            obj(*(listOf("recall", "timeline", id, "--json") + window).toTypedArray())
        )
    }

    suspend fun follow(id: String, cursor: String?, onFrame: suspend (JsonObject) -> Unit) {
        val args =
            listOf("recall", "follow", id, "--jsonl", "--status") +
                (cursor?.let { listOf("--after", it) } ?: emptyList())
        transport.follow(DeckCommands.leasedFollow(command(args))) { onFrame(parseDeckObject(it)) }
    }

    suspend fun events(after: String?, onFrame: suspend (JsonObject) -> Unit) {
        val args =
            listOf("events", "follow", "--read-only", "--jsonl", "--kind", "session") +
                (after?.let { listOf("--after", it) } ?: emptyList())
        transport.follow(DeckCommands.leasedFollow(command(args))) { onFrame(parseDeckObject(it)) }
    }

    suspend fun enableRecall() {
        obj("config", "set", "recall.enabled", "true", "--json")
        capabilities =
            capabilities.copy(
                recallEnabled = obj("config", "get", "recall.enabled", "--json").flag("value")
            )
    }

    suspend fun send(id: String, message: String, onStarted: suspend () -> Unit): JsonObject {
        check(capabilities.guardedSend) {
            "This Agent Deck version cannot safely send chat messages"
        }
        require(message.isNotBlank() && message.toByteArray().size <= 128 * 1024) {
            "Message must contain text and fit within 128 KiB"
        }
        val result =
            transport.execute(
                command(
                    listOf(
                        "session",
                        "send",
                        id,
                        "--message-file",
                        "-",
                        "--json",
                        "--queue",
                        "--require-input-prompt",
                        "--no-tag",
                    )
                ),
                message.toByteArray(Charsets.UTF_8),
                onStarted = onStarted,
            )
        val receipt = optionalParse(result.stdout)
        // A nonzero exit may still carry the durable send ID and terminal delivery state.
        if (receipt != null && receipt.text("send_id").isNotBlank()) return receipt
        requireSuccess(result)
        error("Agent Deck returned no delivery receipt")
    }

    suspend fun sendStatus(id: String): JsonObject = obj("session", "send-status", id, "--json")

    suspend fun queue(id: String): List<JsonObject> =
        obj("session", "queue", "list", id, "--json").objects("queue")

    suspend fun cancelSend(id: String): JsonObject = obj("session", "queue", "cancel", id, "--json")

    suspend fun launch(
        project: DeckProject,
        tool: String,
        title: String,
        model: String,
        effort: String,
        account: String,
        onStarted: suspend () -> Unit,
    ): JsonObject {
        check("no-parent" in capabilities.launchFields) {
            "Agent Deck does not support independent session creation"
        }
        require(project.path.startsWith('/') && tool in capabilities.tools.map { it.name })
        val args = mutableListOf("launch", project.path, "-c", tool, "--no-parent", "--json")
        listOf(
                "title" to title,
                "group" to project.group,
                "model" to model,
                "effort" to effort,
                "account" to account,
            )
            .forEach { (key, value) ->
                if (value.isNotBlank()) {
                    check(key in capabilities.launchFields) { "Agent Deck does not support $key" }
                    args += listOf("--$key", value)
                }
            }
        val result = transport.execute(command(args), timeoutMs = 60_000, onStarted = onStarted)
        val receipt = optionalParse(result.stdout)
        // Keep partial/queued launches with an exact identity; never retry a lost launch.
        if (
            receipt != null &&
                (receipt.text("session_id").isNotBlank() || receipt.text("id").isNotBlank())
        ) {
            return receipt
        }
        requireSuccess(result)
        return receipt ?: error("Agent Deck returned no creation receipt")
    }

    fun attach(id: String): String = DeckCommands.attach(executable, profile, id)

    private fun command(args: List<String>): String =
        DeckCommands.command(executable, profile, *args.toTypedArray())

    private suspend fun run(vararg args: String): DeckExecResult =
        transport.execute(command(args.toList())).also(::requireSuccess)

    private suspend fun obj(vararg args: String): JsonObject = parseDeckObject(run(*args).stdout)

    private fun requireSuccess(result: DeckExecResult) {
        val objectValue = optionalParse(result.stdout)
        if (result.exitCode == 0 && objectValue?.get("success") != JsonPrimitive(false)) return
        val message = objectValue?.text("error")?.ifBlank { objectValue.text("message") }.orEmpty()
        throw DeckCommandException(
            objectValue?.text("code").orEmpty(),
            message.ifBlank {
                result.stderr
                    .ifBlank { result.stdout }
                    .trim()
                    .take(2000)
                    .ifBlank { "Agent Deck exited (${result.exitCode})" }
            },
        )
    }

    private fun optionalParse(text: String): JsonObject? =
        runCatching { parseDeckObject(text) }.getOrNull()

    private suspend fun <T> optional(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}
