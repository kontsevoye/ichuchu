package io.github.kontsevoye.ichuchu.ui.screens.Files

import java.io.File

/** Stages remote content without allowing a server to choose a local path or reuse a URI grant. */
inline fun stageRemoteFile(cacheDir: File, remoteName: String, readBytes: () -> ByteArray): File {
    require(remoteName.isNotBlank() && remoteName != "." && remoteName != "..") {
        "Invalid remote file name"
    }
    require(remoteName.none { it == '/' || it == '\\' || it.isISOControl() }) {
        "Invalid remote file name"
    }
    val root = cacheDir.canonicalFile
    val directory = File(root, "remote-files")
    check(directory.mkdirs() || directory.isDirectory) { "Cannot create file staging directory" }
    check(directory.canonicalFile.parentFile == root) { "Invalid file staging directory" }
    val extension =
        remoteName.substringAfterLast('.', "").takeIf {
            it.length in 1..16 && it.all { ch -> ch.isLetterOrDigit() && ch.code < 128 }
        }
    val file = File.createTempFile("remote-", extension?.let { ".$it" } ?: ".bin", directory)
    try {
        check(file.canonicalFile.parentFile == directory.canonicalFile) {
            "Invalid staged file path"
        }
        file.outputStream().use { it.write(readBytes()) }
        return file
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}
