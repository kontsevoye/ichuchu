package io.github.kontsevoye.ichuchu.model

/** An explicit route uses only each hop's endpoint, never its own saved route. */
object JumpRoutes {
    const val MAX_HOPS = 3

    fun validate(target: HostProfile, profiles: List<HostProfile>): List<HostProfile> {
        require(target.jumpHostIds.size <= MAX_HOPS) {
            "At most $MAX_HOPS SSH jump hosts are supported"
        }
        if (target.jumpHostIds.isEmpty()) return emptyList()
        require(target.transport == Transport.SSH) { "Jump hosts currently require SSH transport" }
        require(target.jumpHostIds.distinct().size == target.jumpHostIds.size) {
            "A jump host cannot appear twice"
        }
        require(target.id == 0L || target.id !in target.jumpHostIds) {
            "A server cannot jump through itself"
        }
        val hops =
            target.jumpHostIds.map { id ->
                require(id > 0) { "Invalid jump host id" }
                profiles.firstOrNull { it.id == id } ?: error("Jump host $id no longer exists")
            }
        (hops + target).forEach {
            require(it.transport == Transport.SSH) {
                "${it.name}: jump routes support SSH profiles only"
            }
            require(it.host.isNotBlank() && it.username.isNotBlank() && it.port in 1..65535) {
                "${it.name}: invalid SSH endpoint"
            }
            require(it.authMethod != AuthMethod.None) {
                "${it.name}: select password or key authentication"
            }
            require(!it.authMethod.requiresKey() || it.keyId != null) {
                "${it.name}: missing SSH key"
            }
        }
        val endpoints = (hops + target).map { Triple(it.host.lowercase(), it.port, it.username) }
        require(endpoints.distinct().size == endpoints.size) {
            "An SSH endpoint cannot appear twice in a route"
        }
        return hops
    }
}
