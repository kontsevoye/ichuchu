package io.github.kontsevoye.ichuchu.data.repository

import io.github.kontsevoye.ichuchu.data.db.HostProfileDao
import io.github.kontsevoye.ichuchu.model.HostProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HostRepository(private val dao: HostProfileDao) {
    fun observeAll(): Flow<List<HostProfile>> = dao.observeAll()

    fun observeKeyUsageCounts(): Flow<Map<Long, Int>> =
        dao.observeKeyUsage().map { rows -> rows.associate { it.keyId to it.count } }

    suspend fun getById(id: Long): HostProfile? = dao.getById(id)

    suspend fun upsert(profile: HostProfile): Long = dao.saveValidated(profile)

    suspend fun delete(profile: HostProfile) = dao.deleteUnused(profile)

    suspend fun clearKeyReference(keyId: Long) = dao.clearKeyReference(keyId)
}
