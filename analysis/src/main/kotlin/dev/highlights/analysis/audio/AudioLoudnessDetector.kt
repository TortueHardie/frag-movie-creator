package dev.highlights.analysis.audio

import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.serialization.SerialDuration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

@Serializable
data class AudioLoudnessParams(
    /**
     * Rôle de la piste à analyser (game, mic, mix) : la bonne piste est trouvée quel que soit l'enregistreur.
     * Par défaut le son du jeu. Ignoré si [stream] ou [titleContains] désigne une piste.
     */
    val role: AudioRole = AudioRole.GAME,
    /** Index de piste imposé (0:a:N). null = le rôle décide. */
    val stream: Int? = null,
    /** Piste utilisée si [stream] n'existe pas dans ce fichier (ex. capture à piste unique). */
    val fallbackStream: Int? = null,
    /** Sélection par titre de piste (sans casse), ex. "mic". */
    val titleContains: String? = null,
    /** Si vrai et que la piste n'existe pas dans ce fichier, le signal est simplement absent (pas d'erreur). */
    val optional: Boolean = false,
    /** Demi-largeur de la fenêtre de médiane locale servant de niveau de référence. */
    val localBaseline: SerialDuration = 30.seconds,
    /** Part du contraste local (pic soudain) vs contraste global (moment fort de la partie). */
    val localContrastWeight: Double = 0.7,
    /** Plancher en LUFS : le silence numérique (-120) est ramené ici. */
    val floorLufs: Double = -70.0,
)

/**
 * Pics de volume via le filtre ebur128 (loudness momentanée sur 400 ms, un point toutes les 100 ms).
 * Seule la piste audio est décodée : aucun décodage ni réencodage vidéo.
 *
 * Valeur brute par fenêtre, en LU : w·(max − médiane locale) + (1−w)·(max − médiane globale).
 */
class AudioLoudnessDetector(override val id: String, private val params: AudioLoudnessParams) : SignalDetector {

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        val grid = ctx.grid
        val stream = selectStream(ctx)
            ?: return missing(ctx, "piste audio ${wanted()} absente (${ctx.audio.describe()})")

        val windowMax = DoubleArray(grid.count) { Double.NaN }
        val parser = Ebur128MetadataParser { t, lufs -> accumulate(grid, windowMax, t, lufs) }
        val total = ctx.media.duration
        var lastReported = -1.0

        ctx.ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-i", ctx.media.path.toString(),
                    "-map", "0:a:${stream.audioIndex}", "-vn", "-sn", "-dn",
                    "-af", "ebur128=metadata=1,ametadata=mode=print:key=lavfi.r128.M:file=-",
                    "-f", "null", "-",
                ),
                "$id : loudness ${stream.label}",
            ),
            StdoutHandler.Lines { line ->
                parser.feed(line)?.let { t ->
                    val fraction = if (total.isPositive()) t / total else 0.0
                    if (fraction - lastReported >= 0.005) {
                        lastReported = fraction
                        ctx.progress.update(fraction, stream.label)
                    }
                }
            },
        )
        ctx.progress.complete()

        if (windowMax.all { it.isNaN() }) return missing(ctx, "aucune mesure de loudness produite pour ${stream.label}")
        log.info { "$id : ${parser.samples} mesures sur ${stream.label}" }

        val covered = parser.lastTime ?: Duration.ZERO
        val note = if (total.isPositive() && covered < total * 0.9) {
            "audio lu jusqu'à ${covered.inWholeSeconds}s sur ${total.inWholeSeconds}s (fichier tronqué ou en cours d'écriture ?)"
                .also { log.warn { "$id : $it" } }
        } else {
            null
        }
        return SignalTrack(id, LoudnessContrast.compute(windowMax, grid, params.localBaseline, params.localContrastWeight), note = note)
    }

    /** Libellé de la piste demandée, pour les messages. */
    private fun wanted(): String = params.titleContains?.let { "« $it »" }
        ?: params.stream?.let { "a:$it" }
        ?: "du rôle ${params.role.name.lowercase()}"

    private fun selectStream(ctx: AnalysisContext): AudioStream? {
        val streams = ctx.media.audio
        params.titleContains?.let { needle ->
            streams.firstOrNull { it.title?.contains(needle, ignoreCase = true) == true }?.let { return it }
        }
        val explicit = params.stream
        if (explicit != null) {
            streams.getOrNull(explicit)?.let { return it }
            return params.fallbackStream?.let { fallback ->
                streams.getOrNull(fallback)?.also { log.info { "$id : piste a:$explicit absente, repli sur ${it.label}" } }
            }
        }
        return ctx.audio[params.role]?.also { log.info { "$id : rôle ${params.role.name.lowercase()} → ${it.label}" } }
    }

    private fun accumulate(grid: WindowGrid, windowMax: DoubleArray, t: Duration, lufs: Double) {
        val value = if (lufs.isNaN()) params.floorLufs else maxOf(lufs, params.floorLufs)
        for (i in grid.indicesCovering(t)) {
            if (windowMax[i].isNaN() || value > windowMax[i]) windowMax[i] = value
        }
    }

    private fun missing(ctx: AnalysisContext, reason: String): SignalTrack {
        if (params.optional) log.info { "$id ignoré : $reason" } else log.warn { "$id : $reason" }
        ctx.progress.complete()
        return SignalTrack.missing(id, ctx.grid.count, reason)
    }
}

class AudioLoudnessDetectorFactory : SignalDetectorFactory {
    override val type = "audio-loudness"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        AudioLoudnessDetector(id, params.decode(AudioLoudnessParams.serializer()) { AudioLoudnessParams() })
}

/** Parse la sortie de `ametadata=mode=print` : une ligne "frame:… pts_time:T" suivie de "lavfi.r128.M=V". */
class Ebur128MetadataParser(private val onSample: (Duration, Double) -> Unit) {
    private var currentTime: Duration? = null
    var samples = 0
        private set
    var lastTime: Duration? = null
        private set

    /** Retourne l'instant de la mesure si la ligne en complète une. */
    fun feed(line: String): Duration? {
        val trimmed = line.trim()
        if (trimmed.startsWith("frame:")) {
            currentTime = PTS_TIME.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()?.seconds
            return null
        }
        if (trimmed.startsWith("lavfi.r128.M=")) {
            val t = currentTime ?: return null
            val value = trimmed.substringAfter('=').toDoubleOrNull() ?: Double.NaN
            samples++
            lastTime = t
            onSample(t, value)
            return t
        }
        return null
    }

    private companion object {
        val PTS_TIME = Regex("""pts_time:(-?\d+(?:\.\d+)?)""")
    }
}

internal object LoudnessContrast {
    fun compute(windowMax: DoubleArray, grid: WindowGrid, localBaseline: Duration, localWeight: Double): DoubleArray {
        val valid = windowMax.filterNot { it.isNaN() }
        val globalMedian = median(valid)
        val radius = (localBaseline / grid.hop).toInt().coerceAtLeast(1)
        val w = localWeight.coerceIn(0.0, 1.0)
        val buffer = ArrayList<Double>(2 * radius + 1)

        return DoubleArray(windowMax.size) { i ->
            val v = windowMax[i]
            if (v.isNaN()) return@DoubleArray Double.NaN
            buffer.clear()
            for (j in maxOf(0, i - radius)..minOf(windowMax.lastIndex, i + radius)) {
                if (!windowMax[j].isNaN()) buffer += windowMax[j]
            }
            w * (v - median(buffer)) + (1 - w) * (v - globalMedian)
        }
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
