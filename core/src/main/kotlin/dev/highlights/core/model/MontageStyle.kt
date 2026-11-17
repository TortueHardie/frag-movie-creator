package dev.highlights.core.model

import dev.highlights.core.i18n.I18n
import kotlinx.serialization.Serializable

/**
 * Style de montage nommé : un jeu de réglages posé par-dessus ceux du profil, à la place d'une douzaine de cases à
 * cocher une à une (rythme rapide, couleurs, vitrine, flou, raccords, whip, souffle…). Chaque champ null laisse le
 * réglage du profil ; un réglage choisi à la main (case de la fenêtre, option de la ligne de commande) passe devant le
 * style. Les styles livrés sont dans `config/montage-styles.yaml`, qu'on modifie ou complète comme les plateformes.
 */
@Serializable
data class MontageStyle(
    /** Identifiant (`--style <id>`) ; son nom affiché vient de la clé `style.<id>` des traductions, sinon de [displayName]. */
    val id: String,
    val displayName: String? = null,
    /** Phrase qui dit ce que fait le style, pour un style sans traduction (`style.<id>.description`). */
    val description: String? = null,
    val fast: Boolean? = null,
    val oneTaps: Boolean? = null,
    val order: MontageOrder? = null,
    val effectDensity: EffectDensity? = null,
    val hook: Boolean? = null,
    val zoom: Boolean? = null,
    val flash: Boolean? = null,
    val flashEveryCut: Boolean? = null,
    val whip: Boolean? = null,
    val whoosh: Boolean? = null,
    val colors: Boolean? = null,
    val motionBlur: Boolean? = null,
    val matchCut: Boolean? = null,
    val slowMotion: Boolean? = null,
    val speedRamp: Boolean? = null,
    val text: Boolean? = null,
    val showcase: Boolean? = null,
    val gameAudio: GameAudio? = null,
    val reactions: Boolean? = null,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "montage-styles : identifiant '$id' attendu en minuscules, chiffres et tirets" }
    }

    /** Nom montré : traduit si la clé `style.<id>` existe, sinon celui du fichier, sinon l'identifiant. */
    val name: String get() = I18n.textOrNull("style.$id") ?: displayName ?: id

    /** Ce que fait le style, traduit si possible ; null si le fichier n'en dit rien. */
    val shownDescription: String? get() = I18n.textOrNull("style.$id.description") ?: description
}

/**
 * Contenu de `config/montage-styles.yaml` : les styles dans l'ordre où on les propose, et celui pris quand on n'en
 * choisit aucun ([default], null : les réglages du profil seuls).
 */
@Serializable
data class MontageStyles(
    val default: String? = null,
    val styles: List<MontageStyle> = emptyList(),
) {
    init {
        require(styles.map { it.id }.toSet().size == styles.size) { "montage-styles : identifiants en double" }
        require(default == null || styles.any { it.id == default }) { "montage-styles : style par défaut '$default' inconnu" }
    }

    fun byId(id: String): MontageStyle? = styles.find { it.id == id }

    val defaultStyle: MontageStyle? get() = default?.let(::byId)

    companion object {
        val NONE = MontageStyles()
    }
}
