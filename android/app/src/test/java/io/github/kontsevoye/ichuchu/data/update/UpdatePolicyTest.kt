package io.github.kontsevoye.ichuchu.data.update

import org.junit.Assert.*
import org.junit.Test

internal fun identity(code: Long = 1000001) =
    UpdateIdentity(
        "io.github.kontsevoye.ichuchu",
        code,
        "0.0.1",
        34,
        listOf("arm64-v8a"),
        setOf("a".repeat(64)),
    )

internal val config = UpdateConfig(true, "kontsevoye/ichuchu", "github")

internal fun manifest() =
    UpdateManifest(
        1,
        "github",
        "stable",
        identity().packageName,
        1000002,
        "0.0.2",
        24,
        "b".repeat(40),
        "a".repeat(64),
        listOf(UpdateArtifact("arm64-v8a", "ichuchu.apk", 10, "c".repeat(64))),
    )

class UpdatePolicyTest {
    @Test
    fun sharedVersionEncodingHasTheSameBoundariesAsTheReleasePipeline() {
        assertEquals(1000001, UpdatePolicy.versionCode("0.0.1"))
        assertEquals(1001000, UpdatePolicy.versionCode("0.1.0"))
        assertEquals(2000000, UpdatePolicy.versionCode("1.0.0"))
        assertEquals(2100000000, UpdatePolicy.versionCode("2099.0.0"))
        for (name in
            listOf(
                "01.0.0",
                "0.0.1000",
                "0.1000.0",
                "0.1.0-beta",
                "2099.0.1",
                "9999999999999999999999.0.0",
            )) assertThrows(UpdateException::class.java) { UpdatePolicy.versionCode(name) }
    }

    @Test
    fun metadataIsBoundToTheApplicationDistributionAndCurrentSigner() {
        val good = manifest()
        assertEquals(good.artifacts.single(), UpdatePolicy.validate(good, identity(), config))
        val invalid =
            listOf(
                good.copy(schemaVersion = 2),
                good.copy(channel = "preview"),
                good.copy(distribution = "other"),
                good.copy(packageName = "other.app"),
                good.copy(versionCode = 2),
                good.copy(minSdk = 35),
                good.copy(commit = "not-a-commit"),
                good.copy(certificateSha256 = "d".repeat(64)),
                good.copy(artifacts = emptyList()),
                good.copy(artifacts = good.artifacts + good.artifacts),
                good.copy(artifacts = listOf(good.artifacts.single().copy(name = "app.aab"))),
                good.copy(
                    artifacts = listOf(good.artifacts.single().copy(size = MAX_UPDATE_BYTES + 1))
                ),
                good.copy(artifacts = listOf(good.artifacts.single().copy(sha256 = "bad"))),
            )
        invalid.forEach {
            assertThrows(UpdateException::class.java) {
                UpdatePolicy.validate(it, identity(), config)
            }
        }
        assertThrows(UpdateException::class.java) {
            UpdatePolicy.validate(good, identity().copy(abis = listOf("x86_64")), config)
        }
        assertThrows(UpdateException::class.java) {
            UpdatePolicy.validate(good, identity().copy(certificates = emptySet()), config)
        }
    }
}
