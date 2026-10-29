package io.github.kontsevoye.ichuchu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.kontsevoye.ichuchu.R

@Immutable
data class IchuTypeScale(
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val labelSmall: TextStyle,
)

enum class IchuFontOption(
    val id: String,
    val label: String,
    val regularFontResId: Int,
) {
    JetBrainsMono("jetbrains_mono", "JetBrains Mono", R.font.jetbrains_mono_regular),
    FiraCode("fira_code", "Fira Code", R.font.fira_code_regular),
    Hack("hack", "Hack", R.font.hack_regular),
    GeistMono("geist_mono", "Geist Mono", R.font.geist_mono_regular),
    ;

    companion object {
        val default: IchuFontOption = JetBrainsMono

        fun fromId(id: String?): IchuFontOption {
            if (id.isNullOrBlank()) return default
            return entries.firstOrNull { it.id == id } ?: default
        }
    }
}

val IchuSymbolsFontFamily: FontFamily = FontFamily(
    Font(R.font.symbols_nerd_font_mono_regular, FontWeight.Normal),
)

private fun monoFamily(option: IchuFontOption): FontFamily = FontFamily(
    Font(option.regularFontResId, FontWeight.Normal),
    Font(option.regularFontResId, FontWeight.Medium),
    Font(option.regularFontResId, FontWeight.SemiBold),
)

fun chuTypographyFor(option: IchuFontOption): IchuTypeScale {
    val mono = monoFamily(option)
    return IchuTypeScale(
        headline = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        ),
        title = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        body = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
        label = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
    )
}

val IchuDefaultTypography: IchuTypeScale = chuTypographyFor(IchuFontOption.default)

val LocalIchuTypography = staticCompositionLocalOf { IchuDefaultTypography }
val LocalIchuFont = staticCompositionLocalOf { IchuFontOption.default }

object IchuTypography {
    val current: IchuTypeScale
        @Composable
        @ReadOnlyComposable
        get() = LocalIchuTypography.current
}
