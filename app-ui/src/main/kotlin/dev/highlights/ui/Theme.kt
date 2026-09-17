package dev.highlights.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val background = Color(0xFF0E1014)
    val surface = Color(0xFF161920)
    val surfaceHigh = Color(0xFF1E222B)
    val outline = Color(0xFF2C313C)
    val accent = Color(0xFFFF7A2F)
    val accentSoft = Color(0x33FF7A2F)
    val cyan = Color(0xFF3FC6E0)
    val text = Color(0xFFE8EAF0)
    val textMuted = Color(0xFF9097A6)
    val danger = Color(0xFFFF5C63)
    val success = Color(0xFF4CD08A)
    val disabledSegment = Color(0x22FFFFFF)
}

private val colors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color(0xFF1A0E06),
    primaryContainer = Palette.accentSoft,
    onPrimaryContainer = Palette.accent,
    secondary = Palette.cyan,
    onSecondary = Color(0xFF04161A),
    background = Palette.background,
    onBackground = Palette.text,
    surface = Palette.surface,
    onSurface = Palette.text,
    surfaceVariant = Palette.surfaceHigh,
    onSurfaceVariant = Palette.textMuted,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surfaceHigh,
    surfaceContainerHighest = Palette.surfaceHigh,
    outline = Palette.outline,
    outlineVariant = Palette.outline,
    error = Palette.danger,
)

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontSize = 14.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun HighlightsTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = typography, content = content)
