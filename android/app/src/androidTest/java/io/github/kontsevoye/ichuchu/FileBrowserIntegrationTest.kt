package io.github.kontsevoye.ichuchu

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.service.terminal.SessionStatus
import io.github.kontsevoye.ichuchu.service.terminal.TabSession
import io.github.kontsevoye.ichuchu.service.terminal.TabSpec
import io.github.kontsevoye.ichuchu.service.terminal.TerminalSessionRepository
import io.github.kontsevoye.ichuchu.ui.screens.Files.FileEntryType
import io.github.kontsevoye.ichuchu.ui.screens.Terminal.TerminalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses only in-memory SFTP files and synthetic SSH credentials. */
class FileBrowserIntegrationTest {
    private suspend fun withFiles(test: suspend (TerminalViewModel, TabSession) -> Unit) =
        coroutineScope {
            assumeTrue(InstrumentationRegistry.getArguments().getString("sshJumpFixture") == "true")
            val app =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as Application
            val store = ViewModelStore()
            val vm =
                withContext(Dispatchers.Main) {
                    ViewModelProvider(
                        store,
                        ViewModelProvider.AndroidViewModelFactory.getInstance(app),
                    )[TerminalViewModel::class.java]
                }
            val opened = mutableListOf<TabSession>()
            val prompts = launch {
                vm.hostKeyPrompt.filterNotNull().collect { vm.onHostKeyDecision(true) }
            }
            try {
                val tab = withContext(Dispatchers.Main) { vm.openTab(spec())!! }
                opened += tab
                withTimeout(15_000) {
                    tab.sessionState.first { it.status == SessionStatus.Connected }
                }
                withContext(Dispatchers.Main) { vm.openPath("/") }
                withTimeout(10_000) {
                    vm.fileBrowserState.first { !it.isLoading && it.entries.isNotEmpty() }
                }
                test(vm, tab)
            } finally {
                withContext(Dispatchers.Main) {
                    vm.tabs.value.filter { it.spec.port == 22227 }.forEach { vm.closeTab(it.id) }
                    store.clear()
                }
                prompts.cancel()
            }
        }

    private fun spec() =
        TabSpec(host = "10.0.2.2", port = 22227, username = "fixture", password = "fixture-0")

    @Test
    fun listingPreservesTabbedNamesAndZeroByteSize() = runBlocking {
        withFiles { vm, _ ->
            val entries = vm.fileBrowserState.value.entries
            val tabbed =
                entries.firstOrNull { it.name == "tab\tname.txt" }
                    ?: error("Tabbed filename was corrupted: $entries")
            assertEquals("/tab\tname.txt", tabbed.path)
            assertEquals(FileEntryType.File, tabbed.type)
            assertEquals(0L, entries.single { it.name == "empty.txt" }.sizeBytes)
        }
    }

    @Test
    fun switchingTabsDuringUploadDoesNotRedirectChunks() = runBlocking {
        withFiles { vm, first ->
            val destination = withContext(Dispatchers.Main) { vm.uploadDestination()!! }
            val target = vm.beginUpload("qa-upload.bin", destination)
            vm.writeUploadChunk(target, byteArrayOf(1, 2))
            val second = withContext(Dispatchers.Main) { vm.openTab(spec())!! }
            withTimeout(15_000) {
                second.sessionState.first { it.status == SessionStatus.Connected }
            }
            vm.writeUploadChunk(target, byteArrayOf(3, 4))
            vm.finishUpload(target)
            val app =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as Application
            assertArrayEquals(
                byteArrayOf(1, 2, 3, 4),
                TerminalSessionRepository.getInstance(app)
                    .sftpReadFile(first.id, "/qa-upload.bin", 100),
            )
        }
    }
}
