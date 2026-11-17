package dev.highlights.core.i18n

import kotlin.math.abs

/**
 * Familles de règles de pluriel (CLDR, nombres entiers et décimaux simplifiés) : chaque langue en nomme une dans
 * `plural.rule` de son fichier. Une nouvelle langue choisit la famille qui lui va, sans code. Catégories rendues :
 * `one`, `few`, `many`, `other` ; la clé `.other` sert quand la forme demandée manque.
 */
enum class PluralRule(val id: String, val categories: List<String>) {
    /** 1 seul au singulier : anglais, allemand, espagnol, italien, néerlandais, suédois, turc, grec… */
    ONE("one", listOf("one", "other")),
    /** 0 et 1 au singulier : français, portugais du Brésil, hindi. */
    ZERO_ONE("zero-one", listOf("one", "other")),
    /** Russe, ukrainien, biélorusse : 1, 21, 31 / 2-4, 22-24 / le reste. */
    EAST_SLAVIC("east-slavic", listOf("one", "few", "many", "other")),
    /** Polonais : 1 / 2-4, 22-24 (pas 12-14) / le reste. */
    POLISH("polish", listOf("one", "few", "many", "other")),
    /** Tchèque, slovaque : 1 / 2-4 / le reste. */
    CZECH("czech", listOf("one", "few", "other")),
    /** Pas de pluriel grammatical : japonais, coréen, chinois, vietnamien, thaï, indonésien. */
    NONE("none", listOf("other")),
    ;

    fun select(n: Double): String {
        val integer = n == Math.floor(n) && !n.isInfinite()
        val i = abs(n).toLong()
        return when (this) {
            ONE -> if (integer && i == 1L) "one" else "other"
            ZERO_ONE -> if (i == 0L || i == 1L) "one" else "other"
            EAST_SLAVIC -> when {
                !integer -> "other"
                i % 10 == 1L && i % 100 != 11L -> "one"
                i % 10 in 2..4 && i % 100 !in 12..14 -> "few"
                else -> "many"
            }
            POLISH -> when {
                !integer -> "other"
                i == 1L -> "one"
                i % 10 in 2..4 && i % 100 !in 12..14 -> "few"
                else -> "many"
            }
            CZECH -> when {
                !integer -> "other"
                i == 1L -> "one"
                i in 2..4 -> "few"
                else -> "other"
            }
            NONE -> "other"
        }
    }

    companion object {
        /** Famille déclarée par le fichier, sinon devinée d'après la langue ([tag] du fichier), sinon [ONE]. */
        fun of(declared: String?, tag: String): PluralRule {
            declared?.trim()?.let { d -> entries.firstOrNull { it.id == d }?.let { return it } }
            return when (tag.substringBefore('_')) {
                "fr", "pt", "hi" -> ZERO_ONE
                "ru", "uk", "be" -> EAST_SLAVIC
                "pl" -> POLISH
                "cs", "sk" -> CZECH
                "ja", "ko", "zh", "vi", "th", "id", "ms" -> NONE
                else -> ONE
            }
        }
    }
}
