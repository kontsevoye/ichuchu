package io.github.kontsevoye.ichuchu.service.agents

import android.app.Application
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.data.repository.SettingsRepository
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.service.ssh.HostKeyCheck
import io.github.kontsevoye.ichuchu.service.ssh.HostKeyStore
import io.github.kontsevoye.ichuchu.service.ssh.NativeSshService
import io.github.kontsevoye.ichuchu.service.ssh.SshHostKeyRequest
import io.github.kontsevoye.ichuchu.service.ssh.SshJumpHost
import io.github.kontsevoye.ichuchu.service.ssh.SshRouteSnapshot
import io.github.kontsevoye.ichuchu.service.terminal.HostKeyPrompt
import io.github.kontsevoye.ichuchu.service.terminal.TabSpec
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Same credentials, hops and trust store as terminals; no shell or tmux is opened for chat. */
class AgentConnection(
    private val application: Application,
    private val db: AppDatabase,
    private val hostId: Long,
) {
    lateinit var host: HostProfile
        private set

    lateinit var spec: TabSpec
        private set

    lateinit var baseIdentity: String
        private set

    val hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    private val trustStore =
        HostKeyStore(
            application.getSharedPreferences(HostKeyStore.PREFS_NAME, Application.MODE_PRIVATE)
        )
    private val active = Collections.synchronizedSet(mutableSetOf<NativeSshService>())
    private val keys = linkedMapOf<String, String>()
    private val trustLock = Any()
    @Volatile private var decision: CompletableDeferred<Boolean>? = null
    @Volatile private var closed = false
    @Volatile private var sealed = false

    val requiresVerification: Boolean
        get() =
            SettingsRepository.getInstance(application).requireAuthOnConnect.value ||
                spec.requiresRouteVerification

    val identity: String
        get() =
            synchronized(trustLock) {
                AgentStateStore.identity(
                    listOf(baseIdentity) + keys.toSortedMap().map { "${it.key}=${it.value}" }
                )
            }

    suspend fun prepare() {
        host = db.hostProfileDao().getById(hostId) ?: error("Server was deleted")
        check(host.transport != Transport.LocalShell) { "Agent mode requires SSH" }
        val allKeys = db.sshKeyDao().getAll()
        val hops = SshJumpHost.resolve(host, db.hostProfileDao().getAll(), allKeys)
        val key = allKeys.firstOrNull { it.id == host.keyId }
        spec =
            TabSpec.fromHostProfile(
                    host,
                    key?.publicKeyOpenSsh.orEmpty(),
                    key?.privateKeyPem.orEmpty(),
                )
                .copy(
                    postConnectCommand = null,
                    multiplexer = null,
                    jumpHosts = hops,
                    requiresRouteVerification =
                        host.requireAuthOnConnect || hops.any { it.profile.requireAuthOnConnect },
                    routeSnapshot =
                        SshRouteSnapshot(
                            db,
                            host,
                            hops,
                            allKeys.filter {
                                it.id == host.keyId ||
                                    hops.any { hop -> hop.profile.keyId == it.id }
                            },
                        ),
                )
        baseIdentity =
            AgentStateStore.identity(
                listOf(hostId.toString(), host.deckProfile, host.deckExecutable) +
                    (hops.map { it.profile } + host).flatMap {
                        listOf(it.host, it.port.toString(), it.username, it.keyId.toString())
                    }
            )
    }

    fun sealTrust() {
        sealed = true
    }

    fun respond(accepted: Boolean) {
        decision?.complete(accepted)
    }

    fun close() {
        closed = true
        decision?.cancel()
        hostKeyPrompt.value = null
        synchronized(active) { active.forEach { it.interrupt() } }
    }

    suspend fun connect(): NativeSshService =
        withContext(Dispatchers.IO) {
            check(!closed) { "Connection closed" }
            spec.routeSnapshot?.requireCurrent()
            val context = currentCoroutineContext()
            val service = NativeSshService(hostKeyPolicy = ::verify)
            active.add(service)
            try {
                service.connect(
                    host = spec.host,
                    port = spec.port,
                    username = spec.username,
                    authMethod = spec.authMethod,
                    password = spec.password,
                    publicKeyOpenSsh = spec.publicKeyOpenSsh,
                    privateKeyPem = spec.privateKeyPem,
                    keyPassphrase = spec.keyPassphrase,
                    jumpHosts = spec.jumpHosts,
                    isCancelled = { closed || !context.isActive },
                )
                if (closed || !context.isActive) throw CancellationException("Connection closed")
                service
            } catch (e: Throwable) {
                service.close()
                throw e
            } finally {
                active.remove(service)
            }
        }

    private fun verify(request: SshHostKeyRequest): Boolean =
        synchronized(trustLock) {
            if (closed) throw CancellationException("Connection closed")
            val keyIdentity = "${request.trustHost}:${request.port}:${request.algorithm}"
            val digest = AgentStateStore.identity(listOf(request.keyBytes.joinToString(",")))
            if (sealed) {
                check(keys[keyIdentity] == digest) {
                    "SSH host key changed. Reconnect to verify the server."
                }
                return@synchronized true
            }
            val check =
                trustStore.check(
                    request.trustHost,
                    request.port,
                    request.algorithm,
                    request.keyBytes,
                )
            if (check != HostKeyCheck.Match) {
                val pending = CompletableDeferred<Boolean>()
                decision = pending
                hostKeyPrompt.value =
                    HostKeyPrompt(
                        request.routeLabel,
                        request.host,
                        request.port,
                        request.algorithm,
                        when (check) {
                            is HostKeyCheck.Unknown -> check.fingerprint
                            is HostKeyCheck.Changed -> check.fingerprint
                            else -> error("Invalid key state")
                        },
                        (check as? HostKeyCheck.Changed)?.previousFingerprint,
                    )
                val accepted =
                    try {
                        runBlocking { pending.await() }
                    } finally {
                        decision = null
                        hostKeyPrompt.value = null
                    }
                if (!accepted) return@synchronized false
                trustStore.saveKey(
                    request.trustHost,
                    request.port,
                    request.algorithm,
                    request.keyBytes,
                )
            }
            keys[keyIdentity] = digest
            true
        }
}
