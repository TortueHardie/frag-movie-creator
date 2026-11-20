package dev.highlights.cli

import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.serialization.toTimecode
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Vérité terrain d'une capture : les vrais événements du joueur (kills, morts…), relevés à l'œil et validés par lui,
 * contre lesquels on mesure les détecteurs. Fichier YAML versionné dans `verite/<jeu>/`, sans la vidéo : la capture
 * est reconnue par son nom, sa taille et sa durée (une autre capture du même nom ne doit pas passer pour elle).
 *
 * Seules les sortes listées dans [kinds] sont complètes : un détecteur qui sort autre chose (réanimations…) n'est ni
 * récompensé ni puni pour ça.
 */
@Serializable
data class GroundTruth(
    val capture: TruthCapture,
    /** Profil d'analyse (wardogs…). */
    val profile: String,
    /** Écart toléré entre l'instant relevé et l'instant détecté. */
    val tolerance: SerialDuration = 3.seconds,
    /**
     * Avance permise : un événement détecté peut précéder l'événement relevé jusqu'à cette durée (au moins [tolerance]).
     * Apex : le kill est la mise à terre, le compteur du HUD (la vérité) le crédite à l'élimination, 2 à 30 s plus tard.
     * Zéro : écart symétrique, [tolerance] des deux côtés.
     */
    val lead: SerialDuration = Duration.ZERO,
    /** Qui a validé la liste, et quand ; null = pas encore validée (la mesure le dit). */
    val validated: String? = null,
    /** Sortes d'événements relevées sans en oublier aucune. */
    val kinds: List<String>,
    val events: List<TruthEvent> = emptyList(),
    /** Seuils sous lesquels la vérification échoue : détecteur → sorte → minimum. */
    val thresholds: Map<String, Map<String, Threshold>> = emptyMap(),
    val note: String? = null,
) {
    init {
        require(kinds.isNotEmpty()) { "kinds vide : la vérité ne dit pas ce qu'elle relève" }
        events.forEach { require(it.kind in kinds) { "événement ${it.kind} à ${it.at.toTimecode()} hors des sortes relevées $kinds" } }
        require(tolerance.isPositive()) { "tolerance doit être > 0" }
        require(!lead.isNegative()) { "lead ne peut pas être négatif" }
    }

    companion object {
        fun load(file: Path): GroundTruth = ConfigYaml.decodeFile(file, serializer())
    }
}

@Serializable
data class TruthCapture(
    /** Nom du fichier vidéo, sans dossier. */
    val name: String,
    val sizeBytes: Long,
    val duration: SerialDuration,
)

@Serializable
data class TruthEvent(val at: SerialDuration, val kind: String, val note: String? = null)

@Serializable
data class Threshold(val precision: Double = 0.0, val recall: Double = 0.0) {
    init {
        require(precision in 0.0..1.0 && recall in 0.0..1.0) { "seuils entre 0 et 1" }
    }
}

/**
 * Mesure d'un détecteur sur une sorte d'événement : [hits] relevés retrouvés, [falseAlarms] détections sans vrai
 * événement à moins de la tolérance, [missed] vrais événements non détectés. Précision et rappel valent null quand ils
 * ne se calculent pas (aucune détection, aucun vrai événement) : « rien détecté, rien à détecter » n'est pas un échec.
 */
data class DetectorScore(
    val detector: String,
    val kind: String,
    val hits: List<Pair<Duration, Duration>>,
    val falseAlarms: List<Duration>,
    val missed: List<Duration>,
) {
    val precision: Double? get() = (hits.size + falseAlarms.size).takeIf { it > 0 }?.let { hits.size.toDouble() / it }
    val recall: Double? get() = (hits.size + missed.size).takeIf { it > 0 }?.let { hits.size.toDouble() / it }

    /** Raisons d'échec face au seuil (vide = réussi). Un critère non calculable ne fait pas échouer. */
    fun failures(threshold: Threshold?): List<String> {
        threshold ?: return emptyList()
        return listOfNotNull(
            precision?.takeIf { it < threshold.precision }?.let { "précision %.2f < %.2f".format(Locale.ROOT, it, threshold.precision) },
            recall?.takeIf { it < threshold.recall }?.let { "rappel %.2f < %.2f".format(Locale.ROOT, it, threshold.recall) },
        )
    }
}

object TruthScoring {
    /**
     * Compare les événements détectés à la vérité, détecteur par détecteur, pour chaque sorte relevée. Chaque vrai
     * événement ne compte qu'une détection et inversement : les paires sont formées de la plus proche à la plus
     * lointaine, dans la tolérance (deux kills à 1 s d'écart ne valident pas une seule détection deux fois).
     * Les détecteurs nommés dans les seuils sont mesurés même sans aucune détection (rappel 0).
     */
    fun score(truth: GroundTruth, detected: List<TimelineEvent>): List<DetectorScore> {
        val detectors = (detected.filter { it.kind in truth.kinds }.map { it.detectorId } + truth.thresholds.keys).distinct().sorted()
        return detectors.flatMap { detector ->
            truth.kinds.map { kind ->
                match(
                    detector, kind,
                    truth.events.filter { it.kind == kind }.map { it.at },
                    detected.filter { it.detectorId == detector && it.kind == kind }.map { it.at },
                    truth.tolerance,
                    truth.lead,
                )
            }
        }
    }

    fun match(detector: String, kind: String, expected: List<Duration>, found: List<Duration>, tolerance: Duration, lead: Duration = Duration.ZERO): DetectorScore {
        val before = maxOf(tolerance, lead).inWholeMilliseconds
        val pairs = expected.indices.flatMap { i -> found.indices.map { j -> Triple(i, j, (expected[i] - found[j]).inWholeMilliseconds) } }
            .filter { it.third in -tolerance.inWholeMilliseconds..before }
            .map { Triple(it.first, it.second, abs(it.third)) }
            .sortedWith(compareBy({ it.third }, { it.first }, { it.second }))
        val usedExpected = BooleanArray(expected.size)
        val usedFound = BooleanArray(found.size)
        val hits = mutableListOf<Pair<Duration, Duration>>()
        for ((i, j, _) in pairs) {
            if (usedExpected[i] || usedFound[j]) continue
            usedExpected[i] = true
            usedFound[j] = true
            hits += expected[i] to found[j]
        }
        return DetectorScore(
            detector, kind,
            hits.sortedBy { it.first },
            found.filterIndexed { j, _ -> !usedFound[j] }.sorted(),
            expected.filterIndexed { i, _ -> !usedExpected[i] }.sorted(),
        )
    }
}
