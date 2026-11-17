package dev.highlights.editing.story

import dev.highlights.core.i18n.I18n
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * Police des textes incrustés (libellés DOUBLÉ / ダブルキル, sous-titres) selon l'écriture du texte. `drawtext` n'a
 * aucun repli de police : un caractère absent de la police devient un carré vide dans la vidéo. Impact (défaut des
 * profils) couvre le latin et le cyrillique, mais ni le japonais, ni le coréen, ni le chinois. La police configurée
 * reste donc celle utilisée tant qu'elle a tous les caractères du texte ; sinon on prend une police livrée avec
 * toute installation de Windows 10/11 qui les a (vérifié avec `Font.canDisplayUpTo` sur ces fichiers) :
 * Yu Gothic Bold (japonais : kana, kanji ; il lui manque des caractères simplifiés comme 杀), Malgun Gothic Bold
 * (coréen), Microsoft YaHei Bold (chinois simplifié, couvre aussi les kana), Arial Black puis Segoe UI Black (autres
 * écritures latines, grecques, cyrilliques). Les fichiers `.ttc` sont lus par FFmpeg (FreeType, première police du
 * fichier) comme par Java.
 *
 * Sans fichier lisible par Java (autre système, chemin faux), la police configurée est gardée telle quelle : on ne
 * peut pas savoir ce qu'elle contient, et le rendu reste celui d'avant.
 */
object VideoFonts {
    /** Dossier des polices de Windows (`%WINDIR%\Fonts`), en barres obliques comme dans les profils. */
    private val fontsDir: String = (System.getenv("WINDIR") ?: "C:/Windows").replace('\\', '/').trimEnd('/') + "/Fonts"

    val JAPANESE = "$fontsDir/YuGothB.ttc"
    val KOREAN = "$fontsDir/malgunbd.ttf"
    val CHINESE = "$fontsDir/msyhbd.ttc"
    val HEAVY_LATIN = "$fontsDir/ariblk.ttf"
    val HEAVY_UI = "$fontsDir/seguibl.ttf"

    /** Tous les replis, du plus proche d'Impact (gras, serré) au plus couvrant. */
    private val ALL get() = listOf(HEAVY_LATIN, HEAVY_UI, CHINESE, JAPANESE, KOREAN)

    private val warned = ConcurrentHashMap.newKeySet<String>()

    /**
     * Police avec laquelle dessiner [text] : [configured] s'il en a tous les caractères (ou s'il est illisible par
     * Java), sinon le premier repli installé qui les a, choisi d'après l'écriture du texte. Les idéogrammes seuls
     * (communs au japonais et au chinois) suivent la langue de l'interface [locale] : 双杀 ne s'écrit pas en Yu Gothic.
     */
    fun forText(configured: String, text: String, locale: Locale = I18n.locale): String {
        if (TextMeasure.canDisplay(configured, text) != false) return configured
        val candidates = (preferred(text, locale) + ALL).distinct()
        candidates.firstOrNull { Files.isRegularFile(Path.of(it)) && TextMeasure.canDisplay(it, text) == true }?.let { return it }
        if (warned.add(configured)) {
            log.warn { "Aucune police installée n'a tous les caractères de « $text » ; $configured gardée (carrés vides possibles)" }
        }
        return configured
    }

    /** Replis à essayer d'abord pour l'écriture de [text]. */
    internal fun preferred(text: String, locale: Locale): List<String> {
        val scripts = text.codePoints().toArray().map { Character.UnicodeScript.of(it) }.toSet()
        return when {
            Character.UnicodeScript.HANGUL in scripts -> listOf(KOREAN)
            Character.UnicodeScript.HIRAGANA in scripts || Character.UnicodeScript.KATAKANA in scripts -> listOf(JAPANESE, CHINESE)
            Character.UnicodeScript.HAN in scripts -> when (locale.language) {
                "ja" -> listOf(JAPANESE, CHINESE)
                "ko" -> listOf(KOREAN, CHINESE)
                else -> listOf(CHINESE, JAPANESE)
            }
            else -> listOf(HEAVY_LATIN, HEAVY_UI)
        }
    }

    /** Chemin de police écrit pour `drawtext` entre apostrophes : barres obliques, deux-points échappés. */
    fun drawtextPath(font: String): String = font.replace("\\", "/").replace(":", "\\:")
}
