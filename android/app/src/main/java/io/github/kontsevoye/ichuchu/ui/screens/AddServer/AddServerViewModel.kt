package io.github.kontsevoye.ichuchu.ui.screens.AddServer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.data.repository.HostRepository
import io.github.kontsevoye.ichuchu.data.repository.KeyGenerationResult
import io.github.kontsevoye.ichuchu.data.repository.SettingsRepository
import io.github.kontsevoye.ichuchu.data.repository.SshKeyRepository
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.MultiplexerType
import io.github.kontsevoye.ichuchu.model.SshKey
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.service.ssh.HostKeyCheck
import io.github.kontsevoye.ichuchu.service.ssh.HostKeyPolicy
import io.github.kontsevoye.ichuchu.service.ssh.HostKeyStore
import io.github.kontsevoye.ichuchu.service.ssh.NativeSshService
import io.github.kontsevoye.ichuchu.service.ssh.SshHostKeyRequest
import io.github.kontsevoye.ichuchu.service.ssh.SshJumpHost
import io.github.kontsevoye.ichuchu.service.terminal.HostKeyPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class AddServerViewModel(application: Application, private val hostId: Long?) : AndroidViewModel(application) {
    companion object {
        fun factory(application: Application, hostId: Long?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AddServerViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AddServerViewModel(application, hostId) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }

    private val db = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(db.hostProfileDao())
    private val sshKeyRepository = SshKeyRepository(db.sshKeyDao())
    private val hostKeyStore =
        HostKeyStore(
            application.applicationContext.getSharedPreferences(
                HostKeyStore.PREFS_NAME,
                Application.MODE_PRIVATE,
            )
        )

    private val _form = MutableStateFlow(AddServerForm())
    val form: StateFlow<AddServerForm> = _form.asStateFlow()

    private val _testState = MutableStateFlow(ConnectionTestState())
    val testState: StateFlow<ConnectionTestState> = _testState.asStateFlow()

    private val _hosts = MutableStateFlow<List<HostProfile>>(emptyList())
    val hosts: StateFlow<List<HostProfile>> = _hosts.asStateFlow()
    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()
    private val testLock = Any()
    @Volatile private var testGeneration = 0L
    private var hostKeyDecision: CompletableDeferred<Boolean>? = null
    private var testJob: Job? = null
    @Volatile private var testSsh: NativeSshService? = null
    val requiresTestVerification: Boolean
        get() =
            SettingsRepository.getInstance(getApplication()).requireAuthOnConnect.value ||
                _form.value.requireAuthOnConnect ||
                _hosts.value.any { it.id in _form.value.jumpHostIds && it.requireAuthOnConnect }

    fun respondToHostKey(accepted: Boolean) =
        synchronized(testLock) { hostKeyDecision?.complete(accepted) }

    fun cancelTest() =
        synchronized(testLock) {
            testGeneration += 1
            testSsh?.interrupt()
            hostKeyDecision?.cancel()
            testJob?.cancel()
            _hostKeyPrompt.value = null
            _testState.value = ConnectionTestState()
        }

    override fun onCleared() {
        cancelTest()
        super.onCleared()
    }

    private val _allKeys = MutableStateFlow<List<SshKey>>(emptyList())
    val keys: StateFlow<List<SshKey>> = _allKeys.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    init {
        viewModelScope.launch { hostRepository.observeAll().collect { _hosts.value = it } }
        viewModelScope.launch { sshKeyRepository.observeAll().collect { _allKeys.value = it } }
        if (hostId != null) {
            viewModelScope.launch {
                val profile = hostRepository.getById(hostId) ?: return@launch
                val key = profile.keyId?.let { sshKeyRepository.getById(it) }
                _form.value =
                    AddServerForm(
                        id = profile.id,
                        jumpHostIds = profile.jumpHostIds,
                        name = profile.name,
                        host = profile.host,
                        port = profile.port.toString(),
                        username = profile.username,
                        password = profile.password,
                        keyId = profile.keyId,
                        privateKeyPem = key?.privateKeyPem ?: "",
                        publicKeyOpenSsh = key?.publicKeyOpenSsh ?: "",
                        keyPassphrase = profile.keyPassphrase,
                        transport = profile.transport,
                        authMethod = profile.authMethod,
                        requireAuthOnConnect = profile.requireAuthOnConnect,
                        postConnectCommand = profile.postConnectCommand.orEmpty(),
                        multiplexer = profile.multiplexer,
                    )
            }
        }
    }

    fun updateName(name: String) {
        _form.value = _form.value.copy(name = name)
    }

    fun updateHost(host: String) {
        _form.value = _form.value.copy(host = host.filterNot { it.isWhitespace() })
    }

    fun updatePort(port: String) {
        _form.value = _form.value.copy(port = port)
    }

    fun updateUsername(username: String) {
        _form.value = _form.value.copy(username = username)
    }

    fun updatePassword(password: String) {
        _form.value = _form.value.copy(password = password)
    }

    fun updateKeyPassphrase(passphrase: String) {
        _form.value = _form.value.copy(keyPassphrase = passphrase)
    }

    fun selectStoredKey(keyId: Long?) {
        viewModelScope.launch {
            val selected =
                keyId?.let { id ->
                    keys.value.firstOrNull { it.id == id } ?: sshKeyRepository.getById(id)
                }
            _form.value =
                _form.value.copy(
                    keyId = keyId,
                    privateKeyPem = selected?.privateKeyPem ?: "",
                    publicKeyOpenSsh = selected?.publicKeyOpenSsh ?: "",
                    keyPassphrase = "",
                )
        }
    }

    fun generateKey(nameHint: String = "") {
        viewModelScope.launch {
            val current = _form.value
            val baseName = if (nameHint.isNotBlank()) nameHint else current.name
            when (val result = sshKeyRepository.generate(baseName, current.keyPassphrase)) {
                is KeyGenerationResult.Success ->
                    _form.value =
                        _form.value.copy(
                            keyId = result.key.id,
                            privateKeyPem = result.key.privateKeyPem,
                            publicKeyOpenSsh = result.key.publicKeyOpenSsh,
                        )
                KeyGenerationResult.Failed -> _errorMessage.emit("Key generation failed")
            }
        }
    }

    fun updateTransport(transport: Transport) {
        val current = _form.value
        val nextAuthMethod =
            when {
                transport == Transport.SSH && current.authMethod == AuthMethod.None ->
                    AuthMethod.Password
                else -> current.authMethod
            }
        _form.value =
            current.copy(
                transport = transport,
                authMethod = nextAuthMethod,
                multiplexer =
                    current.multiplexer.takeIf { it?.supportsTransport(transport) == true },
            )
    }

    fun updateAuthMethod(authMethod: AuthMethod) {
        val current = _form.value
        if (authMethod == AuthMethod.None) {
            _form.value =
                current.copy(transport = Transport.TailscaleSSH, authMethod = AuthMethod.None)
            return
        }
        _form.value = current.copy(authMethod = authMethod)
    }

    fun updateRequireAuthOnConnect(enabled: Boolean) {
        _form.value = _form.value.copy(requireAuthOnConnect = enabled)
    }

    fun updatePostConnectCommand(command: String) {
        _form.value = _form.value.copy(postConnectCommand = command)
    }

    fun updateMultiplexer(multiplexer: MultiplexerType?) {
        val current = _form.value
        _form.value =
            current.copy(
                multiplexer = multiplexer?.takeIf { it.supportsTransport(current.transport) }
            )
    }

    fun addJumpHost(id: Long) {
        val current = _form.value
        if (current.jumpHostIds.size < 3 && id !in current.jumpHostIds && id != current.id) {
            _form.value = current.copy(jumpHostIds = current.jumpHostIds + id)
        }
    }

    fun removeJumpHost(id: Long) {
        _form.value = _form.value.copy(jumpHostIds = _form.value.jumpHostIds - id)
    }

    fun moveJumpHost(index: Int, delta: Int) {
        val ids = _form.value.jumpHostIds.toMutableList()
        val to = index + delta
        if (index !in ids.indices || to !in ids.indices) return
        val value = ids.removeAt(index)
        ids.add(to, value)
        _form.value = _form.value.copy(jumpHostIds = ids)
    }

    fun testConnection(verified: Boolean = false) {
        if (requiresTestVerification && !verified) return
        cancelTest()
        val current = _form.value
        val generation = testGeneration
        _testState.value = ConnectionTestState(ConnectionTestStatus.Running)
        testJob =
            viewModelScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val profile = current.toProfile()
                            val hops =
                                SshJumpHost.resolve(
                                    profile,
                                    db.hostProfileDao().getAll(),
                                    db.sshKeyDao().getAll(),
                                )
                            check(
                                verified ||
                                    !(profile.requireAuthOnConnect ||
                                        hops.any { it.profile.requireAuthOnConnect } ||
                                        SettingsRepository.getInstance(getApplication())
                                            .requireAuthOnConnect
                                            .value)
                            ) {
                                "Authenticate before testing this SSH route"
                            }
                            val policy = HostKeyPolicy { request ->
                                verifyTestHostKey(request, generation)
                            }
                            NativeSshService(hostKeyPolicy = policy).use { ssh ->
                                synchronized(testLock) {
                                    if (generation != testGeneration) {
                                        throw kotlinx.coroutines.CancellationException(
                                            "Test cancelled"
                                        )
                                    }
                                    testSsh = ssh
                                }
                                ssh.connect(
                                    isCancelled = { generation != testGeneration },
                                    host = profile.host,
                                    port = profile.port,
                                    username = profile.username,
                                    authMethod = current.authMethod,
                                    password = current.password,
                                    publicKeyOpenSsh = current.publicKeyOpenSsh,
                                    privateKeyPem = current.privateKeyPem,
                                    keyPassphrase = current.keyPassphrase,
                                    jumpHosts = hops,
                                    onProgress = {
                                        synchronized(testLock) {
                                            if (generation == testGeneration) {
                                                _testState.value =
                                                    ConnectionTestState(
                                                        ConnectionTestStatus.Running,
                                                        it,
                                                    )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                if (generation != testGeneration) return@launch
                testSsh = null
                _hostKeyPrompt.value = null
                _testState.value =
                    result.fold(
                        { ConnectionTestState(ConnectionTestStatus.Success, "SSH route verified") },
                        {
                            ConnectionTestState(
                                ConnectionTestStatus.Error,
                                it.message ?: "Connection failed",
                            )
                        },
                    )
            }
    }

    private fun verifyTestHostKey(request: SshHostKeyRequest, generation: Long): Boolean {
        val check =
            hostKeyStore.check(request.trustHost, request.port, request.algorithm, request.keyBytes)
        if (check == HostKeyCheck.Match) return true
        val decision = CompletableDeferred<Boolean>()
        synchronized(testLock) {
            if (generation != testGeneration) {
                throw kotlinx.coroutines.CancellationException("Test cancelled")
            }
            hostKeyDecision = decision
            _hostKeyPrompt.value =
                HostKeyPrompt(
                    routeLabel = request.routeLabel,
                    host = request.host,
                    port = request.port,
                    algorithm = request.algorithm,
                    fingerprint =
                        when (check) {
                            is HostKeyCheck.Unknown -> check.fingerprint
                            is HostKeyCheck.Changed -> check.fingerprint
                            else -> error("Unexpected host key check")
                        },
                    previousFingerprint = (check as? HostKeyCheck.Changed)?.previousFingerprint,
                )
        }
        val accepted =
            try {
                runBlocking { decision.await() }
            } finally {
                synchronized(testLock) {
                    if (hostKeyDecision === decision) {
                        hostKeyDecision = null
                        _hostKeyPrompt.value = null
                    }
                }
            }
        if (accepted) {
            hostKeyStore.saveKey(
                request.trustHost,
                request.port,
                request.algorithm,
                request.keyBytes,
            )
        }
        return accepted
    }

    fun save(onComplete: () -> Unit) {
        val current = _form.value
        val host = current.host.filterNot { it.isWhitespace() }
        if (!current.canSave() || host.isBlank()) return
        val username = current.username.trim()
        if (username.isBlank()) return

        viewModelScope.launch {
            try {
                hostRepository.upsert(current.toProfile())
            } catch (e: Exception) {
                _errorMessage.emit(e.message ?: "Invalid route")
                return@launch
            }
            onComplete()
        }
    }
}

data class AddServerForm(
    val id: Long? = null,
    val jumpHostIds: List<Long> = emptyList(),
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val keyId: Long? = null,
    val privateKeyPem: String = "",
    val publicKeyOpenSsh: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val authMethod: AuthMethod = AuthMethod.Password,
    val requireAuthOnConnect: Boolean = false,
    val postConnectCommand: String = "",
    val multiplexer: MultiplexerType? = null,
)

fun AddServerForm.canSave(): Boolean {
    if (name.isBlank() || host.isBlank() || username.isBlank() || port.toIntOrNull() !in 1..65535) {
        return false
    }
    if (jumpHostIds.isNotEmpty() && transport != Transport.SSH) return false
    return when (authMethod) {
        AuthMethod.Key,
        AuthMethod.KeyWithPassphrase -> keyId != null && privateKeyPem.isNotBlank()
        else -> true
    }
}

enum class ConnectionTestStatus {
    Idle,
    Running,
    Success,
    Error,
}

data class ConnectionTestState(
    val status: ConnectionTestStatus = ConnectionTestStatus.Idle,
    val message: String? = null,
)

private fun AddServerForm.toProfile(): HostProfile =
    HostProfile(
        id = id ?: 0L,
        name = name.trim(),
        host = host.trim(),
        port = port.toIntOrNull() ?: error("Invalid port"),
        username = username.trim(),
        password = password,
        keyId = keyId,
        keyPassphrase = keyPassphrase,
        transport = transport,
        authMethod = authMethod,
        requireAuthOnConnect = requireAuthOnConnect,
        jumpHostIds = jumpHostIds,
        postConnectCommand = postConnectCommand.trim().ifBlank { null },
        multiplexer = multiplexer.takeIf { it?.supportsTransport(transport) == true },
    )
