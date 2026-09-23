package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.KillStyle
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ShotAlign
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.Durations
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isRegularFile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Regarde chaque kill de près avant le montage : son instant est recalé sur le son du tir ([ShotLocator]), et la
 * rotation de la caméra juste avant dit si c'est un flick ([FlickMeter]). Seule une demi-seconde de son et d'image est
 * décodée autour de chaque kill. Un kill qu'on n'arrive pas à lire garde ce qu'on en savait.
 */
class KillInspector(private val ffmpeg: FfmpegService) {

    suspend fun inspect(groups: List<KillGroup>, settings: MontageSettings, layout: AudioLayout, progress: ProgressReporter): List<KillGroup> {
        val align = settings.shotAlign
        val style = settings.killStyle
        if (!align.enabled && !style.flick) return groups
        val total = groups.sumOf { it.kills.size }
        val done = AtomicInteger()
        val semaphore = Semaphore(PARALLELISM)
        // Une capture déplacée ou effacée depuis l'analyse : on le dit une fois, pas à chaque kill.
        val missing = groups.map { it.media.path }.distinct().filterNot { it.isRegularFile() }.toSet()
        missing.forEach { log.warn { "Capture introuvable, kills gardés tels qu'analysés : $it" } }
        val inspected = coroutineScope {
            groups.map { group ->
                if (group.media.path in missing) return@map async { group }
                async {
                    val results = group.kills.map { kill ->
                        semaphore.withPermit {
                            inspectKill(group.media, kill, group.traitsOf(kill), align, style, layout).also {
                                progress.update(done.incrementAndGet().toDouble() / total, "kill ${done.get()}/$total")
                            }
                        }
                    }
                    MontagePlanner.withTraits(group, results.map { it.first }, results.map { it.second }, style)
                }
            }.awaitAll()
        }
        val kills = inspected.flatMap { g -> g.kills.map { g.traitsOf(it) } }
        val shifted = kills.filter { it.shift != Duration.ZERO }
        log.info {
            "Kills inspectés : $total, ${shifted.size} recalés sur le tir" +
                (if (shifted.isEmpty()) "" else " (écart moyen ${shifted.map { it.shift.inWholeMilliseconds }.average().roundToInt()} ms)") +
                ", ${kills.count { it.flick >= KillTraits.STRONG_FLICK }} flick(s), ${kills.count { it.headshot }} tir(s) à la tête"
        }
        progress.complete()
        return inspected
    }

    private suspend fun inspectKill(
        media: MediaInfo,
        kill: Duration,
        traits: KillTraits,
        align: ShotAlign,
        style: KillStyle,
        layout: AudioLayout,
    ): Pair<Duration, KillTraits> {
        var at = kill
        var result = traits
        if (align.enabled) {
            try {
                val shot = locateShot(media, kill, align, layout)
                if (shot != null) {
                    at = shot
                    result = result.copy(shift = shot - kill)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "Recalage du kill ${Durations.format(kill)} de ${media.path.fileName} impossible : ${e.message}" }
            }
        }
        if (style.flick && media.video != null) {
            try {
                result = result.copy(flick = measureFlick(media, at, style))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "Mesure du flick au kill ${Durations.format(at)} de ${media.path.fileName} impossible : ${e.message}" }
            }
        }
        return at to result
    }

    private suspend fun locateShot(media: MediaInfo, kill: Duration, align: ShotAlign, layout: AudioLayout): Duration? {
        val tracks = AudioTracks.of(media.audio, layout)
        val track = tracks.indexOf(AudioRole.GAME) ?: tracks.mixIndices().firstOrNull() ?: return null
        val start = (kill - align.before - ShotLocator.HISTORY).coerceAtLeast(Duration.ZERO)
        val end = (kill + align.after + ShotLocator.TAIL).coerceAtMost(media.duration)
        if (end <= start) return null
        var samples = FloatArray(0)
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSecondsPrecise(start), "-t", Durations.ffmpegSeconds(end - start), "-i", media.path.toString(),
                    "-map", "0:a:$track", "-vn", "-ac", "1", "-ar", "${ShotLocator.RATE}", "-f", "f32le", "-acodec", "pcm_f32le", "pipe:1",
                ),
                "son du kill ${Durations.format(kill)}",
            ),
            StdoutHandler.Binary { input ->
                val bytes = input.readAllBytes()
                samples = FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            },
        )
        return ShotLocator.locate(samples, ShotLocator.RATE, start, kill, align)
    }

    private suspend fun measureFlick(media: MediaInfo, kill: Duration, style: KillStyle): Double {
        val video = media.video ?: return 0.0
        val width = FlickMeter.WIDTH
        val height = ((width.toDouble() * video.height / video.width) / 2).roundToInt().coerceAtLeast(8) * 2
        val start = (kill - FlickMeter.BEFORE).coerceAtLeast(Duration.ZERO)
        val end = (kill + FlickMeter.AFTER).coerceAtMost(media.duration)
        if (end <= start) return 0.0
        val frames = mutableListOf<ByteArray>()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSecondsPrecise(start), "-t", Durations.ffmpegSeconds(end - start), "-i", media.path.toString(),
                    "-an", "-vf", "fps=${FlickMeter.FPS},scale=$width:$height:flags=area,format=gray", "-f", "rawvideo", "pipe:1",
                ),
                "image du kill ${Durations.format(kill)}",
            ),
            StdoutHandler.Binary { input ->
                val bytes = input.readAllBytes()
                val size = width * height
                for (i in 0 until bytes.size / size) frames += bytes.copyOfRange(i * size, (i + 1) * size)
            },
        )
        val speeds = FlickMeter.speeds(frames, width, height, FlickMeter.FPS.toDouble())
        // Le kill tombe à (kill - start) dans l'extrait ; la vitesse i mesure le passage de l'image i à l'image i + 1.
        val killIndex = ((kill - start) / FlickMeter.FRAME).toInt()
        return FlickMeter.score(speeds, killIndex, style)
    }

    companion object {
        /** Décodages simultanés : de très courts extraits, surtout limités par le démarrage de FFmpeg. */
        private const val PARALLELISM = 4
    }
}

/**
 * Trouve le tir dans le son du jeu : les attaques (montée brusque d'énergie des aigus) proches de l'instant annoncé, et
 * parmi elles la plus nette, pondérée par sa distance à cet instant : une rafale compte plusieurs tirs, le plus proche
 * de la notification est le plus probablement celui qui tue.
 */
object ShotLocator {
    const val RATE = 48_000

    /** Son décodé avant la fenêtre de recherche : de quoi mesurer la montée de la première attaque. */
    val HISTORY = 30.milliseconds
    val TAIL = 10.milliseconds

    /** Trame d'énergie (5,3 ms) et pas (1,3 ms) : une attaque de tir tient en quelques millisecondes. */
    private const val FRAME = 256
    private const val HOP = 64

    /** L'énergie d'une trame est comparée à celle d'il y a 5 à 16 ms : un tir y monte, un son qui enfle non. */
    private const val PAST_FROM = 12
    private const val PAST_TO = 4

    /** Deux attaques à moins de 20 ms l'une de l'autre sont le même tir. */
    private const val PEAK_RADIUS = 15

    fun locate(samples: FloatArray, rate: Int, start: Duration, expected: Duration, align: ShotAlign): Duration? {
        val rise = rises(samples)
        if (rise.isEmpty()) return null
        fun time(i: Int) = start + ((i * HOP + FRAME / 2).toDouble() / rate).seconds
        val from = expected - align.before
        val to = expected + align.after
        val sigma = ((align.before + align.after) / 4).inWholeMicroseconds / 1e6
        var best: Int? = null
        var bestValue = 0.0
        for (i in rise.indices) {
            if (rise[i] < align.minRiseDb) continue
            val lo = maxOf(0, i - PEAK_RADIUS)
            val hi = minOf(rise.lastIndex, i + PEAK_RADIUS)
            if ((lo..hi).any { rise[it] > rise[i] || (rise[it] == rise[i] && it < i) }) continue
            val t = time(i)
            if (t < from || t > to) continue
            val dt = (t - expected).inWholeMicroseconds / 1e6
            val value = rise[i] * exp(-dt * dt / (2 * sigma * sigma))
            if (value > bestValue) {
                bestValue = value
                best = i
            }
        }
        return best?.let(::time)
    }

    /** Montée d'énergie (dB) de chaque trame sur son passé récent, après préaccentuation (les aigus portent l'attaque). */
    internal fun rises(samples: FloatArray): DoubleArray {
        if (samples.size < FRAME) return DoubleArray(0)
        val y = FloatArray(samples.size) { i -> if (i == 0) samples[0] else samples[i] - 0.97f * samples[i - 1] }
        val frames = (samples.size - FRAME) / HOP + 1
        val level = DoubleArray(frames) { f ->
            var sum = 0.0
            for (n in f * HOP until f * HOP + FRAME) sum += y[n].toDouble() * y[n]
            10 * log10(sum / FRAME + 1e-10)
        }
        return DoubleArray(frames) { i ->
            if (i < PAST_FROM) 0.0 else level[i] - (i - PAST_FROM..i - PAST_TO).maxOf { level[it] }
        }
    }
}

/**
 * Vitesse de rotation de la caméra, image par image : décalage horizontal et vertical de toute l'image, estimé en
 * alignant les profils de luminosité des colonnes et des lignes. Une rotation de la vue déplace toute la scène d'un
 * bloc ; un ennemi qui bouge dans une vue immobile, non. Un flick est un balayage rapide juste avant le kill, suivi d'un
 * arrêt sur la cible.
 */
object FlickMeter {
    const val WIDTH = 256
    const val FPS = 60
    val FRAME = (1.0 / FPS).seconds

    /** Fenêtre décodée autour du kill : le balayage le précède de quelques centaines de millisecondes. */
    val BEFORE = 450.milliseconds
    val AFTER = 50.milliseconds

    /** Recherche du balayage : jusqu'à 350 ms avant le kill. */
    private const val SEARCH_FRAMES = 21

    /** Décalage maximal cherché entre deux images, en part de la largeur (15 largeurs d'écran par seconde à 60 i/s). */
    private const val MAX_SHIFT = 0.25

    /** Bandes ignorées en haut et en bas (HUD, minicarte, barre de vie) pour le profil des colonnes. */
    private const val BAND = 0.15

    /** Vitesse de chaque passage d'une image à la suivante, en largeurs d'écran par seconde. */
    fun speeds(frames: List<ByteArray>, width: Int, height: Int, fps: Double): List<Double> {
        val profiles = frames.map { profiles(it, width, height) }
        return profiles.zipWithNext { a, b ->
            val dx = shift(a.first, b.first, (width * MAX_SHIFT).toInt())
            val dy = shift(a.second, b.second, (height * MAX_SHIFT).toInt())
            sqrt(dx * dx + dy * dy.toDouble()) / width * fps
        }
    }

    /**
     * Note de flick (0..1) : plus forte vitesse (moyennée sur deux images, contre le bruit) dans les 350 ms qui précèdent
     * le kill, entre [KillStyle.flickFrom] et [KillStyle.flickTo]. Une visée qui balaie encore au moment du kill (on
     * suit une cible, on ne s'arrête pas dessus) ne compte qu'à moitié.
     */
    fun score(speeds: List<Double>, killIndex: Int, style: KillStyle): Double {
        if (speeds.size < 2) return 0.0
        // smooth[i] couvre les images i à i + 2 : pour ne rien lire après le kill (on pivote souvent juste après,
        // vers la cible suivante), la dernière valeur lue est celle qui finit sur l'image du kill.
        val smooth = speeds.zipWithNext { a, b -> (a + b) / 2 }
        val last = (killIndex - 2).coerceIn(0, smooth.lastIndex)
        val window = (last - SEARCH_FRAMES).coerceAtLeast(0)..last
        val peak = window.maxOf { smooth[it] }
        val score = ((peak - style.flickFrom) / (style.flickTo - style.flickFrom)).coerceIn(0.0, 1.0)
        val atKill = smooth[last]
        return if (atKill > peak / 2) score / 2 else score
    }

    /** Profils moyens des colonnes (bande centrale) et des lignes, en dérivée : insensibles à un changement de luminosité. */
    private fun profiles(pixels: ByteArray, width: Int, height: Int): Pair<DoubleArray, DoubleArray> {
        val top = (height * BAND).toInt()
        val bottom = height - top
        val cols = DoubleArray(width)
        val rows = DoubleArray(height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = (pixels[y * width + x].toInt() and 0xFF).toDouble()
                if (y in top until bottom) cols[x] += v
                rows[y] += v
            }
        }
        return derivative(cols) to derivative(rows)
    }

    private fun derivative(p: DoubleArray) = DoubleArray(p.size - 1) { p[it + 1] - p[it] }

    /** Décalage entier (pixels) qui aligne le mieux [b] sur [a] : écart absolu moyen minimal sur la partie commune. */
    internal fun shift(a: DoubleArray, b: DoubleArray, max: Int): Int {
        var best = 0
        var bestCost = Double.MAX_VALUE
        for (s in -max..max) {
            var cost = 0.0
            var count = 0
            for (i in a.indices) {
                val j = i - s
                if (j < 0 || j >= b.size) continue
                cost += abs(a[i] - b[j])
                count++
            }
            if (count < a.size / 2) continue
            val mean = cost / count
            // À coût égal, le plus petit déplacement : une image uniforme ne bouge pas.
            if (mean < bestCost - 1e-9 || (abs(mean - bestCost) <= 1e-9 && abs(s) < abs(best))) {
                bestCost = mean
                best = s
            }
        }
        return best
    }
}
