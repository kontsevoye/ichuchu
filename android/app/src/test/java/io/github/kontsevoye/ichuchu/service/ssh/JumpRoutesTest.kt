package io.github.kontsevoye.ichuchu.service.ssh

import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.JumpRoutes
import io.github.kontsevoye.ichuchu.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpRoutesTest {
    private val a = HostProfile(id = 1, name = "A", host = "a.example", username = "alice")
    private val b =
        HostProfile(
            id = 2,
            name = "B",
            host = "b.internal",
            username = "bob",
            jumpHostIds = listOf(999),
        )
    private val target =
        HostProfile(
            id = 3,
            name = "target",
            host = "10.0.0.5",
            username = "root",
            jumpHostIds = listOf(1, 2),
        )

    @Test
    fun explicitOrderDoesNotExpandTheHopsOwnRoute() {
        assertEquals(listOf(a, b), JumpRoutes.validate(target, listOf(a, b, target)))
        assertEquals(
            listOf(b, a),
            JumpRoutes.validate(target.copy(jumpHostIds = listOf(2, 1)), listOf(a, b)),
        )
    }

    @Test
    fun malformedRoutesAreRejected() {
        val invalid =
            listOf(
                target.copy(jumpHostIds = listOf(1, 1)),
                target.copy(jumpHostIds = listOf(3)),
                target.copy(jumpHostIds = listOf(88)),
                target.copy(jumpHostIds = listOf(1, 2, 3, 4)),
                target.copy(transport = Transport.Mosh),
                target.copy(host = a.host, username = a.username),
            )
        invalid.forEach { profile ->
            assertTrue(runCatching { JumpRoutes.validate(profile, listOf(a, b, target)) }.isFailure)
        }
        assertTrue(
            runCatching {
                    JumpRoutes.validate(target, listOf(a.copy(transport = Transport.LocalShell), b))
                }
                .isFailure
        )
    }

    @Test
    fun trustIncludesRouteButDoesNotDependOnNamesOrImportedIds() {
        val path = listOf(SshJumpHost(a), SshJumpHost(b))
        fun trust(hops: List<SshJumpHost>) =
            sshTrustHost(target.host, target.port, target.username, hops)
        assertEquals(target.host, trust(emptyList()))
        assertEquals(
            trust(path),
            trust(listOf(SshJumpHost(a.copy(name = "renamed", id = 999)), SshJumpHost(b))),
        )
        assertNotEquals(trust(path), trust(path.reversed()))
        assertNotEquals(
            trust(path),
            trust(listOf(SshJumpHost(a.copy(host = "other.example")), SshJumpHost(b))),
        )
    }
}
