package io.github.kontsevoye.ichuchu.model

enum class MultiplexerType(val id: String, val label: String, val runtimeSupported: Boolean) {
    Tmux("tmux", "tmux", true),
    Zellij("zellij", "zellij", false),
    Zmx("zmx", "zmx", true);

    fun supportsTransport(transport: Transport): Boolean =
        runtimeSupported &&
            when (transport) {
                Transport.SSH,
                Transport.TailscaleSSH -> true
                Transport.Mosh -> this == Tmux
                Transport.LocalShell -> false
            }

    companion object {
        val runtimeOptions: List<MultiplexerType> = entries.filter { it.runtimeSupported }

        fun fromPersistedValue(value: String): MultiplexerType? =
            entries.firstOrNull { it.id == value } ?: entries.firstOrNull { it.name == value }
    }
}
