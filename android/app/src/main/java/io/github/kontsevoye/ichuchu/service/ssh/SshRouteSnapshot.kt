package io.github.kontsevoye.ichuchu.service.ssh

import androidx.room.withTransaction
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.SshKey

/** A live tab may reconnect only while its resolved configuration is unchanged. */
class SshRouteSnapshot(
    private val database: AppDatabase,
    private val target: HostProfile,
    private val hops: List<SshJumpHost>,
    private val keys: List<SshKey>,
) {
    suspend fun requireCurrent() =
        database.withTransaction {
            val profiles = database.hostProfileDao().getAll()
            val currentTarget = profiles.firstOrNull { it.id == target.id }
            val currentHops =
                runCatching {
                        currentTarget?.let {
                            SshJumpHost.resolve(it, profiles, database.sshKeyDao().getAll())
                        }
                    }
                    .getOrNull()
            check(
                currentTarget?.copy(name = "", sortOrder = 0L) ==
                    target.copy(name = "", sortOrder = 0L) &&
                    currentHops?.map { endpoint(it.profile) } ==
                        hops.map { endpoint(it.profile) } &&
                    keys.all { database.sshKeyDao().getById(it.id) == it }
            ) {
                "SSH route or credentials changed. Close this tab and open a new connection."
            }
        }

    private fun endpoint(profile: HostProfile): HostProfile =
        profile.copy(
            name = "",
            sortOrder = 0L,
            jumpHostIds = emptyList(),
            postConnectCommand = null,
            multiplexer = null,
        )
}
