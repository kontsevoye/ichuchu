package io.github.kontsevoye.ichuchu.ui.components

import androidx.compose.foundation.lazy.LazyListState
import io.github.kontsevoye.ichuchu.model.HostProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostListReorderStateTest {
    private fun host(id: Long) =
        HostProfile(id = id, name = "Host $id", host = "fixture", username = "fixture")

    @Test
    fun failedSaveRollsBackThePreview() = runBlocking {
        val original = listOf(host(1), host(2))
        val state = HostListReorderState(LazyListState(), 0)
        state.sync(original)
        state.move(1, 1) { requested ->
            assertEquals(listOf(2L, 1L), requested)
            false
        }
        assertEquals(original, state.hosts)
    }

    @Test
    fun profilesAddedDuringSaveAreStillDisplayed() = runBlocking {
        val state = HostListReorderState(LazyListState(), 0)
        state.sync(listOf(host(1), host(2)))
        val latest = listOf(host(2), host(1), host(3))
        state.move(1, 1) {
            state.sync(latest)
            true
        }
        assertEquals(latest, state.hosts)
    }
}
