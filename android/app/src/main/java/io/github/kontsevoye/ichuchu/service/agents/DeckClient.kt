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
                listOf("codex-composer-fallback", "no-wait", "timeout").all {
                    help.contains("-$it")
                },
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

    /** Explicit actions target one current local identity; they are never retried. */
    suspend fun sessionAction(session: DeckSession, action: DeckSessionAction): JsonObject {
        require(session.local) { "Manage this session on its owning host" }
        require(session.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) {
            "Invalid session identity"
        }
        val current = obj("session", "show", session.id, "--json")
        check(current.text("id") == session.id && current.text("ssh_host").isBlank()) {
            "Session ownership changed. Refresh the list."
        }
        val args =
            when (action) {
                DeckSessionAction.Restart ->
                    listOf("session", "restart", session.id, "--force", "--json")
                DeckSessionAction.Stop -> listOf("session", "stop", session.id, "--json")
                DeckSessionAction.Delete ->
                    listOf("session", "remove", session.id, "--force", "--json")
            }
        val result = transport.execute(command(args), timeoutMs = 60_000)
        requireSuccess(result)
        return parseDeckObject(result.stdout).also {
            check(it.flag("success") && it.text("id") == session.id && !it.flag("skipped")) {
                it.text("reason").ifBlank {
                    "Action was not confirmed. Refresh the list before trying again."
                }
            }
        }
    }

    /** Control commands never enter the message queue or its accepted-turn ledger. */
    suspend fun modelSettings(
        id: String,
        model: String = "",
        effort: String = "",
    ): DeckModelOptions {
        val script =
            checkNotNull(javaClass.getResourceAsStream("/agent_model_control.py")) {
                    "Model controls are missing from this build"
                }
                .bufferedReader()
                .use { it.readText() }
        val command =
            "exec python3 -c " +
                (listOf(script, executable, profile, id, model, effort)).joinToString(
                    " ",
                    transform = DeckCommands::quote,
                )
        val result = transport.execute(command, timeoutMs = 35_000)
        requireSuccess(result)
        val value = parseDeckObject(result.stdout)
        if (model.isNotEmpty() || effort.isNotEmpty()) {
            check(value.flag("confirmed")) {
                "Model change is unconfirmed. Check the terminal before trying again."
            }
        }
        return DeckModelOptions(
            DeckModelSettings(value.text("model"), value.text("effort")),
            (value["models"] as? JsonObject).orEmpty().mapValues { (_, levels) ->
                (levels as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            },
        )
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

    suspend fun projects(): List<DeckProject> {
        val groups = deckGroupPaths(obj("group", "list", "--json"))
        // One SSH exec per bounded batch. RS cannot occur unescaped inside valid JSON.
        return groups.chunked(50).flatMap { batch ->
            val script =
                batch.joinToString("\n") {
                    "(${command(listOf("group", "show", it, "--json"))})\nprintf '\\036'"
                }
            val result =
                transport.execute("exec sh -c " + DeckCommands.quote(script), timeoutMs = 60_000)
            requireSuccess(result)
            val records = result.stdout.split('\u001e')
            batch.mapIndexed { index, group ->
                val detail = records.getOrNull(index)?.let(::optionalParse)
                DeckProject(
                    detail?.text("default_path").orEmpty().takeIf { it.startsWith('/') }.orEmpty(),
                    group,
                )
            }
        }
    }

    suspend fun nativeHistories(session: DeckSession): List<DeckNativeHistory> {
        require(session.local && session.tool == "codex")
        return obj(
                "recall",
                "sessions",
                "--harness",
                "codex",
                "--project",
                session.path,
                "--limit",
                "100",
                "--json",
            )
            .objects("sessions")
            .map(::DeckNativeHistory)
            .filter { it.id.isNotBlank() && it.tool == session.tool && it.path == session.path }
            .distinctBy { it.id }
            .sortedByDescending { it.startedAt }
    }

    /** Explicit user selection only. A project directory is not a conversation identity. */
    suspend fun linkHistory(session: DeckSession, history: DeckNativeHistory) {
        require(
            session.local &&
                session.tool == "codex" &&
                history.tool == session.tool &&
                history.path == session.path
        )
        timeline(
            history.id
        ) // Verify the exact native history is readable before writing the binding.
        obj("session", "set", session.id, "codex-session-id", history.id, "--json")
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

    suspend fun currentEventCursor(): String =
        obj("events", "stats", "--read-only", "--json").text("cursor").ifBlank { "0" }

    suspend fun enableRecall() {
        obj("config", "set", "recall.enabled", "true", "--json")
        capabilities =
            capabilities.copy(
                recallEnabled = obj("config", "get", "recall.enabled", "--json").flag("value")
            )
    }

    suspend fun send(
        session: DeckSession,
        message: String,
        onStarted: suspend () -> Unit,
    ): JsonObject {
        if (session.tool != "codex" || !capabilities.codexComposerSend) {
            return send(session.id, message, onStarted)
        }
        check(capabilities.guardedSend) {
            "This Agent Deck version cannot safely send chat messages"
        }
        require(message.isNotBlank() && message.toByteArray().size <= 128 * 1024) {
            "Message must contain text and fit within 128 KiB"
        }
        return nativeControl("--send", session.id, message.toByteArray(Charsets.UTF_8), onStarted)
    }

    suspend fun contextRemaining(id: String): Int? = sessionContext(id).remaining

    suspend fun sessionContext(id: String): DeckSessionContext {
        val value = nativeControl("--context", id)
        return DeckSessionContext(
            value.number("remaining")?.takeIf { it in 0..100 },
            value
                .text("model")
                .takeIf { it.isNotBlank() }
                ?.let { DeckModelSettings(it, value.text("effort")) },
        )
    }

    suspend fun nativeSendStatus(id: String, receipt: JsonObject): JsonObject =
        nativeControl("--receipt", id, receipt.toString().toByteArray(Charsets.UTF_8))

    private suspend fun nativeControl(
        operation: String,
        id: String,
        stdin: ByteArray? = null,
        onStarted: suspend () -> Unit = {},
    ): JsonObject {
        val script =
            checkNotNull(javaClass.getResourceAsStream("/agent_model_control.py"))
                .bufferedReader()
                .use { it.readText() }
        val command =
            "exec python3 -c " +
                listOf(script, operation, executable, profile, id)
                    .joinToString(" ", transform = DeckCommands::quote)
        val result = transport.execute(command, stdin, timeoutMs = 45_000, onStarted = onStarted)
        val receipt = optionalParse(result.stdout)
        // A refusal before typing is safe to report; a lost response remains unknown and is never
        // retried.
        if (
            operation != "--context" &&
                receipt?.text("state") in setOf("failed", "typed", "submitted", "landed", "unknown")
        ) {
            val value = checkNotNull(receipt)
            val valid =
                when (value.text("state")) {
                    "landed" -> value.text("landed_row_id").isNotBlank()
                    "submitted" ->
                        value.flag("submitted") && value.text("confirmation") == "confirmed"
                    else -> true
                }
            return if (valid) {
                value
            } else {
                JsonObject(
                    value +
                        mapOf(
                            "state" to JsonPrimitive("unknown"),
                            "settled" to JsonPrimitive(true),
                            "reason" to
                                JsonPrimitive(
                                    "Native delivery was not confirmed. Check history before resending."
                                ),
                        )
                )
            }
        }
        requireSuccess(result)
        check(operation == "--context") {
            "Native delivery result is unrecognized. Check history before sending again."
        }
        return receipt ?: error("Remote session control returned no result")
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

    suspend fun uploadFile(name: String, file: java.io.File, onProgress: (Long) -> Unit): String {
        val safe =
            "file-" +
                name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).trimStart('.', '-').ifBlank {
                    "file"
                }
        val result =
            file.inputStream().use {
                transport.upload(
                    DeckCommands.uploadFile(safe, file.length()),
                    it,
                    file.length(),
                    onProgress,
                )
            }
        requireSuccess(result)
        return result.stdout.trim().also {
            require(it.startsWith('/') && '\n' !in it && '\r' !in it && '\u0000' !in it) {
                "Host returned an invalid uploaded-file path"
            }
        }
    }

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
