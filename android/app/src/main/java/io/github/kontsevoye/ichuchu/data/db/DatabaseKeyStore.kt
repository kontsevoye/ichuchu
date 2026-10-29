package io.github.kontsevoye.ichuchu.data.db

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps a random SQLCipher passphrase; the wrapping key never leaves Android Keystore. */
internal class DatabaseKeyStore(
    directory: File,
    private val alias: String = "ichuchu.database.wrapping-key.v1",
) {
    private val file = File(directory, "database-key.v1")
    private val atomicFile = AtomicFile(file)

    @Synchronized
    fun loadOrCreate(databaseExists: Boolean): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (file.exists() || File(file.path + ".bak").exists()) {
            val key =
                store.getKey(alias, null) as? SecretKey
                    ?: error("Database key is unavailable; restore an encrypted backup")
            val envelope =
                atomicFile.openRead().use { input ->
                    val bytes = ByteArray(DatabaseKeyEnvelope.MAX_ENVELOPE_BYTES + 1)
                    var size = 0
                    while (size < bytes.size) {
                        val read = input.read(bytes, size, bytes.size - size)
                        if (read < 0) break
                        size += read
                    }
                    check(size <= DatabaseKeyEnvelope.MAX_ENVELOPE_BYTES) {
                        "Invalid database key file"
                    }
                    bytes.copyOf(size)
                }
            return DatabaseKeyEnvelope.decrypt(key, envelope)
        }
        check(!databaseExists) { "Database key is missing; restore an encrypted backup" }
        val key = (store.getKey(alias, null) as? SecretKey) ?: createWrappingKey()
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase =
            try {
                Base64.encode(random, Base64.NO_WRAP)
            } finally {
                random.fill(0)
            }
        try {
            val envelope = DatabaseKeyEnvelope.encrypt(key, passphrase)
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            val output = atomicFile.startWrite()
            try {
                output.write(envelope)
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
            return passphrase
        } catch (error: Throwable) {
            passphrase.fill(0)
            throw error
        }
    }

    private fun createWrappingKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                // Background sessions need storage access; App Lock gates UI separately.
                .build()
        )
        return generator.generateKey()
    }
}

internal object DatabaseKeyEnvelope {
    const val MAX_ENVELOPE_BYTES = 128
    private const val IV_BYTES = 12
    private val aad = "io.github.kontsevoye.ichuchu.database-key.v1".toByteArray(Charsets.UTF_8)

    fun encrypt(key: SecretKey, passphrase: ByteArray): ByteArray {
        require(passphrase.size == 44) { "Invalid database passphrase size" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        check(cipher.iv.size == IV_BYTES)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(passphrase)
    }

    fun decrypt(key: SecretKey, envelope: ByteArray): ByteArray {
        require(envelope.size == 1 + IV_BYTES + 44 + 16 && envelope[0] == 1.toByte()) {
            "Invalid database key file"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, envelope.copyOfRange(1, 1 + IV_BYTES)),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(envelope, 1 + IV_BYTES, envelope.size - 1 - IV_BYTES)
    }
}
