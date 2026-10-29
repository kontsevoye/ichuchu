package io.github.kontsevoye.ichuchu.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Every hop is checked before a request, including GitHub's signed release-CDN redirects. */
class UpdateHttp(
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES)
            .build(),
    private val allowed: (HttpUrl) -> Boolean = ::isGitHubUrl,
) {
    suspend fun <T> get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        read: (Response) -> T,
    ): T {
        var current = url.toHttpUrl()
        repeat(6) {
            if (!allowed(current))
                throw UpdateException(UpdateFailure.Network, "Untrusted update URL")
            val result =
                execute(
                    Request.Builder()
                        .url(current)
                        .apply {
                            header("User-Agent", "ichuchu-updater")
                            headers.forEach { (key, value) -> header(key, value) }
                        }
                        .build()
                ) { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        val next =
                            response.header("Location")?.let { current.resolve(it) }
                                ?: throw UpdateException(
                                    UpdateFailure.Network,
                                    "Invalid update redirect",
                                )
                        Reply<T>(next, null)
                    } else Reply(null, read(response))
                }
            if (result.next == null) return result.value!!
            current = result.next
        }
        throw UpdateException(UpdateFailure.Network, "Too many update redirects")
    }

    private data class Reply<T>(val next: HttpUrl?, val value: T?)

    private suspend fun <T> execute(request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!continuation.isActive) return
                            try {
                                val result = read(response)
                                if (continuation.isActive) continuation.resume(result)
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    }
                }
            )
        }

    companion object {
        fun isGitHubUrl(url: HttpUrl): Boolean =
            url.isHttps &&
                url.port == 443 &&
                url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.fragment == null &&
                url.host in
                    setOf(
                        "api.github.com",
                        "github.com",
                        "release-assets.githubusercontent.com",
                        "objects.githubusercontent.com",
                        "github-releases.githubusercontent.com",
                    )
    }
}
