package io.github.kontsevoye.ichuchu.service.ssh

import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.JumpRoutes
import io.github.kontsevoye.ichuchu.model.SshKey
import io.github.kontsevoye.ichuchu.model.requiresKey
import java.security.MessageDigest

/** Immutable credentials for a single hop. Never print credentials in diagnostics. */
data class SshJumpHost(
    val profile: HostProfile,
    val publicKeyOpenSsh: String = "",
    val privateKeyPem: String = "",
) {
    override fun toString(): String = "SshJumpHost(${profile.name})"

    companion object {
        fun resolve(
            target: HostProfile,
            profiles: List<HostProfile>,
            keys: List<SshKey>,
        ): List<SshJumpHost> =
            JumpRoutes.validate(target, profiles).map { profile ->
                val key = profile.keyId?.let { id -> keys.firstOrNull { it.id == id } }
                require(!profile.authMethod.requiresKey() || key != null) {
                    "${profile.name}: SSH key no longer exists"
                }
                SshJumpHost(profile, key?.publicKeyOpenSsh.orEmpty(), key?.privateKeyPem.orEmpty())
            }
    }
}

/**
 * Length-prefixed endpoints avoid ambiguity with IPv6, separators, and private DNS. Profile IDs and
 * names are deliberately excluded: import/rename cannot alias trust to a different network, while
 * the same endpoint path keeps its existing identity.
 */
fun sshTrustHost(host: String, port: Int, username: String, hops: List<SshJumpHost>): String {
    if (hops.isEmpty()) return host
    val path = buildString {
        (hops.map { Triple(it.profile.host, it.profile.port, it.profile.username) } +
                Triple(host, port, username))
            .forEach { (h, p, u) ->
                listOf(h.lowercase(), p.toString(), u).forEach {
                    append(it.length).append(':').append(it)
                }
            }
    }
    val digest =
        MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    return "route-v1:$digest"
}

class SshConnectionException(message: String, val retryable: Boolean, cause: Throwable? = null) : IllegalStateException(message, cause)

data class SshHostKeyRequest(
    val host: String,
    val port: Int,
    val algorithm: String,
    val keyBytes: ByteArray,
    val trustHost: String = host,
    val routeLabel: String = "",
)
