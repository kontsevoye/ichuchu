package io.github.kontsevoye.ichuchu.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

interface UpdateApkInspector {
    fun installed(): UpdateIdentity

    fun verify(file: File, candidate: UpdateCandidate, config: UpdateConfig)
}

class ApkVerifier(private val context: Context) : UpdateApkInspector {
    override fun installed(): UpdateIdentity {
        val info = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
        return UpdateIdentity(
            context.packageName,
            code(info),
            info.versionName.orEmpty(),
            Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.toList(),
            certificates(info),
        )
    }

    override fun verify(file: File, candidate: UpdateCandidate, config: UpdateConfig) {
        val identity = installed()
        UpdatePolicy.validate(candidate.manifest, identity, config)
        if (candidate.manifest.versionCode <= identity.versionCode)
            invalid("This APK is not newer than the installed version")
        if (
            !file.isFile ||
                file.length() != candidate.artifact.size ||
                digest(file) != candidate.artifact.sha256
        )
            invalid("APK size or SHA-256 does not match the release")
        val info =
            context.packageManager.getPackageArchiveInfo(file.path, signatureFlags())
                ?: invalid("Cannot read APK manifest")
        if (
            info.packageName != identity.packageName ||
                code(info) != candidate.manifest.versionCode ||
                info.versionName != candidate.manifest.versionName ||
                certificates(info) != identity.certificates ||
                identity.certificates.isEmpty()
        )
            invalid("APK package, version or signer does not match this installation")
        if (
            Build.VERSION.SDK_INT >= 26 &&
                info.applicationInfo?.minSdkVersion != candidate.manifest.minSdk
        )
            invalid("APK minSdk differs from release metadata")
    }

    companion object {
        fun digest(file: File): String =
            file.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().hex()
            }

        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        fun code(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()

        fun signatureFlags(): Int =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

        fun certificates(info: PackageInfo): Set<String> {
            val values =
                if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
                else @Suppress("DEPRECATION") info.signatures
            return values
                .orEmpty()
                .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }
                .toSet()
        }

        private fun invalid(message: String): Nothing =
            throw UpdateException(UpdateFailure.InvalidApk, message)
    }
}
