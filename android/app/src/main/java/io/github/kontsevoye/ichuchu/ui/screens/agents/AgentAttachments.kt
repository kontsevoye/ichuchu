package io.github.kontsevoye.ichuchu.ui.screens.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kontsevoye.ichuchu.service.agents.AGENT_ATTACHMENT_COUNT
import io.github.kontsevoye.ichuchu.service.agents.AgentAttachment
import io.github.kontsevoye.ichuchu.ui.components.IchuCard
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.screens.Files.formatFileSize
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography

@Composable
internal fun AgentAttachments(
    files: List<AgentAttachment>,
    sending: Boolean,
    compact: Boolean,
    onRemove: (String) -> Unit,
) {
    val colors = IchuColors.current
    val type = IchuTypography.current
    val list = rememberLazyListState()
    val knownIds = remember { mutableSetOf<String>() }
    val count = if (files.size == 1) "1 file attached" else "${files.size} files attached"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!compact) {
            IchuText(
                count,
                modifier = Modifier.semantics { contentDescription = count },
                style = type.bodySmall,
                color = colors.textMuted,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (compact) {
                IchuText(
                    "${files.size}/$AGENT_ATTACHMENT_COUNT",
                    modifier = Modifier.semantics { contentDescription = count },
                    style = type.bodySmall,
                    color = colors.textMuted,
                )
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val cardWidth = minOf(280.dp, maxWidth - if (compact) 12.dp else 24.dp)
                var previousShape by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
                val shape = compact to constraints.maxWidth
                LaunchedEffect(files.map { it.id }, shape) {
                    val resized = previousShape != null && previousShape != shape
                    previousShape = shape
                    val added = files.indexOfLast { it.id !in knownIds }
                    val layout = list.layoutInfo
                    val reading =
                        layout.visibleItemsInfo.lastOrNull {
                            it.offset >= layout.viewportStartOffset &&
                                it.offset + it.size <= layout.viewportEndOffset
                        } ?: layout.visibleItemsInfo.lastOrNull()
                    val target =
                        if (added >= 0) {
                            added
                        } else if (resized) {
                            reading?.index
                        } else {
                            null
                        }
                    // BoxWithConstraints updates item widths during measure. Position the card
                    // after that pass, and restart if the strip's actual width changes again.
                    target
                        ?.coerceAtMost(files.lastIndex)
                        ?.takeIf { it >= 0 }
                        ?.let {
                            withFrameNanos {}
                            list.scrollToItem(it)
                        }
                    // Keep additions pending if a new measurement cancels this effect.
                    knownIds.clear()
                    knownIds.addAll(files.map { it.id })
                }
                LazyRow(
                    Modifier.fillMaxWidth().testTag("agent_chat_attachments"),
                    state = list,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Recreate a measured card when its width changes; a reused item can retain
                    // its old width across BoxWithConstraints subcomposition during IME resize.
                    items(files, key = { "${it.id}:${cardWidth.value}" }) { file ->
                        IchuCard(Modifier.width(cardWidth)) {
                            Row(
                                Modifier.padding(start = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    IchuText(
                                        file.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = type.bodySmall,
                                        color = colors.textSecondary,
                                    )
                                    if (!compact) {
                                        IchuText(
                                            formatFileSize(file.size),
                                            style = type.bodySmall,
                                            color = colors.textMuted,
                                        )
                                    }
                                }
                                AgentButton(
                                    "×",
                                    { onRemove(file.id) },
                                    !sending,
                                    description = "Remove attachment ${file.name}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
