package dev.highlights.core.analysis

import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import dev.highlights.core.ConfigException
import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.progress.ProgressReporter
import kotlinx.serialization.DeserializationStrategy
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Un signal d'intérêt calculé sur toute la vidéo (volume, mouvement, éléments d'interface…).
 * Contrainte : ne jamais réencoder, ne travailler que sur un flux décodé (audio, basse résolution, frames extraites).
 */
interface SignalDetector {
    /** Identifiant de l'instance dans le profil (ex. "game-audio"). */
    val id: String

    suspend fun analyze(ctx: AnalysisContext): SignalTrack
}

/** Découvert via [java.util.ServiceLoader] : ajouter un détecteur ne demande aucune modification ailleurs. */
interface SignalDetectorFactory {
    /** Type référencé dans les profils (ex. "audio-loudness"). */
    val type: String

    fun create(id: String, params: DetectorParams): SignalDetector
}

class AnalysisContext(
    val media: MediaInfo,
    val grid: WindowGrid,
    val ffmpeg: FfmpegService,
    /** Dossier temporaire propre au job, supprimé à la fin. */
    val workDir: Path,
    val progress: ProgressReporter,
    /** Dossier de configuration (app.yaml) : base des chemins relatifs des paramètres (modèles d'images…). */
    val configDir: Path = workDir,
)

/**
 * Valeurs brutes par fenêtre, dans l'unité du détecteur (LU, ratio de pixels…). La normalisation est faite par le scoring.
 * NaN = pas de donnée pour cette fenêtre (piste absente, fin de flux…).
 */
class SignalTrack(
    val detectorId: String,
    val raw: DoubleArray,
    val events: List<SignalEvent> = emptyList(),
    /** Raison pour laquelle le signal est vide, le cas échéant. */
    val note: String? = null,
    /** Intervalles étiquetés (voix, rire…) : utilisés pour ne pas couper un moment au milieu. */
    val segments: List<SignalSegment> = emptyList(),
) {
    val isMissing: Boolean get() = raw.all { it.isNaN() } && events.isEmpty() && segments.isEmpty()

    companion object {
        fun missing(detectorId: String, windows: Int, note: String) =
            SignalTrack(detectorId, DoubleArray(windows) { Double.NaN }, note = note)
    }
}

data class SignalEvent(val at: Duration, val kind: String, val confidence: Double)

data class SignalSegment(val range: TimeRange, val kind: String, val confidence: Double = 1.0)

/** Paramètres libres d'un détecteur, décodés depuis le YAML du profil dans la classe propre au détecteur. */
class DetectorParams(val node: YamlNode?) {
    fun <T> decode(deserializer: DeserializationStrategy<T>, default: () -> T): T {
        if (node == null || node is YamlNull) return default()
        return try {
            ConfigYaml.yaml.decodeFromYamlNode(deserializer, node)
        } catch (e: Exception) {
            throw ConfigException("Paramètres de détecteur invalides (${node.path.toHumanReadableString()}) : ${e.message}", e)
        }
    }

    companion object {
        val EMPTY = DetectorParams(null)
    }
}
