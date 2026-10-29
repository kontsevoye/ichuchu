package io.github.kontsevoye.ichuchu.service.ssh

fun interface HostKeyPolicy {
    fun verify(request: SshHostKeyRequest): Boolean
}
