package io.github.kontsevoye.ichuchu.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Local-only state in the application's encrypted database; excluded from portable backups. */
@Entity(tableName = "agent_states", indices = [Index("hostId")])
data class AgentStateEntity(
    @PrimaryKey val cacheKey: String,
    val hostId: Long,
    val payload: String,
    val updatedAt: Long,
    val protected: Boolean,
)

@Dao
interface AgentStateDao {
    @Query("SELECT * FROM agent_states WHERE cacheKey = :key")
    suspend fun get(key: String): AgentStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: AgentStateEntity)

    @Query("DELETE FROM agent_states WHERE hostId = :hostId")
    suspend fun clearHost(hostId: Long)

    @Query("DELETE FROM agent_states WHERE hostId NOT IN (SELECT id FROM host_profiles)")
    suspend fun clearOrphans()

    @Query(
        "DELETE FROM agent_states WHERE hostId = :hostId AND protected = 0 AND cacheKey LIKE '%:chat:%' AND cacheKey NOT IN (SELECT cacheKey FROM agent_states WHERE hostId = :hostId AND cacheKey LIKE '%:chat:%' ORDER BY updatedAt DESC LIMIT 20)"
    )
    suspend fun trim(hostId: Long)

    @Query(
        "SELECT * FROM agent_states WHERE hostId = :hostId AND protected = 0 ORDER BY updatedAt ASC"
    )
    suspend fun evictable(hostId: Long): List<AgentStateEntity>

    @Query(
        "SELECT COALESCE(SUM(length(CAST(payload AS BLOB))), 0) FROM agent_states WHERE hostId = :hostId"
    )
    suspend fun bytes(hostId: Long): Long

    @Query("DELETE FROM agent_states WHERE cacheKey = :key AND protected = 0")
    suspend fun evict(key: String)
}
