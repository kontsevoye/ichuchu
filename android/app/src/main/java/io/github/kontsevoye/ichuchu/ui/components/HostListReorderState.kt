package io.github.kontsevoye.ichuchu.ui.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import io.github.kontsevoye.ichuchu.model.HostProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

/** Preview the gesture locally; write a single transaction when the user drops the host. */
internal class HostListReorderState(
    private val listState: LazyListState,
    private val headerCount: Int,
    private val onDragSwap: () -> Unit = {},
) {
    var hosts by mutableStateOf<List<HostProfile>>(emptyList())
        private set

    var draggingId by mutableStateOf<Long?>(null)
        private set

    var saving by mutableStateOf(false)
        private set

    private var sourceHosts = emptyList<HostProfile>()
    private var top by mutableStateOf(0f)
    private var height = 0

    fun sync(source: List<HostProfile>) {
        sourceHosts = source
        if (draggingId != null && hosts.map { it.id }.toSet() != source.map { it.id }.toSet()) {
            cancel()
        }
        if (draggingId == null && !saving) hosts = source
    }

    fun start(id: Long) {
        if (saving) return
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        draggingId = id
        top = item.offset.toFloat()
        height = item.size
    }

    fun drag(delta: Float) {
        if (draggingId == null) return
        top += delta
        movePastNeighbour()
    }

    fun cancel() {
        draggingId = null
        hosts = sourceHosts
    }

    suspend fun drop(save: suspend (List<Long>) -> Boolean) {
        if (draggingId == null) return
        draggingId = null
        persist(hosts.map { it.id }, save)
    }

    suspend fun move(id: Long, direction: Int, save: suspend (List<Long>) -> Boolean) {
        if (saving || draggingId != null) return
        val index = hosts.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0 || target !in hosts.indices) return
        val order = hosts.toMutableList().apply { add(target, removeAt(index)) }.map { it.id }
        persist(order, save)
    }

    private suspend fun persist(order: List<Long>, save: suspend (List<Long>) -> Boolean) {
        if (order == sourceHosts.map { it.id }) return
        val byId = hosts.associateBy { it.id }
        hosts = order.map { byId.getValue(it) }
        val sourceAtStart = sourceHosts
        saving = true
        try {
            val saved = save(order)
            if (!saved || sourceHosts != sourceAtStart) hosts = sourceHosts
        } catch (e: CancellationException) {
            hosts = sourceHosts
            throw e
        } finally {
            saving = false
        }
    }

    fun itemModifier(id: Long): Modifier =
        Modifier.zIndex(if (draggingId == id) 1f else 0f).graphicsLayer {
            translationY =
                if (draggingId == id) {
                    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                    top - (item?.offset ?: 0)
                } else {
                    0f
                }
            shadowElevation = if (draggingId == id) 8f else 0f
        }

    suspend fun scrollAtEdge(edge: Float) {
        val layout = listState.layoutInfo
        val center = top + height / 2f
        val delta =
            when {
                center < layout.viewportStartOffset + edge ->
                    ((center - layout.viewportStartOffset - edge) / 6f).coerceAtLeast(-20f)
                center > layout.viewportEndOffset - edge ->
                    ((center - layout.viewportEndOffset + edge) / 6f).coerceAtMost(20f)
                else -> 0f
            }
        if (delta != 0f) listState.scrollBy(delta)
        movePastNeighbour()
    }

    private fun movePastNeighbour() {
        val id = draggingId ?: return
        val index = hosts.indexOfFirst { it.id == id }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.key == id }) return
        // Wait for LazyColumn to lay out the last swap before interpreting neighbour positions.
        if (
            visible.any {
                it.key is Long &&
                    it.index != hosts.indexOfFirst { host -> host.id == it.key } + headerCount
            }
        ) {
            return
        }
        val center = top + height / 2f
        val neighbour =
            visible.firstOrNull { other ->
                val otherId = other.key as? Long ?: return@firstOrNull false
                val target = hosts.indexOfFirst { it.id == otherId }
                val otherCenter = other.offset + other.size / 2f
                (target == index + 1 && center > otherCenter) ||
                    (target == index - 1 && center < otherCenter)
            } ?: return
        val target = hosts.indexOfFirst { it.id == neighbour.key }
        hosts = hosts.toMutableList().apply { add(target, removeAt(index)) }
        onDragSwap()
    }
}

@Composable
internal fun rememberHostListReorderState(
    hosts: List<HostProfile>,
    listState: LazyListState,
    edge: Float,
    headerCount: Int,
    onDragSwap: () -> Unit,
): HostListReorderState {
    val currentOnDragSwap by rememberUpdatedState(onDragSwap)
    val state =
        remember(listState, headerCount) {
            HostListReorderState(listState, headerCount) { currentOnDragSwap() }
        }
    LaunchedEffect(hosts) { state.sync(hosts) }
    LaunchedEffect(state.draggingId) {
        if (state.draggingId != null) {
            while (isActive) {
                withFrameNanos {}
                state.scrollAtEdge(edge)
            }
        }
    }
    return state
}
