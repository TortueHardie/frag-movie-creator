package dev.highlights.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.highlights.core.i18n.I18n
import java.nio.file.Path
import kotlin.io.path.isRegularFile

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
 * explicite par langue, prise dans les polices livrées avec Windows 10/11, donne une seule source et de vrais gras.
 * Graisses demandées par le thème : 400, 500 (libellés), 600 (titres) ; une police sans 500 retombe sur 400 et une
 * sans 600 sur 700 (règle de choix de Compose, celle de CSS). Fichier absent (autre système) : police par défaut.
 */
object UiFonts {
    private val dir: Path = Path.of(System.getenv("WINDIR") ?: "C:/Windows", "Fonts")

    /** Fichiers par graisse, du plus courant au plus gras. */
    internal fun files(language: String): Map<FontWeight, String> = when (language) {
        "zh" -> mapOf(FontWeight.Normal to "msyh.ttc", FontWeight.Bold to "msyhbd.ttc")
        "ja" -> mapOf(FontWeight.Normal to "YuGothR.ttc", FontWeight.Medium to "YuGothM.ttc", FontWeight.Bold to "YuGothB.ttc")
        "ko" -> mapOf(FontWeight.Normal to "malgun.ttf", FontWeight.Bold to "malgunbd.ttf")
        else -> emptyMap()
    }

    fun forLanguage(language: String): FontFamily {
        val fonts = files(language).mapNotNull { (weight, name) ->
            dir.resolve(name).takeIf { it.isRegularFile() }?.let { Font(it.toFile(), weight) }
        }
        // Sans la graisse normale, un gras seul servirait à tout le texte : mieux vaut le repli d'avant.
        if (fonts.none { it.weight == FontWeight.Normal }) return FontFamily.Default
        return FontFamily(fonts)
    }
}
