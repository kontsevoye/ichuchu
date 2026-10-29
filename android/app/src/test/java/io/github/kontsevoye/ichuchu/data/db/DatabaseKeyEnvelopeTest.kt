package io.github.kontsevoye.ichuchu.data.db

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class DatabaseKeyEnvelopeTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val passphrase = "SYNTHETIC-".repeat(4).plus("KEY!").toByteArray()

    @Test
    fun wrapsPassphraseWithFreshNoncesAndAuthenticatesIt() {
        assertEquals(44, passphrase.size)
        val key = key()
        val first = DatabaseKeyEnvelope.encrypt(key, passphrase)
        val second = DatabaseKeyEnvelope.encrypt(key, passphrase)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(passphrase, DatabaseKeyEnvelope.decrypt(key, first))
        assertFalse(String(first, Charsets.ISO_8859_1).contains("SYNTHETIC-"))
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { DatabaseKeyEnvelope.decrypt(key, first) }
    }

    @Test
    fun wrongWrappingKeyCannotDecryptPassphrase() {
        val envelope = DatabaseKeyEnvelope.encrypt(key(), passphrase)
        assertThrows(AEADBadTagException::class.java) {
            DatabaseKeyEnvelope.decrypt(key(), envelope)
        }
    }

    @Test
    fun malformedOrUnknownEnvelopesAreRejected() {
        val key = key()
        for (bytes in listOf(byteArrayOf(), ByteArray(1000), ByteArray(73))) {
            assertThrows(IllegalArgumentException::class.java) {
                DatabaseKeyEnvelope.decrypt(key, bytes)
            }
        }
        val envelope = DatabaseKeyEnvelope.encrypt(key, passphrase)
        envelope[0] = 2
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseKeyEnvelope.decrypt(key, envelope)
        }
    }
}
