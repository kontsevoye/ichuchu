package io.github.kontsevoye.ichuchu.model

enum class AuthMethod {
    None,
    Password,
    Key,
    KeyWithPassphrase,
}

fun AuthMethod.requiresKey(): Boolean =
    this == AuthMethod.Key || this == AuthMethod.KeyWithPassphrase
