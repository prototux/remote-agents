package net.prototux.remoteagents.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.remoteagents.R
import net.prototux.remoteagents.data.DarkMode
import net.prototux.remoteagents.data.ThemeId

// Theme tokens follow NothingEven's ThemeSpec (copied, not shared: separate repos by decision).

/** What Material's [ColorScheme] and [Typography] have no slot for. */
@Immutable
data class ThemeTokens(
    val success: Color,
    /** Background of something that needs the owner now (approval banner, awaiting badge). */
    val attention: Color,
    val onAttention: Color,
    val codeBackground: Color,
    val diffAdded: Color,
    val diffRemoved: Color,
    val screenMargin: Dp,
    val cardGap: Dp,
    /** Large figures and headings: dot-matrix on the Nothing theme. */
    val figureFont: FontFamily,
    val mono: FontFamily = FontFamily.Monospace,
)

@Immutable
data class ThemeSpec(val id: ThemeId, val dark: Boolean, val colors: ColorScheme, val typography: Typography, val shapes: Shapes, val tokens: ThemeTokens)

val LocalThemeSpec = staticCompositionLocalOf<ThemeSpec> { error("No ThemeSpec: wrap the UI in AppTheme") }

/** Shortcut for the tokens in composables. */
val tokens: ThemeTokens @Composable get() = LocalThemeSpec.current.tokens

@Composable
fun AppTheme(theme: ThemeId, darkMode: DarkMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (darkMode) {
        DarkMode.System -> isSystemInDarkTheme()
        DarkMode.Light -> false
        DarkMode.Dark -> true
    }
    val context = LocalContext.current
    val spec = remember(theme, dark, dynamicColor) {
        when (theme) {
            ThemeId.Material -> MaterialSpec.build(dark, if (dynamicColor) (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)) else null)
            ThemeId.Nothing -> NothingSpec.build(dark)
        }
    }
    CompositionLocalProvider(LocalThemeSpec provides spec) {
        MaterialTheme(colorScheme = spec.colors, typography = spec.typography, shapes = spec.shapes, content = content)
    }
}

/** Both are variable fonts (wght 100-900); each [Font] picks its weight through the variation axis. */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: FontWeight) = FontFamily(
    weights.map { w -> Font(res, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight))) },
)

internal val Doto = variable(R.font.doto, FontWeight.Normal, FontWeight.Bold)
internal val Grotesk = variable(R.font.hanken_grotesk, FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.Bold)

internal object MaterialSpec {
    fun build(dark: Boolean, dynamic: ColorScheme?): ThemeSpec {
        val colors = dynamic ?: if (dark) darkColorScheme() else lightColorScheme()
        return ThemeSpec(
            ThemeId.Material, dark, colors, Typography(), Shapes(),
            ThemeTokens(
                success = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32),
                attention = colors.tertiaryContainer,
                onAttention = colors.onTertiaryContainer,
                codeBackground = colors.surfaceContainerHighest,
                diffAdded = if (dark) Color(0x3328A745) else Color(0x2228A745),
                diffRemoved = if (dark) Color(0x33D73A49) else Color(0x22D73A49),
                screenMargin = 16.dp, cardGap = 12.dp, figureFont = FontFamily.Default,
            ),
        )
    }
}

/**
 * Monochrome with one red accent; headings and figures in Doto (OFL dot-matrix font), body in Hanken
 * Grotesk. Nothing's own NDot font is never used; the red approximates Nothing's brand red.
 */
internal object NothingSpec {
    val RED = Color(0xFFD71921)

    fun build(dark: Boolean): ThemeSpec {
        val colors = if (dark) darkColors() else lightColors()
        return ThemeSpec(
            ThemeId.Nothing, dark, colors, typography(),
            Shapes(
                extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
            ),
            ThemeTokens(
                // Monochrome: "good" is plain text colour; only attention and errors are red.
                success = colors.onSurface,
                attention = RED, onAttention = Color.White,
                codeBackground = if (dark) grey(0x14) else grey(0xF2),
                diffAdded = if (dark) grey(0x2A) else grey(0xE0),
                diffRemoved = RED.copy(alpha = 0.22f),
                screenMargin = 16.dp, cardGap = 12.dp, figureFont = Doto,
            ),
        )
    }

    private fun lightColors(): ColorScheme = lightColorScheme(
        primary = Color.Black, onPrimary = Color.White,
        primaryContainer = grey(0xE6), onPrimaryContainer = Color.Black,
        secondary = grey(0x5E), onSecondary = Color.White,
        secondaryContainer = RED, onSecondaryContainer = Color.White,
        tertiary = RED, onTertiary = Color.White,
        tertiaryContainer = RED, onTertiaryContainer = Color.White,
        background = Color.White, onBackground = Color.Black,
        surface = Color.White, onSurface = grey(0x1B),
        surfaceVariant = grey(0xEC), onSurfaceVariant = grey(0x5E),
        surfaceContainerLowest = Color.White, surfaceContainerLow = grey(0xF7),
        surfaceContainer = grey(0xF2), surfaceContainerHigh = grey(0xEC), surfaceContainerHighest = grey(0xE6),
        surfaceBright = Color.White, surfaceDim = grey(0xDE),
        inverseSurface = grey(0x1B), inverseOnSurface = grey(0xF2), inversePrimary = Color.White,
        error = RED, onError = Color.White, errorContainer = RED.copy(alpha = 0.15f), onErrorContainer = RED,
        outline = grey(0x8A), outlineVariant = grey(0xD0),
    )

    private fun darkColors(): ColorScheme = darkColorScheme(
        primary = Color.White, onPrimary = Color.Black,
        primaryContainer = grey(0x2A), onPrimaryContainer = Color.White,
        secondary = grey(0xA6), onSecondary = Color.Black,
        secondaryContainer = RED, onSecondaryContainer = Color.White,
        tertiary = RED, onTertiary = Color.White,
        tertiaryContainer = RED, onTertiaryContainer = Color.White,
        background = Color.Black, onBackground = Color.White,
        surface = Color.Black, onSurface = grey(0xF2),
        surfaceVariant = grey(0x1C), onSurfaceVariant = grey(0xA6),
        surfaceContainerLowest = Color.Black, surfaceContainerLow = grey(0x0D),
        surfaceContainer = grey(0x14), surfaceContainerHigh = grey(0x1C), surfaceContainerHighest = grey(0x24),
        surfaceBright = grey(0x2A), surfaceDim = Color.Black,
        inverseSurface = grey(0xF2), inverseOnSurface = grey(0x1B), inversePrimary = Color.Black,
        error = RED, onError = Color.White, errorContainer = RED.copy(alpha = 0.2f), onErrorContainer = Color(0xFFFF8A8F),
        outline = grey(0x75), outlineVariant = grey(0x33),
    )

    private fun grey(v: Int) = Color(red = v, green = v, blue = v)

    /** Doto for display, headline and large titles only: a dot-matrix face is hard to read at body sizes. */
    private fun typography(): Typography {
        val base = Typography()
        fun TextStyle.dotted() = copy(fontFamily = Doto, fontWeight = FontWeight.Bold)
        fun TextStyle.grotesk() = copy(fontFamily = Grotesk)
        return Typography(
            displayLarge = base.displayLarge.dotted(), displayMedium = base.displayMedium.dotted(), displaySmall = base.displaySmall.dotted(),
            headlineLarge = base.headlineLarge.dotted(), headlineMedium = base.headlineMedium.dotted(), headlineSmall = base.headlineSmall.dotted(),
            titleLarge = base.titleLarge.dotted(),
            titleMedium = base.titleMedium.grotesk(), titleSmall = base.titleSmall.grotesk(),
            bodyLarge = base.bodyLarge.grotesk(), bodyMedium = base.bodyMedium.grotesk(), bodySmall = base.bodySmall.grotesk(),
            labelLarge = base.labelLarge.grotesk(), labelMedium = base.labelMedium.grotesk(), labelSmall = base.labelSmall.grotesk(),
        )
    }
}
