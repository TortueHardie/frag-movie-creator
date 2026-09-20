package dev.highlights.core.profile

import com.charleskorn.kaml.YamlNode
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class GameProfile(
    val id: String,
    val displayName: String = id,
    val match: ProfileMatch = ProfileMatch(),
    val window: WindowSpec = WindowSpec(),
    /**
     * Indices des pistes audio de la capture, quand la déduction automatique se trompe (voir AudioTracks) :
     * ex. « audio: { game: 1, mic: 2 } ». Vide = rôles déduits du nombre de pistes et de leurs titres.
     */
    val audio: AudioLayout = AudioLayout(),
    val detectors: List<DetectorConfig>,
    val selection: SelectionPolicy = SelectionPolicy(),
    val edit: EditSettings = EditSettings(),
    val montage: MontageSettings = MontageSettings(),
) {
    init {
        require(detectors.map { it.id }.toSet().size == detectors.size) { "profil '$id' : identifiants de détecteurs en double" }
    }
}

@Serializable
data class ProfileMatch(
    /** Sous-chaînes cherchées (sans casse) dans le chemin complet du fichier, ex. "League of Legends". */
    val pathContains: List<String> = emptyList(),
    /** Départage si plusieurs profils correspondent. */
    val priority: Int = 0,
)

@Serializable
data class WindowSpec(
    val size: SerialDuration = 2.seconds,
    val hop: SerialDuration = 1.seconds,
)

@Serializable
data class DetectorConfig(
    /** Nom de l'instance, utilisé dans les scores et les rapports. */
    val id: String,
    /** Type de détecteur (voir SignalDetectorFactory.type). Par défaut identique à l'id. */
    val type: String = id,
    val enabled: Boolean = true,
    /** score : contribue au score ; gate : 0 = hors jeu (menus, chargement), le score y est annulé. */
    val role: DetectorRole = DetectorRole.SCORE,
    val weight: Double = 1.0,
    /** Bonus ajouté au score d'une fenêtre par événement détecté (× confiance). */
    val eventBoost: Double = 0.0,
    /** Bonus par type d'événement (ex. kill: 0.8, assist: 0.3) ; à défaut, eventBoost. */
    val eventBoosts: Map<String, Double> = emptyMap(),
    val normalization: NormalizationConfig = NormalizationConfig(),
    val params: YamlNode? = null,
) {
    init {
        require(weight >= 0) { "détecteur '$id' : weight doit être ≥ 0" }
    }

    fun detectorParams() = DetectorParams(params)
}

/**
 * Normalisation robuste vers [0, 1] : le percentile bas vaut 0, le percentile haut vaut 1.
 * [minSpread] (dans l'unité brute du détecteur) évite d'amplifier un signal plat : une vidéo sans pic reste à des scores bas.
 */
@Serializable
data class NormalizationConfig(
    val lowPercentile: Double = 0.50,
    val highPercentile: Double = 0.98,
    val minSpread: Double = 0.0,
    /** Valeur maximale après normalisation : > 1 garde un classement entre moments très forts au lieu de les écraser à 1. */
    val maxValue: Double = 1.0,
) {
    init {
        require(lowPercentile in 0.0..1.0 && highPercentile in 0.0..1.0 && lowPercentile < highPercentile) {
            "normalization : 0 ≤ lowPercentile < highPercentile ≤ 1"
        }
    }
}

@Serializable
enum class DetectorRole {
    @SerialName("score") SCORE,
    @SerialName("gate") GATE,
}
