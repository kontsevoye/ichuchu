package io.github.kontsevoye.ichuchu.data.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.kontsevoye.ichuchu.service.terminal.TerminalSessionRepository
import io.github.kontsevoye.ichuchu.service.update.UpdateInstaller
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application-scoped operations survive screen recreation; every install still needs unlocked user
 * interaction.
 */
class UpdateRepository(
    private val application: Application,
    val config: UpdateConfig,
    val store: UpdateStore = UpdateStore(application),
    private val source: GitHubUpdateSource = GitHubUpdateSource(config, store),
    private val downloader: UpdateDownloader = UpdateDownloader(),
    private val verifier: UpdateApkInspector = ApkVerifier(application),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var manualCheckRequested = false
    private val directory = File(application.cacheDir, "updates").apply { mkdirs() }.canonicalFile
    private val installer = UpdateInstaller(application, store)
    private val sessions
        get() = TerminalSessionRepository.getInstance(application)

    private val _state = MutableStateFlow(UpdateState(lastChecked = store.lastChecked))
    val state = _state.asStateFlow()
    private val _automatic = MutableStateFlow(store.automatic)
    val automatic = _automatic.asStateFlow()
    private val _openSettings = MutableStateFlow(false)
    val openSettings = _openSettings.asStateFlow()

    fun requestOpenSettings() {
        _openSettings.value = true
    }

    fun consumeOpenSettings() {
        _openSettings.value = false
    }

    var interactive = false
    var onAttention: () -> Unit = {}
    var onScheduleChanged: () -> Unit = {}
    private var confirmation: Intent? = null
    val installed
        get() = verifier.installed()

    init {
        restore()
        val stale =
            directory
                .listFiles()
                ?.filter { it.name.endsWith(".part") || it.name != store.filename }
                .orEmpty()
        scope.launch(Dispatchers.IO) { stale.forEach { it.delete() } }
    }

    fun setAutomatic(value: Boolean) {
        store.automatic = value && config.enabled
        _automatic.value = store.automatic
        if (!value && !manualCheckRequested && _state.value.phase == UpdatePhase.Checking)
            operation?.cancel()
        onScheduleChanged()
    }

    suspend fun check(background: Boolean = false) =
        withContext(Dispatchers.Main.immediate) {
            if (!config.enabled) return@withContext
            if (operation?.isActive != true) {
                manualCheckRequested = !background
                operation =
                    scope.launch {
                        if (store.sessionId >= 0) return@launch
                        val previous = _state.value
                        _state.value = previous.copy(phase = UpdatePhase.Checking, message = null)
                        try {
                            val candidate = source.latest(installed)
                            store.lastChecked = System.currentTimeMillis()
                            if (candidate != previous.candidate) clearDownload()
                            store.candidate = candidate
                            store.installMessage = null
                            _state.value =
                                UpdateState(
                                    if (candidate == null) UpdatePhase.UpToDate
                                    else if (readyFile() != null) UpdatePhase.Ready
                                    else UpdatePhase.Available,
                                    candidate,
                                    lastChecked = store.lastChecked,
                                )
                        } catch (error: TimeoutCancellationException) {
                            _state.value =
                                previous.copy(
                                    phase = UpdatePhase.Error,
                                    message = "Update check timed out",
                                    failure = UpdateFailure.Network,
                                )
                        } catch (error: CancellationException) {
                            _state.value = previous
                            throw error
                        } catch (error: Exception) {
                            _state.value =
                                previous.copy(
                                    phase = UpdatePhase.Error,
                                    message = userMessage(error),
                                    failure =
                                        (error as? UpdateException)?.failure
                                            ?: UpdateFailure.Network,
                                )
                        }
                    }
            } else if (!background && _state.value.phase == UpdatePhase.Checking)
                manualCheckRequested = true
            val pending = operation
            try {
                pending?.join()
            } finally {
                if (
                    background &&
                        !currentCoroutineContext().isActive &&
                        !manualCheckRequested &&
                        _state.value.phase == UpdatePhase.Checking
                )
                    pending?.cancel()
            }
        }

    fun download() {
        if (!config.enabled || operation?.isActive == true || store.sessionId >= 0) return
        val candidate = _state.value.candidate ?: return
        operation =
            scope.launch {
                clearDownload()
                val ready = File(directory, "${UUID.randomUUID()}.apk")
                val part = File(directory, "${ready.name}.part")
                val job = currentCoroutineContext().job
                _state.update {
                    it.copy(phase = UpdatePhase.Downloading, received = 0, message = null)
                }
                try {
                    downloader.download(candidate, part, ready) { count ->
                        if (job.isActive) _state.update { it.copy(received = count) }
                    }
                    _state.update { it.copy(phase = UpdatePhase.Validating) }
                    withContext(Dispatchers.IO) { verifier.verify(ready, candidate, config) }
                    store.filename = ready.name
                    _state.update { it.copy(phase = UpdatePhase.Ready) }
                } catch (error: TimeoutCancellationException) {
                    part.delete()
                    ready.delete()
                    _state.update {
                        it.copy(
                            phase = UpdatePhase.Error,
                            message = "APK download timed out",
                            received = 0,
                            failure = UpdateFailure.Network,
                        )
                    }
                } catch (error: CancellationException) {
                    part.delete()
                    ready.delete()
                    _state.update { it.copy(phase = UpdatePhase.Available, received = 0) }
                    throw error
                } catch (error: Exception) {
                    part.delete()
                    ready.delete()
                    _state.update {
                        it.copy(phase = UpdatePhase.Error, message = userMessage(error))
                    }
                }
            }
    }

    fun cancelDownload() {
        operation?.cancel()
    }

    fun install(closeSessions: Boolean = false) {
        if (!interactive || !config.enabled || operation?.isActive == true || store.sessionId >= 0)
            return
        val candidate = _state.value.candidate ?: return
        val file = readyFile() ?: return
        if (!installer.permitted()) {
            _state.update { it.copy(phase = UpdatePhase.PermissionRequired, message = null) }
            return
        }
        operation =
            scope.launch {
                try {
                    _state.update { it.copy(phase = UpdatePhase.Validating, message = null) }
                    withContext(Dispatchers.IO) { verifier.verify(file, candidate, config) }
                    if (!interactive) {
                        _state.update { it.copy(phase = UpdatePhase.Ready) }
                        return@launch
                    }
                    if (!installer.permitted()) {
                        _state.update { it.copy(phase = UpdatePhase.PermissionRequired) }
                        return@launch
                    }
                    if (!sessions.beginUpdateInstallation(closeSessions)) {
                        _state.update { it.copy(phase = UpdatePhase.SessionsOpen) }
                        return@launch
                    }
                    _state.update { it.copy(phase = UpdatePhase.Installing) }
                    val job = currentCoroutineContext().job
                    withContext(Dispatchers.IO) {
                        installer.start(file, candidate) { job.isActive }
                    }
                } catch (error: CancellationException) {
                    installer.abandon()
                    sessions.endUpdateInstallation()
                    _state.update { it.copy(phase = UpdatePhase.Ready, message = null) }
                    throw error
                } catch (error: Exception) {
                    sessions.endUpdateInstallation()
                    _state.update {
                        it.copy(phase = UpdatePhase.Error, message = userMessage(error))
                    }
                }
            }
    }

    fun confirmationLaunchFailed() {
        cancelInstallation()
        _state.update {
            it.copy(phase = UpdatePhase.Error, message = "Android installer could not be opened")
        }
    }

    fun takeConfirmation(): Intent? {
        if (!interactive) return null
        return confirmation.also { confirmation = null }
    }

    fun resumeInstallation() {
        if (!interactive || !config.enabled || operation?.isActive == true) return
        if (_state.value.phase != UpdatePhase.UserConfirmation) return
        try {
            sessions.holdUpdateInstallation()
            // A new callback must be a new observable transition even after process death.
            _state.update { it.copy(phase = UpdatePhase.Installing, message = null) }
            installer.resume()
        } catch (error: Exception) {
            cancelInstallation()
            _state.update { it.copy(phase = UpdatePhase.Error, message = userMessage(error)) }
        }
    }

    fun cancelInstallation() {
        operation?.cancel()
        installer.abandon()
        confirmation = null
        sessions.endUpdateInstallation()
        _state.update {
            it.copy(
                phase = if (readyFile() == null) UpdatePhase.Available else UpdatePhase.Ready,
                message = null,
            )
        }
    }

    fun receiveInstallResult(intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id != store.sessionId || id < 0 || intent.action != store.callbackAction) return
        when (
            val status =
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        ) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmation =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                store.waitingForUser = true
                _state.update {
                    it.copy(
                        phase = UpdatePhase.UserConfirmation,
                        confirmationGeneration = it.confirmationGeneration + 1,
                    )
                }
                if (!interactive) onAttention()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                store.clearSession()
                clearDownload()
                store.candidate = null
                sessions.endUpdateInstallation()
                _state.value = UpdateState(UpdatePhase.Installed, lastChecked = store.lastChecked)
            }
            else -> {
                val message =
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation cancelled"
                        PackageInstaller.STATUS_FAILURE_CONFLICT ->
                            "Installation conflicts with the installed package or signer"
                        PackageInstaller.STATUS_FAILURE_STORAGE ->
                            "Not enough storage for this update"
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "This update is incompatible with this device"
                        PackageInstaller.STATUS_FAILURE_BLOCKED ->
                            "Android blocked installation; check app install permission"
                        else -> "Android could not install this update"
                    }
                store.installMessage = message
                store.clearSession()
                confirmation = null
                sessions.endUpdateInstallation()
                _state.update { it.copy(phase = UpdatePhase.Error, message = message) }
            }
        }
    }

    fun readyFile(): File? {
        val name = store.filename ?: return null
        if (!Regex("[0-9a-f-]{36}\\.apk").matches(name)) return null
        val file = File(directory, name)
        return file.takeIf { it.isFile && it.canonicalFile.parentFile == directory }
    }

    private fun clearDownload() {
        readyFile()?.delete()
        store.filename = null
    }

    private fun restore() {
        val candidate = store.candidate
        if (store.sessionId >= 0 && installed.versionCode >= store.expectedCode) {
            installer.abandon()
            clearDownload()
            store.candidate = null
            _state.value = UpdateState(UpdatePhase.Installed, lastChecked = store.lastChecked)
        } else if (store.sessionId >= 0 && installer.liveSession()) {
            sessions.holdUpdateInstallation()
            // Recovery always requires an explicit Resume, not an automatic commit loop.
            _state.value =
                UpdateState(
                    if (!store.waitingForUser && installer.processing()) UpdatePhase.Installing
                    else UpdatePhase.UserConfirmation,
                    candidate,
                    lastChecked = store.lastChecked,
                    message = "An installation is pending. Continue or cancel it.",
                )
        } else {
            if (store.sessionId >= 0) installer.abandon()
            _state.value =
                UpdateState(
                    if (candidate == null) UpdatePhase.Idle
                    else if (readyFile() == null) UpdatePhase.Available else UpdatePhase.Ready,
                    candidate,
                    lastChecked = store.lastChecked,
                    message = store.installMessage,
                )
        }
    }

    private fun userMessage(error: Exception): String =
        when (error) {
            is UpdateException -> error.message.orEmpty()
            else -> "Update request failed. Check connectivity and free storage, then retry."
        }
}
