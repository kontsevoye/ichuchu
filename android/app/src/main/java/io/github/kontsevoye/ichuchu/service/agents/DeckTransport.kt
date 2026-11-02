package io.github.kontsevoye.ichuchu.service.agents

import io.github.kontsevoye.ichuchu.service.ssh.NativeSshService
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class DeckExecResult(val stdout: String, val stderr: String, val exitCode: Int)

class DeckTransportException(e: Exception) : IOException(e.message ?: "SSH connection failed", e)

interface DeckTransport {
    suspend fun execute(
        command: String,
        stdin: ByteArray? = null,
        timeoutMs: Long = 20_000,
        onStarted: suspend () -> Unit = {},
    ): DeckExecResult

    suspend fun follow(command: String, onFrame: suspend (String) -> Unit)

    suspend fun upload(
        command: String,
        input: InputStream,
        size: Long,
        onProgress: (Long) -> Unit,
    ): DeckExecResult = error("File upload is unavailable")

    fun interrupt()
}

/** Bounded no-PTY command lane plus independent stream connections, with ordinary route trust. */
class SshDeckTransport(private val connect: suspend () -> NativeSshService) : DeckTransport {
    private val commandLock = Mutex()
    private val connections = Collections.synchronizedSet(mutableSetOf<NativeSshService>())
    @Volatile private var interrupted = false

    override fun interrupt() {
        interrupted = true
        synchronized(connections) { connections.forEach { it.interrupt() } }
    }

    override suspend fun execute(
        command: String,
        stdin: ByteArray?,
        timeoutMs: Long,
        onStarted: suspend () -> Unit,
    ): DeckExecResult =
        commandLock.withLock {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val output = ByteArrayOutputStream()
                    val errors = ByteArrayOutputStream()
                    process(
                            command,
                            stdin?.inputStream(),
                            onStarted,
                            { appendBounded(output, it, 16 * 1024 * 1024) },
                            { appendBounded(errors, it, 64 * 1024) },
                        )
                        .let { code ->
                            DeckExecResult(output.toString("UTF-8"), errors.toString("UTF-8"), code)
                        }
                }
            }
        }

    override suspend fun upload(
        command: String,
        input: InputStream,
        size: Long,
        onProgress: (Long) -> Unit,
    ): DeckExecResult =
        withTimeout(180_000) {
            require(size in 0..AGENT_ATTACHMENT_LIMIT) { "Files must be at most 20 MiB" }
            // A dedicated connection keeps status polling responsive during a large transfer.
            withContext(Dispatchers.IO) {
                val output = ByteArrayOutputStream()
                val errors = ByteArrayOutputStream()
                val code =
                    process(
                        command,
                        input,
                        {},
                        { appendBounded(output, it, 16 * 1024) },
                        { appendBounded(errors, it, 64 * 1024) },
                        size,
                        onProgress,
                    )
                DeckExecResult(output.toString("UTF-8"), errors.toString("UTF-8"), code)
            }
        }

    override suspend fun follow(command: String, onFrame: suspend (String) -> Unit) {
        withContext(Dispatchers.IO) {
            val decoder = DeckLineDecoder()
            val errors = ByteArrayOutputStream()
            val code =
                process(
                    command,
                    null,
                    {},
                    { bytes -> decoder.feed(bytes).forEach { onFrame(it) } },
                    { appendBounded(errors, it, 64 * 1024) },
                )
            decoder.finish()?.let { onFrame(it) }
            check(code == 0) {
                errors.toString("UTF-8").take(2000).ifBlank { "Agent Deck stream exited ($code)" }
            }
        }
    }

    private suspend fun process(
        command: String,
        stdin: InputStream?,
        onStarted: suspend () -> Unit,
        stdout: suspend (ByteArray) -> Unit,
        stderr: suspend (ByteArray) -> Unit,
        inputLimit: Long = 128 * 1024,
        onProgress: (Long) -> Unit = {},
    ): Int {
        if (interrupted) throw CancellationException("Connection closed")
        val service = connect()
        connections.add(service)
        try {
            if (interrupted) throw CancellationException("Connection closed")
            // Persist mutation intent before exec: any interruption after this point is uncertain.
            onStarted()
            currentCoroutineContext().ensureActive()
            sshCall { check(service.openExec(command)) { "SSH command could not be started" } }
            if (stdin != null) {
                var offset = 0L
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stdin.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(offset + count <= inputLimit) { "Upload exceeds its expected size" }
                    sshCall { service.write(buffer.copyOf(count)) }
                    stdout(sshCall { service.readStream(0) })
                    stderr(sshCall { service.readStream(1) })
                    offset += count
                    onProgress(offset)
                }
            }
            sshCall { service.sendEof() }
            while (true) {
                currentCoroutineContext().ensureActive()
                val out = sshCall { service.readStream(0) }
                val err = sshCall { service.readStream(1) }
                stdout(out)
                stderr(err)
                if (out.isEmpty() && err.isEmpty()) {
                    if (service.isChannelEof()) return sshCall { service.finishExec() }
                    delay(20)
                }
            }
        } catch (e: Exception) {
            if (interrupted) throw CancellationException("Connection closed", e)
            throw e
        } finally {
            connections.remove(service)
            service.close()
        }
    }

    private fun appendBounded(target: ByteArrayOutputStream, bytes: ByteArray, limit: Int) {
        check(target.size() + bytes.size <= limit) {
            "Agent Deck output exceeds the supported size"
        }
        target.write(bytes)
    }

    private inline fun <T> sshCall(block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DeckTransportException(e)
        }
}

/** NDJSON framing happens on bytes, so an SSH chunk may split any UTF-8 character. */
class DeckLineDecoder(private val limit: Int = 4 * 1024 * 1024) {
    private val pending = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): List<String> = buildList {
        for (byte in bytes) {
            if (byte == 10.toByte()) {
                finish()?.let(::add)
            } else {
                check(pending.size() < limit) { "Agent Deck frame exceeds the supported size" }
                pending.write(byte.toInt())
            }
        }
    }

    fun finish(): String? {
        if (pending.size() == 0) return null
        val bytes = pending.toByteArray()
        pending.reset()
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .trimEnd('\r')
            .takeIf { it.isNotBlank() }
    }
}
