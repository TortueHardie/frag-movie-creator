package dev.highlights.ml

import dev.highlights.core.ConfigException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalSegment
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.SerialDuration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

@Serializable
data class AudioEventKind(
    /** Noms de classes AudioSet (colonne display_name de yamnet_class_map.csv). */
    val classes: List<String>,
    /** Score (max des classes) à partir duquel un patch compte. */
    val threshold: Double,
)

@Serializable
data class AudioEventParams(
    /** Rôle de la piste analysée : le micro, quel que soit son index chez cet enregistreur. */
    val role: AudioRole = AudioRole.MIC,
    /** Index de piste imposé (0:a:N). null = le rôle décide. */
    val stream: Int? = null,
    val optional: Boolean = true,
    val model: String = "models/yamnet.onnx",
    val classMap: String = "models/yamnet_class_map.csv",
    val kinds: Map<String, AudioEventKind> = mapOf(
        "laughter" to AudioEventKind(listOf("Laughter", "Giggle", "Chuckle, chortle", "Snicker", "Belly laugh", "Baby laughter"), 0.25),
        "shout" to AudioEventKind(listOf("Shout", "Yell", "Screaming", "Whoop", "Bellow", "Cheering"), 0.35),
    ),
    /** Type dont le score alimente la valeur par fenêtre (les autres ne produisent que segments et événements). */
    val scoreKind: String = "laughter",
    /** Patchs plus silencieux ignorés sans passer par le modèle (micro coupé la plupart du temps). */
    val silenceDb: Double = -50.0,
    /** Deux détections séparées de moins que ça forment un seul segment. */
    val mergeGap: SerialDuration = 1.seconds,
    val minDuration: SerialDuration = 500.milliseconds,
    val batchSize: Int = 32,
)

/**
 * Rires, cris et exclamations par YAMNet (hors ligne, ONNX Runtime). Seul l'audio de la piste est décodé (16 kHz mono),
 * le spectrogramme est calculé en flux et seuls les passages non silencieux passent par le modèle.
 */
class AudioEventDetector(override val id: String, private val params: AudioEventParams) : SignalDetector {

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        val stream = if (params.stream != null) ctx.media.audio.getOrNull(params.stream) else ctx.audio[params.role]
        if (stream == null) {
            val note = "piste ${params.stream?.let { "a:$it" } ?: "(rôle ${params.role.name.lowercase()})"} absente"
            if (params.optional) log.info { "$id ignoré : $note" } else log.warn { "$id : $note" }
            ctx.progress.complete()
            return SignalTrack.missing(id, ctx.grid.count, note)
        }
        val classNames = YamnetClassifier.loadClassNames(ctx.configDir.resolve(params.classMap))
        val kindIndices = params.kinds.mapValues { (kind, spec) ->
            spec.classes.map { name ->
                classNames.indexOf(name).takeIf { it >= 0 } ?: throw ConfigException("$id : classe AudioSet inconnue '$name' (type $kind)")
            }
        }
        val scoresByKind = params.kinds.keys.associateWith { mutableMapOf<Int, Double>() }
        val topClasses = sortedMapOf<String, Int>()
        val total = ctx.media.duration
        var analyzed = 0
        var skipped = 0

        withContext(Dispatchers.IO) {
            YamnetClassifier(ctx.configDir.resolve(params.model)).use { model ->
                val pending = mutableListOf<Pair<Int, FloatArray>>()
                fun flush() {
                    if (pending.isEmpty()) return
                    val results = model.classify(pending.map { it.second })
                    pending.forEachIndexed { i, (index, _) ->
                        results[i].withIndex().maxBy { it.value }.let { top -> topClasses.merge(classNames[top.index], 1, Int::plus) }
                        for ((kind, indices) in kindIndices) {
                            scoresByKind.getValue(kind)[index] = indices.maxOf { results[i][it].toDouble() }
                        }
                    }
                    analyzed += pending.size
                    pending.clear()
                }
                fun report(index: Int) {
                    if (index % 200 == 0 && total.isPositive()) {
                        ctx.progress.update((LogMelStream.patchStartSeconds(index).seconds / total).coerceAtMost(0.99))
                    }
                }
                // Le seuil de silence descend dans le spectrogramme : un patch sous ce niveau ne coûte
                // même pas sa FFT (micro coupé la plupart du temps).
                val stream16k = LogMelStream(
                    silenceDb = params.silenceDb,
                    onSkipped = { index ->
                        skipped++
                        report(index)
                    },
                ) { index, patch, _ ->
                    pending += index to patch
                    if (pending.size >= params.batchSize) flush()
                    report(index)
                }
                ctx.ffmpeg.run(
                    FfmpegCommand(
                        listOf(
                            "-i", ctx.media.path.toString(), "-map", "0:a:${stream.audioIndex}", "-vn", "-sn", "-dn",
                            "-ac", "1", "-ar", "${LogMelStream.SAMPLE_RATE}", "-f", "f32le", "-acodec", "pcm_f32le", "pipe:1",
                        ),
                        "$id : événements audio ${stream.label}",
                    ),
                    StdoutHandler.Binary { input ->
                        val data = DataInputStream(input.buffered(1 shl 16))
                        val bytes = ByteArray(1 shl 16)
                        val floats = FloatArray(bytes.size / 4)
                        var carry = 0
                        while (true) {
                            val read = data.read(bytes, carry, bytes.size - carry)
                            if (read < 0) break
                            val available = carry + read
                            val whole = available / 4
                            ByteBuffer.wrap(bytes, 0, whole * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats, 0, whole)
                            stream16k.push(floats, whole)
                            carry = available - whole * 4
                            if (carry > 0) System.arraycopy(bytes, whole * 4, bytes, 0, carry)
                        }
                        flush()
                    },
                )
            }
        }
        ctx.progress.complete()

        val segments = mutableListOf<SignalSegment>()
        val events = mutableListOf<SignalEvent>()
        for ((kind, spec) in params.kinds) {
            val hits = scoresByKind.getValue(kind).filterValues { it >= spec.threshold }.toSortedMap()
            var current: TimeRange? = null
            var best = 0.0
            fun close() {
                val r = current ?: return
                if (r.length >= params.minDuration) {
                    segments += SignalSegment(r, kind, best)
                    events += SignalEvent(r.start, kind, best)
                }
                current = null
                best = 0.0
            }
            for ((index, score) in hits) {
                val start = LogMelStream.patchStartSeconds(index).seconds
                val range = TimeRange(start, minOf(start + LogMelStream.PATCH_SECONDS.seconds, total).coerceAtLeast(start))
                val c = current
                if (c != null && range.start <= c.end + params.mergeGap) {
                    current = c.union(range)
                } else {
                    close()
                    current = range
                }
                best = maxOf(best, score)
            }
            close()
        }
        log.info {
            "$id : $analyzed patchs analysés, $skipped silencieux ignorés, " +
                segments.groupingBy { it.kind }.eachCount().entries.joinToString { "${it.value} ${it.key}" }.ifEmpty { "aucun événement" }
        }

        // Aide au réglage des seuils : classes dominantes entendues et meilleurs scores par type.
        log.info { "$id : classes dominantes ${topClasses.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}=${it.value}" }}" }
        for ((kind, scores) in scoresByKind) {
            val best = scores.entries.sortedByDescending { it.value }.take(10)
                .joinToString { "%.0fs=%.2f".format(java.util.Locale.ROOT, LogMelStream.patchStartSeconds(it.key), it.value) }
            log.info { "$id : meilleurs scores '$kind' (seuil ${params.kinds.getValue(kind).threshold}) : $best" }
        }

        val grid = ctx.grid
        val raw = DoubleArray(grid.count)
        scoresByKind[params.scoreKind]?.forEach { (index, score) ->
            val start = LogMelStream.patchStartSeconds(index).seconds
            val center = start + (LogMelStream.PATCH_SECONDS / 2).seconds
            for (w in grid.indicesCovering(center)) raw[w] = maxOf(raw[w], score)
        }
        return SignalTrack(id, raw, events.sortedBy { it.at }, segments = segments.sortedBy { it.range.start })
    }
}

class AudioEventDetectorFactory : SignalDetectorFactory {
    override val type = "audio-events"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        AudioEventDetector(id, params.decode(AudioEventParams.serializer()) { AudioEventParams() })
}

