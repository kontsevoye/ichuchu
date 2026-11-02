package io.github.kontsevoye.ichuchu.service.agents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class StagedAgentFile(val file: File, val name: String, val mimeType: String) : Closeable {
    override fun close() {
        file.delete()
    }
}

/** A bounded private temporary copy needs no long-lived document grant or broad storage access. */
suspend fun stageAgentFile(context: Context, uri: Uri): StagedAgentFile =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        resolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        name = cursor.getString(nameIndex).orEmpty().ifBlank { "file" }
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        require(cursor.getLong(sizeIndex) <= AGENT_ATTACHMENT_LIMIT) {
                            "Files must be at most 20 MiB"
                        }
                    }
                }
            }
        name = name.replace(Regex("[\\p{Cc}\\p{Cf}]"), "_").take(160)
        val mime =
            resolver.getType(uri).orEmpty().takeIf {
                it.matches(Regex("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+"))
            } ?: "application/octet-stream"
        val directory = File(context.cacheDir, "agent-uploads").also { it.mkdirs() }
        // Only this feature's abandoned scratch copies; successful/failed attempts delete
        // immediately.
        directory
            .listFiles()
            ?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }
            ?.forEach { it.delete() }
        val file = File.createTempFile("attachment-", ".upload", directory)
        try {
            requireNotNull(resolver.openInputStream(uri)) { "Could not open $name" }
                .use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= AGENT_ATTACHMENT_LIMIT) {
                                "Files must be at most 20 MiB"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            StagedAgentFile(file, name, mime)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }
