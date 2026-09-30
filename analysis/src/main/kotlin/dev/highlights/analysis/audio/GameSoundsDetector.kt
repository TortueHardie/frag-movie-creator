package dev.highlights.analysis.audio

import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioRole
import dev.highlights.core.serialization.SerialDuration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Tirs du joueur : attaques du son du jeu assez fortes pour venir de son arme ([OnsetStream], [ShotFilter]). */
@Serializable
data class ShotSounds(
    val kind: String = "shot",
    /** Montée d'énergie minimale (dB) d'une attaque. */
    val minRiseDb: Double = 9.0,
    /** Deux attaques plus proches sont le même tir (la cadence la plus rapide de VALORANT : 75 ms entre deux balles). */
    val minGap: SerialDuration = 40.milliseconds,
    /** Niveau de référence de l'arme du joueur : ce percentile des niveaux d'attaque de la partie… */
    val percentile: Double = 0.9,
    /** … et l'écart en dessous duquel une attaque n'est plus un tir du joueur. */
    val belowDb: Double = 10.0,
)

/** Un son précis du jeu, reconnu à son empreinte ([TemplateMatcher]) : le « dink » d'un tir à la tête, par exemple. */
@Serializable
data class SoundTemplate(
    /** Extrait du son seul (wav, flac… tout ce que lit FFmpeg), relatif au dossier de configuration. */
    val file: String,
    /** Ressemblance (-1 à 1) à partir de laquelle le son est reconnu. */
    val threshold: Double = 0.75,
    /** Bandes comparées : restreindre aux fréquences propres au son l'isole des tirs qui le couvrent. */
    val minHz: Double = 1000.0,
    val maxHz: Double = 12000.0,
    val bands: Int = 24,
    /** Deux reconnaissances plus proches sont le même son. */
    val minGap: SerialDuration = 150.milliseconds,
)

@Serializable
data class GameSoundsParams(
    val role: AudioRole = AudioRole.GAME,
    /** Index de piste imposé (0:a:N). null = le rôle décide. */
    val stream: Int? = null,
    val optional: Boolean = true,
    /** Tirs du joueur ; null : pas de tirs. */
    val shots: ShotSounds? = ShotSounds(),
    /** Sons à reconnaître, par type d'événement émis (« headshot » : le tir à la tête). */
    val sounds: Map<String, SoundTemplate> = emptyMap(),
    /** Décalage ajouté aux sons reconnus, pour les caler sur l'instant des autres événements du même type. */
    val offset: SerialDuration = Duration.ZERO,
)

/**
 * Sons du jeu, instant par instant : chaque tir du joueur (événement `shot`) et les sons reconnus à leur gabarit (le
 * tir à la tête, `headshot`). Ne dépend ni d'Outplayed ni de l'image : marche sur toute capture qui a le son du jeu.
 * La piste est lue une fois, en flux (48 kHz mono) ; aucune valeur par fenêtre, que des événements.
 *
 * Un gabarit se prépare une fois par jeu, depuis une capture : l'extrait le plus propre possible du son, coupé au ras
 * de son début, par exemple `ffmpeg -ss 754.32 -t 0.25 -i partie.mp4 -map 0:a:0 -ac 1 headshot.wav`.
 */
class GameSoundsDetector(override val id: String, private val params: GameSoundsParams) : SignalDetector {

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        val stream = if (params.stream != null) ctx.media.audio.getOrNull(params.stream) else ctx.audio[params.role]
            ?: ctx.audio.mixIndices().firstOrNull()?.let { ctx.media.audio.getOrNull(it) }
        if (stream == null) return missing(ctx, "piste ${params.stream?.let { "a:$it" } ?: "du jeu"} absente")

        val matchers = params.sounds.mapNotNull { (kind, spec) ->
            val file = ctx.configDir.resolve(spec.file)
            if (!file.isRegularFile()) {
                log.warn { "$id : gabarit '$kind' introuvable ($file), son non recherché" }
                return@mapNotNull null
            }
            val fingerprint = TemplateMatcher.fingerprint(decode(ctx.ffmpeg, file, "$id : gabarit $kind"), RATE, spec.bands, spec.minHz, spec.maxHz)
            if (fingerprint.size < 2) {
                log.warn { "$id : gabarit '$kind' trop court ou muet ($file)" }
                return@mapNotNull null
            }
            Recognizer(kind, spec, TemplateMatcher(fingerprint))
        }
        if (params.shots == null && matchers.isEmpty()) return missing(ctx, "ni tirs ni gabarit utilisable")

        val onsets = params.shots?.let { OnsetStream(RATE) }
        val total = ctx.media.duration
        var decoded = 0L
        var lastReported = 0.0
        ctx.ffmpeg.run(
            FfmpegCommand(pcmArgs(ctx.media.path, stream.audioIndex), "$id : sons du jeu ${stream.label}"),
            StdoutHandler.Binary { input ->
                readFloats(input) { chunk, n ->
                    onsets?.push(chunk, n)
                    matchers.forEach { it.spectrum.push(chunk, n) }
                    decoded += n
                    val f = if (total.isPositive()) decoded.toDouble() / RATE / total.inWholeMilliseconds * 1000 else 0.0
                    if (f - lastReported >= 0.01) {
                        lastReported = f
                        ctx.progress.update(f.coerceAtMost(0.99), stream.label)
                    }
                }
            },
        )
        ctx.progress.complete()

        val events = mutableListOf<SignalEvent>()
        params.shots?.let { spec ->
            val all = onsets!!.onsets(spec.minRiseDb, spec.minGap.inWholeMicroseconds / 1e6)
            val kept = ShotFilter.keep(all, spec.percentile, spec.belowDb)
            log.info { "$id : ${all.size} attaques, ${kept.size} tirs du joueur" }
            kept.forEach { events += SignalEvent(it.seconds.seconds.coerceAtMost(total), spec.kind, (it.riseDb / 30).coerceIn(0.0, 1.0)) }
        }
        for (m in matchers) {
            val gap = (m.spec.minGap.inWholeMicroseconds / 1e6 * RATE / m.spectrum.hop).toInt().coerceAtLeast(1)
            val peaks = TemplateMatcher.peaks(m.scores, m.count, m.spec.threshold, gap)
            // Chaque ressemblance est celle du passage qui finit sur sa trame : le son a commencé un gabarit plus tôt.
            val start = m.length - 1
            peaks.forEach { i ->
                val at = (m.spectrum.time(i - start).seconds + params.offset).coerceIn(Duration.ZERO, total)
                events += SignalEvent(at, m.kind, m.scores[i].toDouble())
            }
            val best = (0 until m.count).map { m.scores[it] }.filterNot { it.isNaN() }.sortedDescending().take(5)
            log.info {
                "$id : ${peaks.size} '${m.kind}' reconnu(s) (seuil ${m.spec.threshold}, meilleures ressemblances " +
                    best.joinToString { "%.2f".format(Locale.ROOT, it) } + ")"
            }
        }
        val raw = DoubleArray(ctx.grid.count) { Double.NaN }
        return SignalTrack(id, raw, events.sortedBy { it.at }, note = "aucun son reconnu".takeIf { events.isEmpty() })
    }

    /** Un gabarit et ses ressemblances trame par trame, calculées pendant le décodage. */
    private class Recognizer(val kind: String, val spec: SoundTemplate, private val matcher: TemplateMatcher) {
        val length = matcher.length
        var scores = FloatArray(1 shl 16)
        var count = 0
        val spectrum = BandSpectrum(RATE, spec.bands, spec.minHz, spec.maxHz) { frame ->
            if (count == scores.size) scores = scores.copyOf(count * 2)
            scores[count++] = matcher.push(frame).toFloat()
        }
    }

    private fun missing(ctx: AnalysisContext, reason: String): SignalTrack {
        if (params.optional) log.info { "$id ignoré : $reason" } else log.warn { "$id : $reason" }
        ctx.progress.complete()
        return SignalTrack.missing(id, ctx.grid.count, reason)
    }

    companion object {
        const val RATE = 48_000

        private fun pcmArgs(path: Path, audioIndex: Int) = listOf(
            "-i", path.toString(), "-map", "0:a:$audioIndex", "-vn", "-sn", "-dn",
            "-ac", "1", "-ar", "$RATE", "-f", "f32le", "-acodec", "pcm_f32le", "pipe:1",
        )

        private suspend fun decode(ffmpeg: FfmpegService, file: Path, label: String): FloatArray {
            var result = FloatArray(0)
            ffmpeg.run(
                FfmpegCommand(pcmArgs(file, 0), label),
                StdoutHandler.Binary { input ->
                    val bytes = input.readAllBytes()
                    result = FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
                },
            )
            return result
        }

        /** Lit des flottants little-endian par blocs, sans jamais couper un échantillon entre deux lectures. */
        internal fun readFloats(input: InputStream, onChunk: (FloatArray, Int) -> Unit) {
            val stream = input.buffered(1 shl 16)
            val bytes = ByteArray(1 shl 16)
            val floats = FloatArray(bytes.size / 4)
            var carry = 0
            while (true) {
                val read = stream.read(bytes, carry, bytes.size - carry)
                if (read < 0) break
                val available = carry + read
                val whole = available / 4
                ByteBuffer.wrap(bytes, 0, whole * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats, 0, whole)
                if (whole > 0) onChunk(floats, whole)
                carry = available - whole * 4
                if (carry > 0) System.arraycopy(bytes, whole * 4, bytes, 0, carry)
            }
        }
    }
}

class GameSoundsDetectorFactory : SignalDetectorFactory {
    override val type = "game-sounds"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        GameSoundsDetector(id, params.decode(GameSoundsParams.serializer()) { GameSoundsParams() })
}
