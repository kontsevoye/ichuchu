package io.github.kontsevoye.ichuchu.service.agents

/** User text is always stdin. Only quoted executable/argument tokens enter the remote shell. */
object DeckCommands {
    fun quote(value: String): String {
        require('\u0000' !in value) { "NUL is not a valid command argument" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun discover(configured: String): String {
        if (configured.isNotBlank()) {
            require(configured.none { it == '\n' || it == '\r' }) {
                "Invalid Agent Deck executable"
            }
            val path =
                if (configured.startsWith("~/")) {
                    "\"\$HOME\"/" + quote(configured.removePrefix("~/"))
                } else {
                    quote(configured)
                }
            return "command -v $path"
        }
        return "if command -v agent-deck >/dev/null 2>&1; then command -v agent-deck; else " +
            "for deck_bin in \"\$HOME/go/bin/agent-deck\" \"\$HOME/.local/bin/agent-deck\" \"\$HOME/.nix-profile/bin/agent-deck\" /opt/homebrew/bin/agent-deck /usr/local/bin/agent-deck; do " +
            "if [ -x \"\$deck_bin\" ]; then printf '%s\\n' \"\$deck_bin\"; exit 0; fi; done; exit 127; fi"
    }

    fun command(executable: String, profile: String, vararg args: String): String {
        require(executable.isNotBlank())
        require(profile.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid Agent Deck profile" }
        return "exec " +
            (listOf(executable, "-p", profile) + args).joinToString(" ", transform = ::quote)
    }

    fun attach(executable: String, profile: String, id: String): String =
        command(executable, profile, "session", "attach", id)

    /** A disconnected no-PTY reader must not leave a permanent remote follower behind. */
    fun leasedFollow(command: String): String {
        val script =
            "$command & deck_reader=\$!; " +
                "(sleep 55; kill -TERM \"\$deck_reader\" 2>/dev/null) & deck_lease=\$!; " +
                "trap 'kill -TERM \"\$deck_reader\" \"\$deck_lease\" 2>/dev/null' HUP INT TERM EXIT; " +
                "wait \"\$deck_reader\"; deck_result=\$?; trap - HUP INT TERM EXIT; kill -TERM \"\$deck_lease\" 2>/dev/null; " +
                "wait \"\$deck_lease\" 2>/dev/null; exit \"\$deck_result\""
        return "exec sh -c " + quote(script)
    }
}
