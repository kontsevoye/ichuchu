package io.github.kontsevoye.ichuchu.service.agents

enum class DeckSessionAction(val label: String, val completed: String) {
    Restart("restart", "Session restarted"),
    Stop("stop", "Session stopped"),
    Delete("delete", "Session deleted"),
}
