package io.github.kontsevoye.ichuchu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography

enum class IchuButtonVariant {
    Filled,
    Outlined,
    Ghost,
}

/**
 * Reusable non-clickable button surface: background, border, shape, press alpha,
 * min-height, and content padding.  Used by [IchuButton] for clickable buttons
 * and by [RepeatableAccessoryButton][io.github.kontsevoye.ichuchu.ui.terminal.RepeatableAccessoryButton]
 * for repeat-touch buttons so both share consistent visual styling.
 */
@Composable
fun IchuButtonSurface(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressed: Boolean = false,
    variant: IchuButtonVariant = IchuButtonVariant.Filled,
    backgroundColor: Color? = null,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable () -> Unit,
) {
    val colors = IchuColors.current
    val shape = RectangleShape

    val background: Color = when {
        !enabled -> colors.disabledSurface
        variant == IchuButtonVariant.Filled -> colors.accent
        backgroundColor != null -> backgroundColor
        else -> Color.Transparent
    }

    val effectiveBorderColor: Color = borderColor ?: colors.border

    val border: BorderStroke? = when {
        !enabled && variant != IchuButtonVariant.Ghost -> BorderStroke(1.dp, colors.border)
        variant == IchuButtonVariant.Outlined -> BorderStroke(1.dp, effectiveBorderColor)
        else -> null
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(background, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .defaultMinSize(minHeight = 36.dp)
            .alpha(if (pressed && enabled) 0.7f else 1f)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun IchuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: IchuButtonVariant = IchuButtonVariant.Filled,
    bracketed: Boolean = false,
    borderColor: Color? = null,
    backgroundColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    testTag: String? = null,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val bracketColor: Color = when {
        !enabled -> colors.disabledText
        variant == IchuButtonVariant.Filled -> colors.onAccent
        borderColor != null -> borderColor
        else -> colors.accent
    }

    val semanticsModifier = Modifier.semantics {
        if (testTag != null) {
            this.testTag = testTag
        }
        if (contentDescription != null) {
            this.contentDescription = contentDescription
        }
    }

    IchuButtonSurface(
        modifier = modifier
            .then(semanticsModifier)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        enabled = enabled,
        pressed = pressed,
        variant = variant,
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        contentPadding = contentPadding,
    ) {
        if (bracketed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IchuText("[", style = typography.label, color = bracketColor)
                content()
                IchuText("]", style = typography.label, color = bracketColor)
            }
        } else {
            content()
        }
    }
}
