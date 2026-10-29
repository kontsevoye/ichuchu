package io.github.kontsevoye.ichuchu.data.update

import io.github.kontsevoye.ichuchu.data.update.ApkVerifier.Companion.hex
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout

class UpdateDownloader(private val http: UpdateHttp = UpdateHttp()) {
    suspend fun download(
        candidate: UpdateCandidate,
        part: File,
        ready: File,
        progress: (Long) -> Unit,
    ) =
        withTimeout(180_000) {
            val job = currentCoroutineContext().job
            try {
                http.get(candidate.downloadUrl, mapOf("Accept" to "application/octet-stream")) {
                    response ->
                    try {
                        if (!job.isActive) throw CancellationException()
                        if (!response.isSuccessful)
                            throw UpdateException(
                                UpdateFailure.Network,
                                "APK download returned HTTP ${response.code}",
                            )
                        val expected = candidate.artifact.size
                        if (
                            response.body.contentLength() >= 0 &&
                                response.body.contentLength() != expected
                        )
                            throw UpdateException(
                                UpdateFailure.InvalidApk,
                                "APK download length changed",
                            )
                        var total = 0L
                        val digest = MessageDigest.getInstance("SHA-256")
                        response.body.byteStream().use { input ->
                            FileOutputStream(part).use { output ->
                                val bytes = ByteArray(32 * 1024)
                                while (true) {
                                    if (!job.isActive) throw CancellationException()
                                    val count = input.read(bytes)
                                    if (count < 0) break
                                    total += count
                                    if (total > expected || total > MAX_UPDATE_BYTES)
                                        throw UpdateException(
                                            UpdateFailure.InvalidApk,
                                            "APK exceeds its advertised size",
                                        )
                                    digest.update(bytes, 0, count)
                                    output.write(bytes, 0, count)
                                    progress(total)
                                }
                                output.fd.sync()
                            }
                        }
                        if (total != expected || digest.digest().hex() != candidate.artifact.sha256)
                            throw UpdateException(
                                UpdateFailure.InvalidApk,
                                "APK is truncated or has the wrong SHA-256",
                            )
                        if (!job.isActive) throw CancellationException()
                        if (!part.renameTo(ready))
                            throw UpdateException(
                                UpdateFailure.InvalidApk,
                                "Cannot finalize downloaded APK",
                            )
                    } catch (error: Exception) {
                        part.delete()
                        ready.delete()
                        throw error
                    }
                }
            } finally {
                part.delete()
            }
        }
}
