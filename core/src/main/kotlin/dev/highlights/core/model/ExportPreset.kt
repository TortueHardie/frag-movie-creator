package dev.highlights.core.model

import dev.highlights.core.i18n.I18n
import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.Serializable

/**
 * Préréglage d'export : ce qu'on fabrique, choisi d'un clic (« TikTok », « YouTube court », « Complet ») pour les
 * highlights comme pour le montage kills, au lieu de régler plateforme, format, durée, style et miniature un à un.
 * Chaque champ null laisse le réglage tel qu'il est ; un réglage choisi à la main (champ de l'application, option de la
 * ligne de commande) passe devant le préréglage. Les préréglages livrés sont dans `config/export-presets.yaml`.
 */
@Serializable
data class ExportPreset(
    /** Identifiant (`--preset <id>`) ; nom affiché : clé `preset.<id>` des traductions, sinon [displayName]. */
    val id: String,
    val displayName: String? = null,
    /** Phrase qui dit ce que fait le préréglage, pour un préréglage sans traduction (`preset.<id>.description`). */
    val description: String? = null,
    /** Plateforme visée (tiktok, shorts, reels, youtube… : format, volume, débit, zone sûre, durée maximale). */
    val platform: String? = null,
    /** Format de la vidéo sans plateforme (une plateforme impose déjà le sien). */
    val format: OutputFormat? = null,
    /** Durée visée : durée cible des highlights (sélection des moments), durée maximale du montage kills. */
    val length: SerialDuration? = null,
    /** Montage des highlights : simple ou story. */
    val highlightsStyle: EditStyle? = null,
    /** Style du montage kills (identifiant de `montage-styles.yaml`). */
    val montageStyle: String? = null,
    /** Miniature YouTube écrite à côté de chaque vidéo. */
    val thumbnail: Boolean? = null,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "export-presets : identifiant '$id' attendu en minuscules, chiffres et tirets" }
        require(platform == null || format == null) { "export-presets : '$id' donne une plateforme et un format, la plateforme impose déjà le sien" }
        require(length == null || length.isPositive()) { "export-presets : durée de '$id' nulle ou négative" }
    }

    /** Nom montré : traduit si la clé `preset.<id>` existe, sinon celui du fichier, sinon l'identifiant. */
    val name: String get() = I18n.textOrNull("preset.$id") ?: displayName ?: id

    /** Ce que fait le préréglage, traduit si possible ; null si le fichier n'en dit rien. */
    val shownDescription: String? get() = I18n.textOrNull("preset.$id.description") ?: description
}

/** Contenu de `config/export-presets.yaml` : les préréglages dans l'ordre où on les propose. */
@Serializable
data class ExportPresets(val presets: List<ExportPreset> = emptyList()) {
    init {
        require(presets.map { it.id }.toSet().size == presets.size) { "export-presets : identifiants en double" }
    }

    fun byId(id: String): ExportPreset? = presets.find { it.id == id }

    companion object {
        val NONE = ExportPresets()
    }
}
