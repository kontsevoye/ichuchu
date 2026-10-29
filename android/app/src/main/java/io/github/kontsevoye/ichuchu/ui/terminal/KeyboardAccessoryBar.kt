package io.github.kontsevoye.ichuchu.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kontsevoye.ichuchu.ui.components.IchuButton
import io.github.kontsevoye.ichuchu.ui.components.IchuButtonSurface
import io.github.kontsevoye.ichuchu.ui.components.IchuButtonVariant
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuSymbolsFontFamily
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography
import kotlinx.coroutines.withTimeoutOrNull

private const val INITIAL_REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardAccessoryBar(
    entries: List<ResolvedAccessoryEntry>,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    onSettings: (() -> Unit)? = null,
    onIchuchuKey: (() -> Unit)? = null,
    ichuchuKeyActive: Boolean = false,
    onOpenFiles: (() -> Unit)? = null,
    useSingleRow: Boolean = false,
    horizontalPadding: Dp = 8.dp,
    verticalPadding: Dp = 6.dp,
    modifier: Modifier = Modifier,
) {
    val buttonHeight = 30.dp
    val buttonPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)
    var expandedGroupId by remember { mutableStateOf<String?>(null) }
    val onChildAction: (AccessoryAction) -> Unit = { action ->
        onAction(action)
    }

    Column(modifier = modifier) {
        expandedGroupId?.let { groupId ->
            val expandedGroup =
                entries.firstOrNull { it is ResolvedAccessoryEntry.Group && it.group.id == groupId }
                    as? ResolvedAccessoryEntry.Group
            if (expandedGroup != null) {
                GroupPopover(
                    group = expandedGroup.group,
                    modifierState = modifierState,
                    onAction = onChildAction,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                    horizontalPadding = horizontalPadding,
                )
            }
        }

        if (useSingleRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEach { entry ->
                    AccessoryEntry(
                        entry = entry,
                        expanded = expandedGroupId == entry.id,
                        modifierState = modifierState,
                        onAction = onAction,
                        onToggleGroup = { id ->
                            expandedGroupId = if (expandedGroupId == id) null else id
                        },
                        buttonHeight = buttonHeight,
                        buttonPadding = buttonPadding,
                    )
                }
                FilesButton(
                    onIchuchuKey = onIchuchuKey,
                    ichuchuKeyActive = ichuchuKeyActive,
                    onOpenFiles = onOpenFiles,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                )
                SettingsButton(
                    onSettings = onSettings,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                )
            }
            return
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxLines = 2,
        ) {
            entries.forEach { entry ->
                AccessoryEntry(
                    entry = entry,
                    expanded = expandedGroupId == entry.id,
                    modifierState = modifierState,
                    onAction = onAction,
                    onToggleGroup = { id ->
                        expandedGroupId = if (expandedGroupId == id) null else id
                    },
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                )
            }
            FilesButton(
                onIchuchuKey = onIchuchuKey,
                ichuchuKeyActive = ichuchuKeyActive,
                onOpenFiles = onOpenFiles,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
            SettingsButton(
                onSettings = onSettings,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
        }
    }
}

@Composable
private fun GroupPopover(
    group: AccessoryKeyGroup,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
    horizontalPadding: Dp,
) {
    val colors = IchuColors.current
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 4.dp)
                .background(colors.surface)
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            group.children.forEach { child ->
                AccessoryButton(
                    item = child,
                    modifierState = modifierState,
                    onAction = onAction,
                    buttonHeight = buttonHeight,
                    buttonPadding = buttonPadding,
                )
            }
        }
    }
}

@Composable
private fun AccessoryEntry(
    entry: ResolvedAccessoryEntry,
    expanded: Boolean,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    onToggleGroup: (String) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    when (entry) {
        is ResolvedAccessoryEntry.Single ->
            AccessoryButton(
                item = entry.item,
                modifierState = modifierState,
                onAction = onAction,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
        is ResolvedAccessoryEntry.Group ->
            GroupButton(
                group = entry.group,
                expanded = expanded,
                onToggle = { onToggleGroup(entry.group.id) },
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
    }
}

@Composable
private fun GroupButton(
    group: AccessoryKeyGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    IchuButton(
        onClick = onToggle,
        variant = if (expanded) IchuButtonVariant.Filled else IchuButtonVariant.Outlined,
        modifier = Modifier.height(buttonHeight),
        contentPadding = buttonPadding,
    ) {
        IchuText(
            group.label,
            style = typography.label,
            color = if (expanded) colors.onAccent else colors.textPrimary,
        )
    }
}

@Composable
private fun AccessoryButton(
    item: AccessoryKeyItem,
    modifierState: ModifierState,
    onAction: (AccessoryAction) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val typography = IchuTypography.current
    val toggleModifier = (item.action as? AccessoryAction.ToggleModifier)?.modifier
    if (toggleModifier != null) {
        ToggleButton(
            label = item.label,
            enabled = modifierState.isEnabled(toggleModifier),
            onClick = { onAction(item.action) },
            modifier = Modifier.height(buttonHeight),
            contentPadding = buttonPadding,
        )
    } else {
        val specialKey = (item.action as? AccessoryAction.SendSpecialKey)?.key
        if (specialKey != null && specialKey.isRepeatable) {
            RepeatableAccessoryButton(
                item = item,
                onAction = onAction,
                buttonHeight = buttonHeight,
                buttonPadding = buttonPadding,
            )
        } else {
            IchuButton(
                onClick = { onAction(item.action) },
                variant = IchuButtonVariant.Outlined,
                modifier = Modifier.height(buttonHeight),
                contentPadding = buttonPadding,
            ) {
                IchuText(item.label, style = typography.label)
            }
        }
    }
}

@Composable
private fun FilesButton(
    onIchuchuKey: (() -> Unit)?,
    ichuchuKeyActive: Boolean,
    onOpenFiles: (() -> Unit)?,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    if (onIchuchuKey != null) {
        IchuButton(
            onClick = onIchuchuKey,
            variant = if (ichuchuKeyActive) IchuButtonVariant.Filled else IchuButtonVariant.Outlined,
            modifier = Modifier.height(buttonHeight),
            contentPadding = buttonPadding,
        ) {
            IchuText(
                "⌘",
                style = typography.label,
                color = if (ichuchuKeyActive) colors.onAccent else colors.textPrimary,
            )
        }
    }
    if (onOpenFiles == null) return
    IchuButton(
        onClick = onOpenFiles,
        variant = IchuButtonVariant.Outlined,
        modifier = Modifier.height(buttonHeight),
        contentPadding = buttonPadding,
    ) {
        IchuText(
            text = "",
            style = TextStyle(
                fontFamily = IchuSymbolsFontFamily,
                fontWeight = typography.label.fontWeight,
                fontStyle = typography.label.fontStyle,
                fontSize = 16.sp,
                lineHeight = 16.sp,
            ),
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun SettingsButton(
    onSettings: (() -> Unit)?,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val typography = IchuTypography.current
    if (onSettings == null) return
    IchuButton(
        onClick = onSettings,
        variant = IchuButtonVariant.Outlined,
        modifier = Modifier.height(buttonHeight),
        contentPadding = buttonPadding,
    ) {
        IchuText("⚙", style = typography.label)
    }
}

@Composable
private fun RepeatableAccessoryButton(
    item: AccessoryKeyItem,
    onAction: (AccessoryAction) -> Unit,
    buttonHeight: Dp,
    buttonPadding: PaddingValues,
) {
    val typography = IchuTypography.current
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val currentOnAction by rememberUpdatedState(onAction)

    Box(
        modifier = Modifier
            .height(buttonHeight)
            .pointerInput(item.action) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    currentOnAction(item.action)

                    try {
                        val releasedDuringDelay = withTimeoutOrNull(INITIAL_REPEAT_DELAY_MS) {
                            waitForUpOrCancellation()
                        }

                        if (releasedDuringDelay == null) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            while (true) {
                                currentOnAction(item.action)
                                val released = withTimeoutOrNull(REPEAT_INTERVAL_MS) {
                                    waitForUpOrCancellation()
                                }
                                if (released != null) break
                            }
                        }
                    } finally {
                        pressed = false
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = item.label
                onClick(label = item.label) {
                    currentOnAction(item.action)
                    true
                }
            },
    ) {
        IchuButtonSurface(
            modifier = Modifier.height(buttonHeight),
            pressed = pressed,
            variant = IchuButtonVariant.Outlined,
            contentPadding = buttonPadding,
        ) {
            IchuText(item.label, style = typography.label)
        }
    }
}

@Composable
private fun ToggleButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    val activeLabel = if (enabled) "• $label" else label
    IchuButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = contentPadding,
        variant = if (enabled) IchuButtonVariant.Filled else IchuButtonVariant.Outlined,
    ) {
        IchuText(
            activeLabel,
            style = typography.label,
            color = if (enabled) colors.onAccent else colors.textSecondary,
        )
    }
}
