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
    var userScrollGeneration = 0
        private set

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) {
            userScrollGeneration++
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

    suspend fun shiftForViewport(delta: Int) {
        try {
            list.scrollBy(delta.toFloat())
        } catch (e: CancellationException) {
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
        var height = 0
        var width = 0
        snapshotFlow { Triple(scroll.following, list.layoutInfo, list.isScrollInProgress) }
            .collect { (following, layout, moving) ->
                val previousHeight = height
                val previousWidth = width
                height = layout.viewportSize.height
                width = layout.viewportSize.width
                if (moving || layout.totalItemsCount == 0) return@collect
                if (following && list.canScrollForward) {
                    // IME insets can publish layoutInfo during a measure pass. Scroll only after
                    // that pass, and let a gesture begun in the meantime cancel following.
                    withFrameNanos {}
                    if (scroll.following && !list.isScrollInProgress) scroll.followEnd()
                } else if (
                    !following &&
                        previousHeight > 0 &&
                        previousHeight != height &&
                        previousWidth == width
                ) {
                    val gesture = scroll.userScrollGeneration
                    withFrameNanos {}
                    val current = list.layoutInfo.viewportSize
                    height = current.height
                    width = current.width
                    if (
                        !scroll.following &&
                            !list.isScrollInProgress &&
                            gesture == scroll.userScrollGeneration &&
                            previousWidth == width
                    ) {
                        // Keep the content point at the lower reading edge using the viewport
                        // delta, including IME, composer and Latest changes. New messages at
                        // the same viewport size must never move a reading chat.
                        scroll.shiftForViewport(previousHeight - height)
                    }
                }
            }
    }
    return scroll
}
