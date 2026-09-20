package dev.highlights.analysis.audio

import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalSegment
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.SerialDuration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

@Serializable
data class VoiceActivityParams(
    /** Rôle de la piste écoutée : le micro, quel que soit son index chez cet enregistreur. */
    val role: AudioRole = AudioRole.MIC,
    /** Index de piste imposé (0:a:N). null = le rôle décide. */
    val stream: Int? = null,
    val optional: Boolean = true,
    /** En dessous : silence. Les micros avec noise gate descendent vers -110 dB, la voix est au-dessus de -40 dB. */
    val silenceDb: Double = -50.0,
    /** Une pause plus courte ne coupe pas la phrase. */
    val minSilence: SerialDuration = 800.milliseconds,
    /** Les bruits plus courts (clic, souffle) sont ignorés. */
    val minSpeech: SerialDuration = 250.milliseconds,
    val kind: String = "speech",
)

/**
 * Intervalles où la voix est active (silencedetect de FFmpeg sur la piste micro, audio seul).
 * Sert à ne jamais couper une phrase ou un rire, et donne un signal « on parle » par fenêtre.
 */
class VoiceActivityDetector(override val id: String, private val params: VoiceActivityParams) : SignalDetector {

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        val stream = if (params.stream != null) ctx.media.audio.getOrNull(params.stream) else ctx.audio[params.role]
        if (stream == null) {
            val note = "piste micro ${params.stream?.let { "a:$it" } ?: "(rôle ${params.role.name.lowercase()})"} absente"
            if (params.optional) log.info { "$id ignoré : $note" } else log.warn { "$id : $note" }
            ctx.progress.complete()
            return SignalTrack.missing(id, ctx.grid.count, note)
        }
        val total = ctx.media.duration
        val parser = SilenceParser()
        var lastReported = 0.0

        ctx.ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-i", ctx.media.path.toString(), "-map", "0:a:${stream.audioIndex}", "-vn", "-sn", "-dn",
                    "-af", "silencedetect=n=${params.silenceDb}dB:d=${Durations.ffmpegSeconds(params.minSilence)}",
                    "-progress", "pipe:1", "-f", "null", "-",
                ),
                "$id : activité vocale ${stream.label}",
            ),
            stdout = dev.highlights.core.ffmpeg.StdoutHandler.Lines { line ->
                dev.highlights.core.ffmpeg.FfmpegProgressParser.parseOutTime(line)?.let { t ->
                    val f = if (total.isPositive()) t / total else 0.0
                    if (f - lastReported >= 0.01) {
                        lastReported = f
                        ctx.progress.update(f.coerceAtMost(0.99))
                    }
                }
            },
            onStderrLine = parser::feed,
        )
        ctx.progress.complete()

        val speech = parser.speechRanges(total).filter { it.length >= params.minSpeech }
        log.info { "$id : ${speech.size} prises de parole, ${speech.fold(Duration.ZERO) { a, r -> a + r.length }.inWholeSeconds} s au total" }

        val grid = ctx.grid
        val raw = DoubleArray(grid.count)
        for (r in speech) {
            for (i in grid.indicesCovering(r.start).first..grid.indicesCovering(r.end).last.coerceAtLeast(0)) {
                if (i !in raw.indices) continue
                val w = grid.rangeOf(i)
                val overlap = minOf(w.end, r.end) - maxOf(w.start, r.start)
                if (overlap.isPositive()) raw[i] += overlap / w.length
            }
        }
        return SignalTrack(id, raw, segments = speech.map { SignalSegment(it, params.kind) })
    }
}

/** Lit « silence_start: X » / « silence_end: Y » sur stderr et en déduit les intervalles non silencieux. */
internal class SilenceParser {
    private val silences = mutableListOf<Pair<Duration, Duration?>>()
    private val start = Regex("""silence_start:\s*(-?[\d.]+)""")
    private val end = Regex("""silence_end:\s*(-?[\d.]+)""")

    fun feed(line: String) {
        if (!line.contains("silencedetect")) return
        start.find(line)?.let { silences += it.groupValues[1].toDouble().coerceAtLeast(0.0).seconds to null }
        end.find(line)?.let { m ->
            val t = m.groupValues[1].toDouble().seconds
            val last = silences.lastOrNull()
            if (last != null && last.second == null) silences[silences.lastIndex] = last.first to t
        }
    }

    fun speechRanges(total: Duration): List<TimeRange> {
        val result = mutableListOf<TimeRange>()
        var cursor = Duration.ZERO
        for ((s, e) in silences) {
            if (s > cursor) result += TimeRange(cursor, minOf(s, total))
            cursor = e ?: total
        }
        if (cursor < total) result += TimeRange(cursor, total)
        return result.filter { it.length.isPositive() }
    }
}

class VoiceActivityDetectorFactory : SignalDetectorFactory {
    override val type = "voice-activity"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        VoiceActivityDetector(id, params.decode(VoiceActivityParams.serializer()) { VoiceActivityParams() })
}
