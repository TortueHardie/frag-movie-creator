package dev.highlights.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.highlights.core.i18n.I18n
import java.awt.GraphicsEnvironment
import java.util.Locale

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

private fun typography(family: FontFamily) = Typography(
    titleLarge = TextStyle(fontFamily = family, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = family, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = family, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontFamily = family, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = family, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = family, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = family, fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = family, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = family, fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/** Thème de l'application ; la police suit la langue de l'interface (voir [UiFonts]), relue quand elle change. */
@Composable
fun HighlightsTheme(content: @Composable () -> Unit) {
    val language = I18n.locale.language
    val typography = remember(language) { typography(UiFonts.forLanguage(language)) }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/**
 * Police de l'interface. En latin, cyrillique, etc. : celle de Compose, comme avant. En chinois, japonais et coréen,
 * la police par défaut n'a pas ces caractères et Skia les cherche glyphe par glyphe dans les polices du système : les
 * idéogrammes communs au japonais venaient de Yu Gothic (avec un gras), les caractères simplifiés seuls (统, 计) d'une
 * autre police sans gras, d'où « 搜索 » en gras à côté de « 统计 » maigre sur deux boutons identiques. Une famille
 * explicite par langue, celle de l'interface de Windows 10/11, donne une seule source et de vrais gras.
 *
 * Par nom de famille ([SystemFont]) et non par fichier : `YuGothR.ttc` s'ouvre sur sa première police, Yu Gothic, aux
 * kana espacés pleine chasse et plus maigre que « Yu Gothic UI », la police des menus de Windows rangée plus loin dans
 * le même fichier. Skia prend dans la famille la graisse la plus proche de chacune de celles du thème (400, 500, 600,
 * 700). Famille absente (autre système) : police par défaut, comme avant.
 */
object UiFonts {
    private val WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

    /** Famille de l'interface de Windows pour [language], null hors chinois, japonais, coréen. */
    internal fun family(language: String): String? = when (language) {
        "zh" -> "Microsoft YaHei UI"
        "ja" -> "Yu Gothic UI"
        "ko" -> "Malgun Gothic"
        else -> null
    }

    private val installed: Set<String> by lazy {
        runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT).toSet() }
            .getOrDefault(emptySet())
    }

    // SystemFont est marquée expérimentale depuis Compose 1.0 desktop ; seule façon de viser une police du système par nom.
    @OptIn(ExperimentalTextApi::class)
    fun forLanguage(language: String): FontFamily {
        val name = family(language)?.takeIf { it in installed } ?: return FontFamily.Default
        return FontFamily(WEIGHTS.map { SystemFont(name, it) })
    }
}
