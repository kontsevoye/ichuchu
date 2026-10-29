package io.github.kontsevoye.ichuchu

import io.github.kontsevoye.ichuchu.data.backup.buildBackupPayload
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.SshKey
import io.github.kontsevoye.ichuchu.service.backup.IchuchuBackupCodec
import io.github.kontsevoye.ichuchu.service.ssh.Ed25519KeyGenerator
import org.junit.Assert.*
import org.junit.Test

/** Uses actual Android JNI; the JVM suite cannot load an Android ELF library. */
class NativeSecuritySmokeTest {
    @Test
    fun nativeKeyGenerationAndEncryptedBackupRemainUsable() {
        val generated =
            Ed25519KeyGenerator().generate("synthetic-native-test", "synthetic-passphrase")
        assertTrue(generated.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(generated.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
        val key =
            SshKey(
                name = "synthetic-backup",
                privateKeyPem = "SYNTHETIC_PRIVATE_KEY",
                publicKeyOpenSsh = "synthetic-public",
                createdAtEpochMs = 1,
            )
        val host =
            HostProfile(
                name = "synthetic-host",
                host = "example.invalid",
                username = "test",
                password = "SYNTHETIC_PASSWORD",
            )
        val payload = buildBackupPayload(listOf(key), listOf(host))
        val passphrase = "synthetic backup passphrase".toCharArray()
        try {
            val encrypted = IchuchuBackupCodec.encrypt(payload, passphrase)
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("SYNTHETIC_PRIVATE_KEY"))
            assertEquals(payload, IchuchuBackupCodec.decrypt(encrypted, passphrase))
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            try {
                IchuchuBackupCodec.decrypt(encrypted, passphrase)
                fail("Tampered backup must be rejected")
            } catch (_: io.github.kontsevoye.ichuchu.data.backup.InvalidBackupPassphraseException) {
                // GCM authentication must succeed before a payload can be returned.
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }
}
