package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AmmoHud
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.RegionAnchor
import dev.highlights.core.model.ScreenGeometry
import dev.highlights.core.model.TimeRange
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
import kotlin.io.path.isRegularFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * Balles tirées autour d'un kill, lues sur le compteur de munitions du HUD (voir [AmmoHud]). Ne relit que les kills
 * qu'on lui donne, une seconde d'image chacun : c'est la confirmation des one taps entendus, pas une analyse de la
 * partie entière (décoder toute la vidéo à 60 images/s coûterait plusieurs minutes).
 */
class AmmoCounter(private val ffmpeg: FfmpegService) {

    /**
     * [groups] avec, pour chaque kill, le nombre de balles vu sur le compteur à la place de celui entendu ; 0 quand le
     * compteur ne bouge pas (capacité, couteau, compteur caché) : ce n'est pas un one tap. [context] : tous les kills des
     * parties, qui bornent la fenêtre de chaque kill (les balles d'un kill voisin ne sont pas les siennes).
     */
    suspend fun recount(groups: List<KillGroup>, context: List<KillGroup>, hud: AmmoHud, progress: ProgressReporter): List<KillGroup> {
        val neighbours = context.plus(groups).groupBy { it.media.path }.mapValues { (_, gs) -> gs.flatMap { it.kills }.distinct().sorted() }
        val semaphore = Semaphore(PARALLELISM)
        var done = 0
        val total = groups.sumOf { it.kills.size }.coerceAtLeast(1)
        val result = coroutineScope {
            groups.map { group ->
                async {
                    val counts = group.kills.map { kill ->
                        semaphore.withPermit {
                            val window = window(kill, neighbours[group.media.path].orEmpty(), hud)
                            count(group.media, window, hud).also {
                                synchronized(this@AmmoCounter) { done++ }
                                progress.update(done.toDouble() / total, "kill $done/$total")
                            }
                        }
                    }
                    val traits = group.kills.mapIndexed { i, k -> group.traitsOf(k).let { t -> counts[i]?.let { t.copy(shots = it) } ?: t } }
                    group.copy(traits = traits)
                }
            }.awaitAll()
        }
        val counted = result.flatMap { g -> g.kills.map { g.traitsOf(it).shots } }
        log.info {
            "Compteur de munitions : ${counted.count { it == 1 }} kill(s) d'une balle, ${counted.count { it != null && it > 1 }} en rafale, " +
                "${counted.count { it == 0 }} sans balle lue, sur ${counted.size}"
        }
        progress.complete()
        return result
    }

    /** Balles tirées dans [window], null si la capture ne se lit pas (on garde alors les tirs entendus). */
    private suspend fun count(media: MediaInfo, window: TimeRange, hud: AmmoHud): Int? = try {
        decode(media, window, hud)?.let { frames -> shots(frames, WIDTH * HEIGHT, hud).size }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn { "Compteur de munitions illisible autour de ${Durations.format(window.start)} : ${e.message}" }
        null
    }

    private suspend fun decode(media: MediaInfo, range: TimeRange, hud: AmmoHud): List<ByteArray>? {
        val video = media.video ?: return null
        if (!media.path.isRegularFile() || !range.length.isPositive()) return null
        val zone = ScreenGeometry.rescale(hud.region, REFERENCE_ASPECT, video.width.toDouble() / video.height, RegionAnchor.CENTER)
        val frames = mutableListOf<ByteArray>()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSecondsPrecise(range.start), "-t", Durations.ffmpegSeconds(range.length), "-i", media.path.toString(),
                    "-an", "-vf",
                    "fps=$FPS,crop=iw*${num(zone.width)}:ih*${num(zone.height)}:iw*${num(zone.x)}:ih*${num(zone.y)}," +
                        "scale=$WIDTH:$HEIGHT:flags=area,format=gray",
                    "-f", "rawvideo", "pipe:1",
                ),
                "munitions ${Durations.format(range.start)}",
            ),
            StdoutHandler.Binary { input ->
                val bytes = input.readAllBytes()
                val pixels = WIDTH * HEIGHT
                for (i in 0 until bytes.size / pixels) frames += bytes.copyOfRange(i * pixels, (i + 1) * pixels)
            },
        )
        return frames
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    companion object {
        private const val PARALLELISM = 4

        /** Assez pour séparer les balles du Vandal (0,1 s) ; un changement de chiffre s'étale sur une ou deux images. */
        const val FPS = 60

        /** Zone réduite : trois chiffres de 13 pixels de large environ, lisibles sans le détail de la capture. */
        const val WIDTH = 96
        const val HEIGHT = 48

        /** Changements plus proches que ça : le même chiffre qui s'affiche sur deux images. */
        private val SAME_SHOT = 50.milliseconds

        /** Format sur lequel la zone par défaut a été mesurée. */
        private const val REFERENCE_ASPECT = 16.0 / 9

        /**
         * Fenêtre des balles d'un kill : [AmmoHud.before] avant, [AmmoHud.after] après, sans dépasser la moitié du chemin
         * vers les kills voisins ([kills], triés).
         */
        internal fun window(kill: Duration, kills: List<Duration>, hud: AmmoHud): TimeRange {
            val previous = kills.lastOrNull { it < kill }
            val next = kills.firstOrNull { it > kill }
            val start = maxOf(kill - hud.before, previous?.let { (it + kill) / 2 } ?: Duration.ZERO, Duration.ZERO)
            val end = minOf(kill + hud.after, next?.let { (it + kill) / 2 } ?: Duration.INFINITE)
            return TimeRange(start, end)
        }

        /**
         * Images (indices) où le compteur change : plus de [AmmoHud.minChange] des pixels blancs (les chiffres) diffèrent
         * de l'image précédente. Deux changements à moins de [SAME_SHOT] n'en font qu'un.
         */
        internal fun shots(frames: List<ByteArray>, pixels: Int, hud: AmmoHud): List<Int> {
            val masks = frames.map { f -> BooleanArray(pixels) { (f[it].toInt() and 0xFF) > hud.brightness } }
            val gap = (SAME_SHOT.inWholeMicroseconds * FPS / 1_000_000).toInt()
            val changes = mutableListOf<Int>()
            for (i in 1 until masks.size) {
                val a = masks[i - 1]
                val b = masks[i]
                val lit = maxOf(a.count { it }, b.count { it })
                if (lit == 0) continue
                val differ = (0 until pixels).count { a[it] != b[it] }
                if (differ.toDouble() / lit > hud.minChange && (changes.isEmpty() || i - changes.last() > gap)) changes += i
            }
            return changes
        }
    }
}
