package io.github.kontsevoye.ichuchu.ui.screens.Settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kontsevoye.ichuchu.ui.components.IchuButton
import io.github.kontsevoye.ichuchu.ui.components.IchuButtonVariant
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuFontOption
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography

@Composable
internal fun FontSelectorSection(
    currentFont: String,
    onFontSelected: (String) -> Unit,
) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    val current = remember(currentFont) { IchuFontOption.fromId(currentFont) }
    var expanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IchuText("── ", style = typography.labelSmall, color = colors.textMuted)
        IchuText("FONT", style = typography.labelSmall, color = colors.textMuted)
        IchuText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
    Spacer(modifier = Modifier.height(12.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IchuButton(
            onClick = { expanded = !expanded },
            variant = IchuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IchuText(current.label, style = typography.label)
                IchuText(if (expanded) "▲" else "▼", style = typography.labelSmall, color = colors.textMuted)
            }
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                IchuFontOption.entries.forEach { option ->
                    val isSelected = option == current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) colors.surface else colors.surfaceVariant)
                            .clickable {
                                onFontSelected(option.id)
                                expanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IchuText(
                                option.label,
                                style = typography.label,
                                color = if (isSelected) colors.accent else colors.textPrimary,
                            )
                            if (isSelected) IchuText("●", style = typography.label, color = colors.accent)
                        }
                    }
                }
            }
        }
    }
}
