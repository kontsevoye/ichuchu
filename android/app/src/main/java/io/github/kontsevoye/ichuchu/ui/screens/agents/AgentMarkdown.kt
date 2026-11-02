package io.github.kontsevoye.ichuchu.ui.screens.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography

/** Small, inert Markdown subset: no HTML, remote images, scripts or automatic URL requests. */
@Composable
internal fun AgentMarkdown(text: String) {
    val colors = IchuColors.current
    val type = IchuTypography.current
    val uriHandler = LocalUriHandler.current
    val parts = remember(text) { text.split("```") }
    Column(Modifier.fillMaxWidth()) {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                SelectionContainer {
                    IchuText(
                        part.substringAfter('\n', part).trimEnd(),
                        style = type.bodySmall,
                        modifier =
                            Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(10.dp),
                    )
                }
            } else if (part.isNotBlank()) {
                val annotated =
                    remember(part, colors) {
                        buildAnnotatedString {
                            val pattern =
                                Regex(
                                    "\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)|\\*\\*(.+?)\\*\\*|`([^`]+)`"
                                )
                            var offset = 0
                            for (match in pattern.findAll(part)) {
                                append(part.substring(offset, match.range.first))
                                val start = length
                                when {
                                    match.groupValues[1].isNotEmpty() -> {
                                        append(match.groupValues[1])
                                        addStringAnnotation(
                                            "url",
                                            match.groupValues[2],
                                            start,
                                            length,
                                        )
                                        addStyle(
                                            SpanStyle(
                                                color = colors.accent,
                                                textDecoration = TextDecoration.Underline,
                                            ),
                                            start,
                                            length,
                                        )
                                    }
                                    match.groupValues[3].isNotEmpty() -> {
                                        append(match.groupValues[3])
                                        addStyle(
                                            SpanStyle(fontWeight = FontWeight.Bold),
                                            start,
                                            length,
                                        )
                                    }
                                    else -> {
                                        append(match.groupValues[4])
                                        addStyle(
                                            SpanStyle(background = colors.surfaceVariant),
                                            start,
                                            length,
                                        )
                                    }
                                }
                                offset = match.range.last + 1
                            }
                            append(part.substring(offset))
                        }
                    }
                SelectionContainer {
                    @Suppress("DEPRECATION")
                    ClickableText(
                        annotated,
                        style = type.body.copy(color = colors.textPrimary),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) { offset ->
                        annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let {
                            runCatching { uriHandler.openUri(it.item) }
                        }
                    }
                }
            }
        }
    }
}
