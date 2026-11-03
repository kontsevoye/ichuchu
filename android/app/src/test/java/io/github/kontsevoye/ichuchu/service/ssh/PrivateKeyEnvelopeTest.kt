package io.github.kontsevoye.ichuchu.service.ssh

import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateKeyEnvelopeTest {
    @Test
    fun acceptsPkcs8AndRejectsCorruptOrUnrelatedInput() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val body = Base64.getEncoder().encodeToString(key.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
        assertTrue(hasPrivateKeyEnvelope(pem))
        assertTrue(hasPrivateKeyEnvelope(pem.replace("\n", "\r\n")))
        for (invalid in
            listOf(
                "",
                "This PRIVATE KEY is arbitrary text",
                key.public.toString(),
                pem.replace("END PRIVATE", "END RSA PRIVATE"),
                pem.replace(body, "%%%"),
                pem.replace(body, body.dropLast(4)),
                pem.replace(body, "AAAA"),
                pem.replace("BEGIN PRIVATE", "BEGIN OPENSSH PRIVATE")
                    .replace("END PRIVATE", "END OPENSSH PRIVATE"),
                pem + "\nsecond payload",
                "x".repeat(1024 * 1024 + 1),
            )) {
                assertFalse(hasPrivateKeyEnvelope(invalid))
            }
    }
}
