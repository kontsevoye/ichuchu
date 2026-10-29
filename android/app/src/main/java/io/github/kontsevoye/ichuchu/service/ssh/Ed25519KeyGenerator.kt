package io.github.kontsevoye.ichuchu.service.ssh

import io.github.kontsevoye.ichuchu.model.SshKey

class Ed25519KeyGenerator(private val bridge: NativeSshBridge = NativeSshBridge()) {
    fun generate(name: String, passphrase: String = ""): SshKey {
        val normalizedName = name.trim().ifBlank { "android-ed25519" }
        val comment = "$normalizedName@ichuchu"
        val effectivePassphrase = passphrase.ifBlank { null }

        val result = bridge.nativeGenerateEd25519Key(comment, effectivePassphrase)
            ?: throw IllegalStateException("Native Ed25519 key generation failed")

        return SshKey(
            name = normalizedName,
            algorithm = "Ed25519",
            privateKeyPem = result[0],
            publicKeyOpenSsh = result[1].trimEnd(),
            createdAtEpochMs = System.currentTimeMillis(),
        )
    }
}
