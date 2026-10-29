package io.github.kontsevoye.ichuchu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography

@Composable
fun IchuDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    val shape = RectangleShape

    Dialog(
        onDismissRequest = onDismiss,
        properties = properties,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, shape)
                .border(BorderStroke(1.dp, colors.border), shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IchuText(text = title, style = typography.title)
            content()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                IchuButton(
                    onClick = onDismiss,
                    variant = IchuButtonVariant.Ghost,
                    bracketed = true,
                    borderColor = colors.textMuted,
                ) {
                    IchuText(dismissLabel, style = typography.label, color = colors.textMuted)
                }
                IchuButton(
                    onClick = onConfirm,
                    bracketed = true,
                ) {
                    IchuText(confirmLabel, style = typography.label, color = colors.onAccent)
                }
            }
        }
    }
}
