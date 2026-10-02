package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.MotionBlur
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.Durations
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isRegularFile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * Rotation de la caméra sur la source d'un plan, à partir de [from] : [motions] `i` mesure le passage de l'image `i` à
 * l'image `i + 1` (à [FlickMeter.FPS] images/s).
 */
data class MotionTrack(val from: Duration, val motions: List<FlickMeter.Motion>) {
    /** Rotation à l'instant [t] de la source ; null hors de la mesure. */
    fun at(t: Duration): FlickMeter.Motion? {
        if (t < from) return null
        return motions.getOrNull(((t - from) / FlickMeter.FRAME).toInt())
    }
}

/**
 * Mesure la rotation de la caméra sur toute la durée de chaque plan, pour le flou par vecteurs de mouvement (voir
 * [MotionBlur.vectors]) : la source est décodée en tout petit ([FlickMeter.WIDTH] pixels de large, en niveaux de gris),
 * comme pour la mesure des flicks, et [FlickMeter.motions] donne le glissement du décor d'une image à l'autre, HUD
 * exclu. Une capture illisible laisse son plan sans flou par vecteurs, rien de plus.
 */
class CameraMotion(private val ffmpeg: FfmpegService) {

    suspend fun measure(plan: MontagePlan, progress: ProgressReporter): List<MotionTrack?> {
        val done = AtomicInteger()
        val semaphore = Semaphore(PARALLELISM)
        val tracks = coroutineScope {
            plan.clips.map { clip ->
                async {
                    semaphore.withPermit {
                        try {
                            track(clip)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn { "Mouvement de caméra illisible (${clip.group.media.path.fileName} à ${Durations.format(clip.start)}) : ${e.message}" }
                            null
                        }.also { progress.update(done.incrementAndGet().toDouble() / plan.clips.size, "plan ${done.get()}/${plan.clips.size}") }
                    }
                }
            }.awaitAll()
        }
        progress.complete()
        return tracks
    }

    private suspend fun track(clip: MontageClip): MotionTrack? {
        val media = clip.group.media
        val video = media.video ?: return null
        if (!media.path.isRegularFile()) return null
        val width = FlickMeter.WIDTH
        // Le décor seul : la bande au-dessus de l'arme et sous le haut du HUD. Les animations du personnage (mains d'une
        // capacité, arme qu'on inspecte, écran de mort) traversaient l'image et passaient pour un mouvement de caméra.
        val height = ((width.toDouble() * video.height * DECOR_HEIGHT / video.width) / 2).roundToInt().coerceAtLeast(8) * 2
        // La coupe peut avancer sur la source (plans d'un temps) : un peu de marge avant le début du plan.
        val start = (clip.start - MARGIN).coerceAtLeast(media.bounds.start)
        val end = (clip.end + MARGIN).coerceAtMost(media.duration)
        if (end <= start) return null
        val frames = mutableListOf<ByteArray>()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSecondsPrecise(start), "-t", Durations.ffmpegSeconds(end - start), "-i", media.path.toString(),
                    "-an", "-vf", "fps=${FlickMeter.FPS},crop=iw:ih*$DECOR_HEIGHT:0:ih*$DECOR_TOP,scale=$width:$height:flags=area,format=gray",
                    "-f", "rawvideo", "pipe:1",
                ),
                "mouvement de caméra ${Durations.format(clip.start)}",
            ),
            StdoutHandler.Binary { input ->
                val bytes = input.readAllBytes()
                val size = width * height
                for (i in 0 until bytes.size / size) frames += bytes.copyOfRange(i * size, (i + 1) * size)
            },
        )
        if (frames.size < 2) return null
        return MotionTrack(start, FlickMeter.motions(frames, width, height, FlickMeter.FPS.toDouble()))
    }

    companion object {
        private const val PARALLELISM = 3
        private const val DECOR_TOP = 0.12
        private const val DECOR_HEIGHT = 0.43
        private val MARGIN = 500.milliseconds
    }
}

/**
 * Flou par vecteurs de mouvement d'un plan : à chaque image de sortie, un flou gaussien orienté selon le glissement du
 * décor, de la longueur parcourue pendant l'obturateur. Le flou gaussien d'écart type L/√12 a la même étendue qu'une
 * traînée uniforme de longueur L (le flou d'une caméra réelle) ; `gblur` le calcule en temps constant quelle que soit
 * sa taille, et ses deux écarts types (horizontal, vertical) se changent image par image par `sendcmd`.
 */
object VectorBlur {
    /**
     * En deçà, pas de flou : un décor qui glisse de moins de 10 pixels par image se lit net. À 4 pixels, les petits
     * mouvements de visée floutaient légèrement presque tout le montage (-10 % de netteté).
     */
    private const val MIN_LENGTH = 10.0

    /**
     * Écarts types (horizontal, vertical) du flou de chaque image de sortie d'un plan de [frames] images à [fps]
     * images/s. [lead] : avance de la coupe (le plan commence [lead] avant son début dans la source) ; [pixelsPerWidth] :
     * largeur de la capture en pixels de sortie ; [width] : largeur de la sortie (plafond [MotionBlur.maxLength]) ;
     * [kills] : instants des kills dans le plan (même temps que les images), autour desquels le flou s'atténue.
     */
    fun sigmas(
        clip: MontageClip, track: MotionTrack, lead: Duration, frames: Int, fps: Int,
        pixelsPerWidth: Double, width: Int, blur: MotionBlur, kills: List<Duration> = emptyList(),
    ): List<Pair<Double, Double>> {
        val shutter = blur.shutter.inWholeMicroseconds / 1e6 * fps
        val cap = blur.maxLength * width
        fun sigma(px: Double): Double {
            val length = minOf(abs(px) * shutter, cap)
            return if (length < MIN_LENGTH) 0.0 else length / sqrt(12.0)
        }
        return List(frames) { n ->
            // L'image n tombe à n/fps dans le plan, avance de la coupe comprise.
            val time = (n.toLong() * 1_000_000 / fps).microseconds
            // Le kill se lit net : l'ennemi qui tombe compte plus que la traînée.
            val clear = if (kills.any { time >= it - blur.clearBefore && time <= it + blur.clearAfter }) blur.clearStrength else 1.0
            val at = sourceAt(clip, time - lead)
            val motion = at?.let { (s, _) -> track.at(s) }
            if (at == null || motion == null) 0.0 to 0.0
            else {
                // Rotation en largeurs de capture par seconde de source ; une seconde de sortie en couvre `factor`.
                val perFrame = pixelsPerWidth * at.second / fps
                sigma(motion.x * perFrame * clear) to sigma(motion.y * perFrame * clear)
            }
        }
    }

    /**
     * Instant de la source montré à l'instant [out] du plan (temps de sortie, depuis le début du plan sans l'avance de
     * la coupe), et vitesse de lecture à cet endroit ; null sur une image gelée (source trop courte).
     */
    internal fun sourceAt(clip: MontageClip, out: Duration): Pair<Duration, Double>? {
        var rest = out - clip.padBefore
        if (rest.isNegative()) return null
        var pos = clip.start
        for (seg in clip.speeds) {
            val gap = seg.range.start - pos
            if (rest <= gap) return pos + rest to 1.0
            rest -= gap
            pos = seg.range.start
            val segOut = seg.range.length / seg.factor
            if (rest <= segOut) return pos + rest * seg.factor to seg.factor
            rest -= segOut
            pos = seg.range.end
        }
        val t = pos + rest
        return if (t > clip.end) null else t to 1.0
    }

    /**
     * Filtres du flou d'un plan : `sendcmd` qui change les écarts types de `gblur@[name]` à chaque image où ils changent
     * (arrondis au demi-pixel), puis le flou lui-même. Vide si aucune image n'est floutée.
     */
    fun filters(name: String, sigmas: List<Pair<Double, Double>>, fps: Int): List<String> {
        fun round(v: Double) = Math.round(v * 2) / 2.0
        val rounded = sigmas.map { (x, y) -> round(x) to round(y) }
        if (rounded.all { it.first == 0.0 && it.second == 0.0 }) return emptyList()
        val commands = mutableListOf<String>()
        var last: Pair<Double, Double>? = 0.0 to 0.0
        rounded.forEachIndexed { n, value ->
            if (value != last) {
                val t = String.format(Locale.ROOT, "%.4f", n.toDouble() / fps)
                commands += "$t gblur@$name sigma ${num(value.first)}, gblur@$name sigmaV ${num(value.second)}"
                last = value
            }
        }
        return listOf("sendcmd=c='${commands.joinToString(";")}'", "gblur@$name=sigma=0:sigmaV=0")
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.1f", v)
}
