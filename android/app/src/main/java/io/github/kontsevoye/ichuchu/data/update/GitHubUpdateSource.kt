package io.github.kontsevoye.ichuchu.data.update

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

class GitHubUpdateSource(
    private val config: UpdateConfig,
    private val store: UpdateStore,
    private val http: UpdateHttp = UpdateHttp(),
    private val api: String = "https://api.github.com",
    private val web: String = "https://github.com",
    private val timeoutMillis: Long = 45_000,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(identity: UpdateIdentity): UpdateCandidate? =
        withContext(Dispatchers.IO) {
            withTimeout(timeoutMillis) {
                if (System.currentTimeMillis() < store.retryAt)
                    throw UpdateException(
                        UpdateFailure.RateLimited,
                        "GitHub rate limit: retry later",
                        store.retryAt,
                    )
                val url = "$api/repos/${config.repository}/releases/latest"
                val headers = buildMap {
                    put("Accept", "application/vnd.github+json")
                    store.etag?.let { put("If-None-Match", it) }
                }
                val releaseText =
                    http.get(url, headers) { response ->
                        if (response.code == 304)
                            store.releaseJson
                                ?: throw UpdateException(
                                    UpdateFailure.Network,
                                    "Missing cached release metadata",
                                )
                        else {
                            requireSuccess(response)
                            val text = readMetadata(response)
                            store.releaseJson = text
                            store.etag = response.header("ETag")
                            text
                        }
                    }
                val release = decode<Release>(releaseText)
                if (
                    release.draft ||
                        release.prerelease ||
                        !Regex("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
                            .matches(release.tag)
                )
                    throw UpdateException(
                        UpdateFailure.UnsupportedRelease,
                        "Latest release is not a stable release",
                    )
                val manifestAsset =
                    release.assets.singleOrNull {
                        it.name == "update.json" && it.state == "uploaded"
                    }
                        ?: throw UpdateException(
                            UpdateFailure.UnsupportedRelease,
                            "This release has no in-app update metadata. Open releases to install manually.",
                        )
                if (manifestAsset.size !in 1..MAX_METADATA_BYTES)
                    invalid("Update manifest is too large")
                val manifestText =
                    http.get(assetUrl(release.tag, manifestAsset)) { response ->
                        requireSuccess(response)
                        readMetadata(response)
                    }
                val manifest = decode<UpdateManifest>(manifestText)
                if (release.tag != "v${manifest.versionName}")
                    invalid("Release tag and manifest version differ")
                val artifact = UpdatePolicy.validate(manifest, identity, config)
                val asset =
                    release.assets.singleOrNull {
                        it.name == artifact.name && it.state == "uploaded"
                    } ?: invalid("Release APK is missing or ambiguous")
                if (
                    asset.size != artifact.size ||
                        (asset.digest != null && asset.digest != "sha256:${artifact.sha256}")
                )
                    invalid("Release APK and manifest disagree")
                if (manifest.versionCode <= identity.versionCode) return@withTimeout null
                UpdateCandidate(
                    manifest,
                    artifact,
                    assetUrl(release.tag, asset),
                    "$web/${config.repository}/releases/tag/${release.tag}",
                    release.body.orEmpty().take(16_000),
                )
            }
        }

    private fun assetUrl(tag: String, asset: Asset): String {
        val expected = "$web/${config.repository}/releases/download/$tag/${asset.name}".toHttpUrl()
        if (asset.url.toHttpUrl() != expected)
            invalid("Release asset URL does not match this repository")
        return expected.toString()
    }

    private fun requireSuccess(response: Response) {
        if (response.code == 403 || response.code == 429) {
            val now = System.currentTimeMillis()
            val retry =
                response
                    .header("Retry-After")
                    ?.toLongOrNull()
                    ?.coerceIn(1, 86_400)
                    ?.times(1000)
                    ?.plus(now)
                    ?: response
                        .header("X-RateLimit-Reset")
                        ?.toLongOrNull()
                        ?.times(1000)
                        ?.coerceIn(now + 60_000, now + 86_400_000)
                    ?: now + 3_600_000
            store.retryAt = retry
            throw UpdateException(
                UpdateFailure.RateLimited,
                "GitHub rate limit: retry later",
                retry,
            )
        }
        if (response.code == 404)
            throw UpdateException(
                UpdateFailure.UnsupportedRelease,
                "No supported release is published yet",
            )
        if (!response.isSuccessful)
            throw UpdateException(
                UpdateFailure.Network,
                "Update server returned HTTP ${response.code}",
            )
        store.retryAt = 0
    }

    private fun readMetadata(response: Response): String {
        if (response.body.contentLength() > MAX_METADATA_BYTES) invalid("Metadata is too large")
        val result = ByteArrayOutputStream()
        response.body.byteStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (result.size() + count > MAX_METADATA_BYTES) invalid("Metadata is too large")
                result.write(buffer, 0, count)
            }
        }
        return result.toString(Charsets.UTF_8.name())
    }

    private inline fun <reified T> decode(text: String): T =
        try {
            json.decodeFromString<T>(text)
        } catch (error: Exception) {
            invalid("Invalid update metadata")
        }

    private fun invalid(message: String): Nothing =
        throw UpdateException(UpdateFailure.InvalidMetadata, message)

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val body: String? = null,
        val assets: List<Asset>,
    )

    @Serializable
    private data class Asset(
        val name: String,
        val size: Long,
        val state: String,
        @SerialName("browser_download_url") val url: String,
        val digest: String? = null,
    )
}
