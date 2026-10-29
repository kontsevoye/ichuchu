package io.github.kontsevoye.ichuchu.data.db

import androidx.room.TypeConverter
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.MultiplexerType
import io.github.kontsevoye.ichuchu.model.Transport

class Converters {
    @TypeConverter fun fromJumpHostIds(value: List<Long>): String = value.joinToString(",")

    @TypeConverter
    fun toJumpHostIds(value: String): List<Long> =
        if (value.isEmpty()) emptyList() else value.split(',').map { it.toLong() }

    @TypeConverter fun fromTransport(value: Transport): String = value.name

    @TypeConverter fun toTransport(value: String): Transport = Transport.valueOf(value)

    @TypeConverter fun fromAuthMethod(value: AuthMethod): String = value.name

    @TypeConverter fun toAuthMethod(value: String): AuthMethod = AuthMethod.valueOf(value)

    @TypeConverter fun fromMultiplexer(value: MultiplexerType?): String? = value?.id

    @TypeConverter
    fun toMultiplexer(value: String?): MultiplexerType? =
        value?.let { MultiplexerType.fromPersistedValue(it) }
}
