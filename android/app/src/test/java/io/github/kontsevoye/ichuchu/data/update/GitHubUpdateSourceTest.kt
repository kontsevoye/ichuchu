package io.github.kontsevoye.ichuchu.data.update

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubUpdateSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var store: UpdateStore
    private lateinit var http: UpdateHttp
    private lateinit var source: GitHubUpdateSource
    private val json = Json

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("ichuchu_updates", 0).edit().clear().commit()
        store = UpdateStore(app)
        server = MockWebServer().apply { start() }
        http = UpdateHttp(allowed = { it.host == "localhost" && it.port == server.port })
        val base = server.url("/").toString().removeSuffix("/")
        source = GitHubUpdateSource(config, store, http, base, base)
    }

    @After
    fun cleanup() {
        server.shutdown()
    }

    private fun release(
        metadata: UpdateManifest = manifest(),
        draft: Boolean = false,
        prerelease: Boolean = false,
        includeMetadata: Boolean = true,
    ) =
        buildJsonObject {
                put("tag_name", "v${metadata.versionName}")
                put("draft", draft)
                put("prerelease", prerelease)
                put("body", "plain release notes")
                put(
                    "assets",
                    buildJsonArray {
                        if (includeMetadata)
                            add(
                                buildJsonObject {
                                    put("name", "update.json")
                                    put("size", json.encodeToString(metadata).toByteArray().size)
                                    put("state", "uploaded")
                                    put(
                                        "browser_download_url",
                                        server
                                            .url(
                                                "/${config.repository}/releases/download/v${metadata.versionName}/update.json"
                                            )
                                            .toString(),
                                    )
                                }
                            )
                        add(
                            buildJsonObject {
                                put("name", "ichuchu.apk")
                                put("size", metadata.artifacts.single().size)
                                put("state", "uploaded")
                                put(
                                    "browser_download_url",
                                    server
                                        .url(
                                            "/${config.repository}/releases/download/v${metadata.versionName}/ichuchu.apk"
                                        )
                                        .toString(),
                                )
                            }
                        )
                    },
                )
            }
            .toString()

    private fun enqueue(metadata: UpdateManifest = manifest()) {
        server.enqueue(MockResponse().setBody(release(metadata)).addHeader("ETag", "\"release\""))
        server.enqueue(MockResponse().setBody(json.encodeToString(metadata)))
    }

    @Test
    fun newerSameAndOlderCandidatesUseTheInstalledCode() =
        runBlocking<Unit> {
            enqueue()
            assertEquals(1000002, source.latest(identity())!!.manifest.versionCode)
            enqueue()
            assertNull(source.latest(identity(1000002)))
            enqueue()
            assertNull(source.latest(identity(1000003)))
        }

    @Test
    fun etag304UsesValidatedCachedReleaseWithoutLosingItsCandidate() =
        runBlocking<Unit> {
            enqueue()
            val first = source.latest(identity())
            server.takeRequest()
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(304))
            server.enqueue(MockResponse().setBody(json.encodeToString(manifest())))
            assertEquals(first, source.latest(identity()))
            assertEquals("\"release\"", server.takeRequest().getHeader("If-None-Match"))
        }

    @Test
    fun invalidUnsupportedAndUnpublishedMetadataAreDistinctFromUpToDate() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody(release(draft = true)))
            assertThrows(UpdateException::class.java) { runBlocking { source.latest(identity()) } }
            server.enqueue(MockResponse().setBody(release(prerelease = true)))
            assertThrows(UpdateException::class.java) { runBlocking { source.latest(identity()) } }
            server.enqueue(MockResponse().setBody(release(includeMetadata = false)))
            assertEquals(
                UpdateFailure.UnsupportedRelease,
                assertThrows(UpdateException::class.java) {
                        runBlocking { source.latest(identity()) }
                    }
                    .failure,
            )
            server.enqueue(MockResponse().setResponseCode(404))
            assertThrows(UpdateException::class.java) { runBlocking { source.latest(identity()) } }
            server.enqueue(MockResponse().setBody("malformed"))
            assertThrows(UpdateException::class.java) { runBlocking { source.latest(identity()) } }
        }

    @Test
    fun rateLimitBackoffPreventsRepeatedRequests() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "60"))
            assertEquals(
                UpdateFailure.RateLimited,
                assertThrows(UpdateException::class.java) {
                        runBlocking { source.latest(identity()) }
                    }
                    .failure,
            )
            assertTrue(store.retryAt > System.currentTimeMillis())
            assertThrows(UpdateException::class.java) { runBlocking { source.latest(identity()) } }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun metadataSizeAndWrongDistributionAreRejected() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setBody("x".repeat(MAX_METADATA_BYTES.toInt() + 1)))
            assertThrows(UpdateException::class.java) { runBlocking { source.latest(identity()) } }
            enqueue(manifest().copy(distribution = "other"))
            assertThrows(UpdateException::class.java) { runBlocking { source.latest(identity()) } }
        }

    @Test
    fun redirectsAndProductionUrlPolicyRejectDowngradesAndUnexpectedHosts() =
        runBlocking<Unit> {
            for (url in
                listOf(
                    "http://github.com/file",
                    "https://github.com:444/file",
                    "https://github.com.evil.invalid/file",
                    "https://user:password@github.com/file",
                    "https://github.com/file#fragment",
                )) assertFalse(UpdateHttp.isGitHubUrl(url.toHttpUrl()))
            assertTrue(
                UpdateHttp.isGitHubUrl(
                    "https://release-assets.githubusercontent.com/file?token=redacted".toHttpUrl()
                )
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "https://evil.invalid/app.apk")
            )
            assertThrows(UpdateException::class.java) {
                runBlocking { http.get(server.url("/start").toString()) { "ignored" } }
            }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun downloadRequiresExactBytesAndRemovesPartialFilesOnFailure() =
        runBlocking<Unit> {
            val bytes = "signed-apk-fixture".toByteArray()
            val digest =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
            val artifact =
                manifest().artifacts.single().copy(size = bytes.size.toLong(), sha256 = digest)
            val candidate =
                UpdateCandidate(
                    manifest().copy(artifacts = listOf(artifact)),
                    artifact,
                    server.url("/apk").toString(),
                    "",
                    "",
                )
            val dir =
                File.createTempFile("updater", "test").apply {
                    delete()
                    mkdirs()
                }
            try {
                val part = File(dir, "update.part")
                val ready = File(dir, "update.apk")
                server.enqueue(MockResponse().setBody(String(bytes)))
                UpdateDownloader(http).download(candidate, part, ready) {}
                assertArrayEquals(bytes, ready.readBytes())
                assertFalse(part.exists())
                ready.delete()
                for (response in
                    listOf(
                        MockResponse().setBody("x".repeat(bytes.size)),
                        MockResponse().setBody("truncated"),
                        MockResponse().setChunkedBody("x".repeat(bytes.size + 1), 4),
                    )) {
                    server.enqueue(response)
                    assertThrows(UpdateException::class.java) {
                        runBlocking { UpdateDownloader(http).download(candidate, part, ready) {} }
                    }
                    assertFalse(part.exists())
                    assertFalse(ready.exists())
                }
            } finally {
                dir.deleteRecursively()
            }
        }
}
