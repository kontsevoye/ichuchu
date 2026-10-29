package io.github.kontsevoye.ichuchu.service.ssh

fun interface HostKeyPolicy {
    fun verify(host: String, port: Int, algorithm: String, keyBytes: ByteArray): Boolean
}
