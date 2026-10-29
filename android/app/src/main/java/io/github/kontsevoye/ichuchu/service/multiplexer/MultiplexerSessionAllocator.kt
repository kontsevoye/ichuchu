package io.github.kontsevoye.ichuchu.service.multiplexer

object MultiplexerSessionAllocator {
    private val ichuchuSessionRegex = Regex("^ichuchu-([1-9][0-9]*)$")

    fun reusableDetachedIchuchuSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String? {
        val localNames = localSessionNames.toSet()
        return remoteSessions
            .asSequence()
            .filter { session -> !session.attached && session.name !in localNames }
            .mapNotNull { session ->
                ichuchuSessionRegex
                    .matchEntire(session.name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { index -> session.name to index }
            }
            .maxByOrNull { (_, index) -> index }
            ?.first
    }

    fun nextIchuchuSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String {
        val used = remoteSessions.map { it.name }.toSet() + localSessionNames
        var index = 1
        while ("ichuchu-$index" in used) index += 1
        return "ichuchu-$index"
    }
}
