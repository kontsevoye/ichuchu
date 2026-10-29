package io.github.kontsevoye.ichuchu.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.JumpRoutes
import kotlinx.coroutines.flow.Flow

@Dao
interface HostProfileDao {
    @Query("SELECT * FROM host_profiles ORDER BY name")
    fun observeAll(): Flow<List<HostProfile>>

    @Query("SELECT * FROM host_profiles WHERE id = :id")
    suspend fun getById(id: Long): HostProfile?

    @Query("SELECT * FROM host_profiles ORDER BY name")
    suspend fun getAll(): List<HostProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: HostProfile): Long

    @Insert suspend fun insertForImport(profile: HostProfile): Long

    @Update suspend fun update(profile: HostProfile)

    @Delete suspend fun delete(profile: HostProfile)

    @Transaction
    suspend fun saveValidated(profile: HostProfile): Long {
        val profiles = getAll()
        JumpRoutes.validate(profile, profiles)
        profiles
            .filter { profile.id in it.jumpHostIds }
            .forEach { dependent ->
                JumpRoutes.validate(
                    dependent,
                    profiles.map { if (it.id == profile.id) profile else it },
                )
            }
        return insert(profile)
    }

    @Transaction
    suspend fun deleteUnused(profile: HostProfile) {
        val users = getAll().filter { profile.id in it.jumpHostIds }
        check(users.isEmpty()) {
            "Used as a jump host by: ${users.joinToString { it.name }}. Edit these routes first."
        }
        delete(profile)
    }

    @Query("UPDATE host_profiles SET keyId = NULL, keyPassphrase = '' WHERE keyId = :keyId")
    suspend fun clearKeyReference(keyId: Long)

    @Query(
        "SELECT keyId AS keyId, COUNT(*) AS count FROM host_profiles " +
            "WHERE keyId IS NOT NULL GROUP BY keyId"
    )
    fun observeKeyUsage(): Flow<List<KeyUsageCount>>
}

data class KeyUsageCount(val keyId: Long, val count: Int)
