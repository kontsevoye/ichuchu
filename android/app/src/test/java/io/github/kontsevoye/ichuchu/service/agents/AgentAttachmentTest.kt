package io.github.kontsevoye.ichuchu.service.agents

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAttachmentTest {
    @Test
    fun uploadScriptPreservesBinaryBytesAndCreatesPrivateUniqueFiles() {
        val data = ByteArray(256 * 1024) { (it % 251).toByte() }
        for (shell in listOf("/bin/sh", "/bin/zsh").filter { java.io.File(it).exists() }) {
            val root = Files.createTempDirectory("ichuchu-upload-test").toFile()
            try {
                val paths =
                    (1..2).map {
                        val process =
                            ProcessBuilder(
                                    shell,
                                    "-c",
                                    DeckCommands.uploadFile("sample.bin", data.size.toLong()),
                                )
                                .apply { environment()["TMPDIR"] = root.absolutePath }
                                .start()
                        process.outputStream.use { it.write(data) }
                        val path = process.inputStream.bufferedReader().readText().trim()
                        assertEquals(
                            process.errorStream.bufferedReader().readText(),
                            0,
                            process.waitFor(),
                        )
                        val file = java.io.File(path)
                        assertTrue(file.canonicalPath.startsWith(root.canonicalPath + "/"))
                        assertArrayEquals(data, file.readBytes())
                        assertEquals(
                            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                            Files.getPosixFilePermissions(file.toPath()),
                        )
                        assertEquals(
                            setOf(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE,
                            ),
                            Files.getPosixFilePermissions(file.parentFile.toPath()),
                        )
                        path
                    }
                assertNotEquals(paths[0], paths[1])
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun incompleteUploadsLeaveNoStagedFileAndNamesCannotEscape() {
        val root = Files.createTempDirectory("ichuchu-short-upload-test").toFile()
        try {
            val process =
                ProcessBuilder("/bin/sh", "-c", DeckCommands.uploadFile("sample.bin", 100))
                    .apply { environment()["TMPDIR"] = root.absolutePath }
                    .start()
            process.outputStream.use { it.write(byteArrayOf(1, 2, 3)) }
            assertNotEquals(0, process.waitFor())
            assertEquals(0, root.listFiles()!!.size)
            for (bad in listOf("../escape", "a/b", "quote'", "bad\nname", "$(id)")) {
                assertThrows(IllegalArgumentException::class.java) {
                    DeckCommands.uploadFile(bad, 10)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                DeckCommands.uploadFile("large.bin", AGENT_ATTACHMENT_LIMIT + 1)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun attachmentsAreExplicitHostPathsWithAnAttachmentOnlyPrompt() {
        val file =
            AgentAttachment(
                "id",
                "Screenshot 世界.png",
                "image/png",
                8,
                "/tmp/ichuchu-attachment.a/file.png",
            )
        val message = agentAttachmentMessage("", listOf(file))
        assertTrue(message.contains(file.remotePath))
        assertTrue(message.contains("image-viewing tool"))
        assertEquals(file, AgentAttachment.parse(file.json()))
        assertEquals("plain", agentAttachmentMessage("plain", emptyList()))
    }
}
