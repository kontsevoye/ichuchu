package io.github.kontsevoye.ichuchu.ui.screens.agents

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** User intent survives remeasure: new content or a smaller viewport cannot unpin the chat. */
internal class AgentChatScroll(private val list: LazyListState) : NestedScrollConnection {
    var following by mutableStateOf(true)
        private set

    private var userScrolling = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) {
            userScrolling = true
            following = false
        }
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (userScrolling && (consumed.y != 0f || available.y != 0f)) {
            following = !list.canScrollForward
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (userScrolling) {
            userScrolling = false
            following = !list.canScrollForward
        }
        return Velocity.Zero
    }

    suspend fun jumpToLatest() {
        following = true
        followEnd()
    }

    suspend fun followEnd() {
        try {
            val lastIndex = list.layoutInfo.totalItemsCount - 1
            if (lastIndex < 0) return
            if (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
                list.scrollToItem(lastIndex)
            }
            val layout = list.layoutInfo
            val last =
                layout.visibleItemsInfo.lastOrNull()?.takeIf { it.index == lastIndex } ?: return
            // scrollToItem aligns a row's START. Long/streaming replies need their END aligned.
            val remaining =
                last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
            if (remaining > 0) list.scrollBy(remaining.toFloat())
        } catch (e: CancellationException) {
            // User input may interrupt a scroll mutation without cancelling this layout observer.
            currentCoroutineContext().ensureActive()
        }
    }
}

@Composable
internal fun rememberAgentChatScroll(
    list: LazyListState,
    sessionId: String?,
    enabled: Boolean,
): AgentChatScroll {
    val scroll = remember(list, sessionId) { AgentChatScroll(list) }
    LaunchedEffect(scroll, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { Triple(scroll.following, list.layoutInfo, list.isScrollInProgress) }
            .collect { (following, layout, moving) ->
                if (following && !moving && layout.totalItemsCount > 0 && list.canScrollForward) {
                    // IME insets can publish layoutInfo during a measure pass. Scroll only after
                    // that pass, and let a gesture begun in the meantime cancel following.
                    withFrameNanos {}
                    if (scroll.following && !list.isScrollInProgress) scroll.followEnd()
                }
            }
    }
    return scroll
}
