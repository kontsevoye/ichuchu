package io.github.kontsevoye.ichuchu.service.multiplexer

import io.github.kontsevoye.ichuchu.model.MultiplexerType

object MultiplexerRegistry {
    val defaultType: MultiplexerType = MultiplexerType.Tmux

    fun forType(type: MultiplexerType): Multiplexer? = when (type) {
        MultiplexerType.Tmux -> TmuxMultiplexer
        MultiplexerType.Zmx -> ZmxMultiplexer
        MultiplexerType.Zellij -> null
    }
}
