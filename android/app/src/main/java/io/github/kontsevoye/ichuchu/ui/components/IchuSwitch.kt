package io.github.kontsevoye.ichuchu.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors

@Composable
fun IchuSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val colors = IchuColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val trackColor = if (checked) colors.accent else colors.surface
    val thumbColor = if (checked) colors.onAccent else colors.textPrimary
    val thumbOffset by animateDpAsState(if (checked) 17.dp else 2.dp, label = "chu-switch")

    Box(
        modifier =
            modifier
                .size(48.dp)
                .semantics { if (label != null) contentDescription = label }
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onValueChange = onCheckedChange,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.size(width = 36.dp, height = 18.dp)
                    .background(trackColor)
                    .border(1.dp, colors.border)
                    .alpha(if (enabled) 1f else 0.5f)
        ) {
            Box(
                modifier =
                    Modifier.offset(x = thumbOffset, y = 1.dp).size(16.dp).background(thumbColor)
            )
        }
    }
}
