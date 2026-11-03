package io.github.kontsevoye.ichuchu.service.ssh

import android.util.Base64
import java.nio.ByteBuffer

/**
 * Checks the container before storing it; encrypted key usability still requires authentication.
 */
internal fun hasPrivateKeyEnvelope(pem: String): Boolean =
    runCatching {
            require(pem.length <= 1024 * 1024)
            val lines = pem.trim().lines().map { it.trim() }
            val label =
                Regex("-----BEGIN ((?:OPENSSH |RSA |EC |DSA |ENCRYPTED )?PRIVATE KEY)-----")
                    .matchEntire(lines.first())
                    ?.groupValues
                    ?.get(1) ?: return false
            require(lines.last() == "-----END $label-----")
            val content = lines.drop(1).dropLast(1).filter { it.isNotEmpty() }
            val headers = content.takeWhile { ':' in it }
            val body = content.drop(headers.size).joinToString("")
            require(body.isNotEmpty() && body.length % 4 == 0)
            require(body.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
            val bytes = Base64.decode(body, Base64.NO_WRAP)
            if (headers.isNotEmpty()) {
                require(label in setOf("RSA PRIVATE KEY", "EC PRIVATE KEY", "DSA PRIVATE KEY"))
                require(headers.size == 2 && headers[0].replace(" ", "") == "Proc-Type:4,ENCRYPTED")
                require(headers[1].matches(Regex("DEK-Info: *[A-Z0-9-]+,[0-9A-Fa-f]+")))
                require(bytes.size >= 16 && bytes.size % 8 == 0)
            } else if (label == "OPENSSH PRIVATE KEY") {
                val input = ByteBuffer.wrap(bytes)
                val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
                require(ByteArray(magic.size).also { input.get(it) }.contentEquals(magic))
                val cipher = input.sshString().toString(Charsets.US_ASCII)
                val kdf = input.sshString().toString(Charsets.US_ASCII)
                val options = input.sshString()
                require(input.int == 1)
                require(input.sshString().isNotEmpty())
                val privateBytes = input.sshString()
                require(!input.hasRemaining() && privateBytes.size >= 16)
                if (cipher == "none") {
                    require(kdf == "none" && options.isEmpty())
                    val privateInput = ByteBuffer.wrap(privateBytes)
                    require(privateInput.int == privateInput.int)
                } else {
                    require(
                        cipher.isNotBlank() &&
                            kdf.isNotBlank() &&
                            kdf != "none" &&
                            options.isNotEmpty()
                    )
                }
            } else {
                val input = ByteBuffer.wrap(bytes)
                val sequence = input.derElement(0x30)
                require(!input.hasRemaining())
                val fields = ByteBuffer.wrap(sequence)
                if (label == "ENCRYPTED PRIVATE KEY") {
                    require(fields.derElement(0x30).isNotEmpty())
                    require(fields.derElement(0x04).isNotEmpty() && !fields.hasRemaining())
                } else {
                    val version = fields.derElement(0x02)
                    require(version.size == 1 && version[0].toInt() in 0..1)
                    require(fields.remaining() >= 8)
                }
            }
            true
        }
        .getOrDefault(false)

private fun ByteBuffer.sshString(): ByteArray {
    val length = int
    require(length >= 0 && length <= remaining())
    return ByteArray(length).also { get(it) }
}

private fun ByteBuffer.derElement(tag: Int): ByteArray {
    require(get().toInt() and 0xff == tag)
    val first = get().toInt() and 0xff
    val length =
        if (first < 128) {
            first
        } else {
            val count = first and 0x7f
            require(count in 1..4)
            var value = 0L
            repeat(count) { value = (value shl 8) or (get().toLong() and 0xff) }
            require(value in 128..remaining().toLong())
            value.toInt()
        }
    require(length <= remaining())
    return ByteArray(length).also { get(it) }
}
