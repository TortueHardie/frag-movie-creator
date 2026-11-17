package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.serialization.SerialPath
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Highlight(
    val id: String,
    val source: SerialPath,
    val range: TimeRange,
    val peak: SerialDuration,
    /** Score fusionné au pic, dans [0, 1]. */
    val score: Double,
    /** Part de chaque signal dans le score au pic (somme = score, hors boosts d'événements). */
    val contributions: Map<String, Double> = emptyMap(),
    val enabled: Boolean = true,
    /** Nombre d'événements par type dans le segment, ex. {"kill": 2}. */
    val events: Map<String, Int> = emptyMap(),
    /** D'où vient le moment : détecté, retouché ou ajouté à la main. Un moment non détecté survit à un recalcul. */
    val origin: MomentOrigin = MomentOrigin.DETECTED,
    /** Rang dans le montage choisi à la main (null = ordre du profil) ; voir [ClipOrder.MANUAL]. */
    val order: Int? = null,
) {
    /** Retouché ou ajouté par l'utilisateur : le recalcul de la sélection le garde tel quel. */
    val manual: Boolean get() = origin != MomentOrigin.DETECTED
}

@Serializable
enum class MomentOrigin {
    @SerialName("detected") DETECTED,
    /** Début ou fin déplacés à la main. */
    @SerialName("adjusted") ADJUSTED,
    /** Ajouté à la main sur la timeline. */
    @SerialName("added") ADDED,
}

/** Score fusionné par fenêtre et contribution de chaque signal (NaN remplacé par 0 pour rester sérialisable). */
@Serializable
data class ScoredTimeline(
    val grid: WindowGrid,
    val total: List<Double>,
    val contributions: Map<String, List<Double>>,
    /** Événements ponctuels détectés (kills…), triés par instant. */
    val events: List<TimelineEvent> = emptyList(),
    /** Fenêtres hors jeu (menus, chargement) exclues par un détecteur de rôle gate. */
    val excluded: List<TimeRange> = emptyList(),
    /** Intervalles étiquetés (voix, rire…), triés par début. */
    val segments: List<TimelineSegment> = emptyList(),
) {
    init {
        require(total.size == grid.count) { "timeline : ${total.size} scores pour ${grid.count} fenêtres" }
    }
}

@Serializable
data class TimelineEvent(
    val at: SerialDuration,
    val kind: String,
    val confidence: Double,
    val detectorId: String,
)

@Serializable
data class TimelineSegment(
    val range: TimeRange,
    val kind: String,
    val confidence: Double,
    val detectorId: String,
)
