package io.github.kontsevoye.ichuchu.data.update

import kotlinx.serialization.Serializable

const val MAX_UPDATE_BYTES = 256L * 1024 * 1024
const val MAX_METADATA_BYTES = 1024L * 1024

@Serializable
data class UpdateArtifact(val abi: String, val name: String, val size: Long, val sha256: String)

@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val distribution: String,
    val channel: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val commit: String,
    val certificateSha256: String,
    val artifacts: List<UpdateArtifact>,
)

@Serializable
data class UpdateCandidate(
    val manifest: UpdateManifest,
    val artifact: UpdateArtifact,
    val downloadUrl: String,
    val releaseUrl: String,
    val notes: String,
)

data class UpdateIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val sdk: Int,
    val abis: List<String>,
    val certificates: Set<String>,
)

data class UpdateConfig(val enabled: Boolean, val repository: String, val distribution: String)

enum class UpdatePhase {
    Idle,
    Checking,
    UpToDate,
    Available,
    Downloading,
    Validating,
    Ready,
    PermissionRequired,
    SessionsOpen,
    Installing,
    UserConfirmation,
    Installed,
    Error,
}

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val candidate: UpdateCandidate? = null,
    val received: Long = 0,
    val lastChecked: Long = 0,
    val message: String? = null,
    val confirmationGeneration: Long = 0,
    val failure: UpdateFailure? = null,
)

enum class UpdateFailure {
    Network,
    RateLimited,
    UnsupportedRelease,
    InvalidMetadata,
    InvalidApk,
    Install,
}

class UpdateException(val failure: UpdateFailure, message: String, val retryAt: Long = 0) :
    Exception(message)

/** All gates are checked again at install time against the currently installed package. */
object UpdatePolicy {
    fun versionCode(name: String): Long {
        val match =
            Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(name)
                ?: invalid("Invalid stable version")
        val parts =
            match.groupValues.drop(1).map { it.toLongOrNull() ?: invalid("Invalid version") }
        if (parts[1] > 999 || parts[2] > 999 || parts[0] > 2099) invalid("Version overflow")
        return (1_000_000 + parts[0] * 1_000_000 + parts[1] * 1000 + parts[2]).also {
            if (it > 2_100_000_000) invalid("Version overflow")
        }
    }

    fun validate(
        manifest: UpdateManifest,
        identity: UpdateIdentity,
        config: UpdateConfig,
    ): UpdateArtifact {
        if (
            manifest.schemaVersion != 1 ||
                manifest.channel != "stable" ||
                manifest.distribution != config.distribution ||
                manifest.packageName != identity.packageName
        )
            invalid("This release belongs to a different application or distribution")
        if (
            manifest.versionCode != versionCode(manifest.versionName) ||
                manifest.minSdk < 24 ||
                !Regex("[0-9a-f]{40}").matches(manifest.commit)
        )
            invalid("Inconsistent release metadata")
        if (manifest.minSdk > identity.sdk) invalid("This update requires a newer Android version")
        if (
            !Regex("[0-9a-f]{64}").matches(manifest.certificateSha256) ||
                identity.certificates != setOf(manifest.certificateSha256)
        )
            invalid("Release signing certificate does not match this installation")
        val artifact =
            manifest.artifacts.singleOrNull { it.abi == "arm64-v8a" && it.name == "ichuchu.apk" }
                ?: invalid("No supported APK in this release")
        if (artifact.abi !in identity.abis) invalid("This device does not support the release ABI")
        if (
            artifact.size !in 1..MAX_UPDATE_BYTES || !Regex("[0-9a-f]{64}").matches(artifact.sha256)
        )
            invalid("Invalid APK size or digest")
        return artifact
    }

    private fun invalid(message: String): Nothing =
        throw UpdateException(UpdateFailure.InvalidMetadata, message)
}
