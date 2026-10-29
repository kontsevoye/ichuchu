package io.github.kontsevoye.ichuchu.ui.screens.Files

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteFileStagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun rejectsTraversalBeforeReadingOrWriting() {
        val cache = temporary.newFolder("cache")
        val database = File(temporary.newFolder("databases"), "ichuchu.db")
        database.writeText("original")
        for (name in
            listOf(
                "../databases/ichuchu.db",
                "/absolute",
                "a/b",
                "a\\b",
                ".",
                "..",
                "",
                " ",
                "a\u0000b",
                "a\nb",
            )) {
            var read = false
            assertThrows(IllegalArgumentException::class.java) {
                stageRemoteFile(cache, name) {
                    read = true
                    "attacker".toByteArray()
                }
            }
            assertFalse(read)
            assertEquals("original", database.readText())
        }
    }

    @Test
    fun isolatesFilesWithTheSameRemoteNameAndPreservesExtension() {
        val cache = temporary.newFolder("cache")
        val first = stageRemoteFile(cache, "отчёт.txt") { "first".toByteArray() }
        val second = stageRemoteFile(cache, "отчёт.txt") { "second".toByteArray() }
        assertNotEquals(first, second)
        assertEquals(File(cache, "remote-files").canonicalFile, first.canonicalFile.parentFile)
        assertTrue(first.name.endsWith(".txt"))
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
    }

    @Test
    fun removesPartialFileWhenReadingFails() {
        val cache = temporary.newFolder("cache")
        assertThrows(IllegalStateException::class.java) {
            stageRemoteFile(cache, "file.txt") { error("synthetic read failure") }
        }
        assertTrue(File(cache, "remote-files").listFiles()!!.isEmpty())
    }

    @Test
    fun rejectsStagingDirectorySymlinkOutsideCache() {
        val cache = temporary.newFolder("cache")
        val outside = temporary.newFolder("outside")
        java.nio.file.Files.createSymbolicLink(
            File(cache, "remote-files").toPath(),
            outside.toPath(),
        )
        assertThrows(IllegalStateException::class.java) {
            stageRemoteFile(cache, "file.txt") { "secret".toByteArray() }
        }
        assertTrue(outside.listFiles()!!.isEmpty())
    }
}
