package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.MatchCut
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Raccords sur les animations du jeu (voir [MatchCut]) : pour chaque coupe du plan, décode la zone des animations
 * (l'arme, les mains) autour de la fin du plan sortant et du début du plan entrant, cherche le déplacement de la coupe
 * qui aligne le mieux les deux, et l'applique s'il ressemble assez. Seules quelques dizaines de petites images sont
 * décodées par coupe. Une coupe qu'on n'arrive pas à lire reste où elle est.
 */
class MatchCutter(private val ffmpeg: FfmpegService) {

    suspend fun apply(plan: MontagePlan, progress: ProgressReporter): MontagePlan {
        val settings = plan.settings.matchCut
        if (!settings.enabled || plan.clips.size < 2) return plan.also { progress.complete() }
        val frame = MatchCuts.FRAME
        val margin = MatchCuts.frames(settings.window)
        val semaphore = Semaphore(PARALLELISM)
        // Chaque coupe est lue sur le plan d'origine : le début et la fin d'un même clip ne se chevauchent pas.
        val candidates = coroutineScope {
            (1 until plan.clips.size).map { i ->
                async {
                    val out = plan.clips[i - 1]
                    val into = plan.clips[i]
                    val tail = MatchCuts.tailShifts(out, settings)
                    val head = MatchCuts.headShifts(into, settings)
                    if (tail == null || head == null) return@async null
                    val readOut = TimeRange(out.end + frame * tail.first - frame * margin, out.end + frame * tail.last + frame * margin)
                    val readIn = TimeRange(into.start + frame * head.first - frame * margin, into.start + frame * head.last + frame * margin)
                    try {
                        semaphore.withPermit {
                            val a = decode(out.group.media, readOut, settings.region) ?: return@withPermit null
                            val b = decode(into.group.media, readIn, settings.region) ?: return@withPermit null
                            val sameSpot = out.group.media.path == into.group.media.path
                            MatchCuts.best(a, b, tail, head, margin)
                                ?.takeUnless { sameSpot && (out.end + frame * it.tail - (into.start + frame * it.head)).absoluteValue < 1.seconds }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn { "Raccord de la coupe $i impossible à évaluer : ${e.message}" }
                        null
                    } finally {
                        progress.update(i.toDouble() / (plan.clips.size - 1), "coupe $i/${plan.clips.size - 1}")
                    }
                }
            }.awaitAll()
        }
        val clips = plan.clips.toMutableList()
        candidates.forEachIndexed { k, match ->
            val i = k + 1
            if (match == null) return@forEachIndexed
            val kept = match.similarity >= settings.minSimilarity
            log.info {
                "Coupe $i : animation ressemblante à ${"%.2f".format(Locale.ROOT, match.similarity)} " +
                    "(seuil ${"%.2f".format(Locale.ROOT, settings.minSimilarity)})" +
                    if (kept) ", raccord ${match.tail * frame.inWholeMilliseconds} ms / ${match.head * frame.inWholeMilliseconds} ms" else ""
            }
            if (!kept) return@forEachIndexed
            clips[i - 1] = MatchCuts.shiftTail(clips[i - 1], frame * match.tail)
            clips[i] = MatchCuts.shiftHead(clips[i], frame * match.head).copy(matchCut = true)
        }
        log.info { "Raccords sur les animations : ${clips.count { it.matchCut }} coupe(s) sur ${plan.clips.size - 1}" }
        progress.complete()
        return plan.copy(clips = clips)
    }

    /** Petites images en niveaux de gris de la zone des animations sur [range], une par [MatchCuts.FRAME]. */
    private suspend fun decode(media: MediaInfo, range: TimeRange, region: CropRegion): List<FloatArray>? {
        val video = media.video ?: return null
        if (!media.path.isRegularFile()) return null
        if (range.start < media.bounds.start || range.end > media.duration) return null
        val zone = ScreenGeometry.rescale(region, REFERENCE_ASPECT, video.width.toDouble() / video.height, RegionAnchor.RIGHT)
        val w = MatchCuts.WIDTH
        val h = MatchCuts.HEIGHT
        val frames = mutableListOf<FloatArray>()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSecondsPrecise(range.start), "-t", Durations.ffmpegSeconds(range.length), "-i", media.path.toString(),
                    "-an", "-vf",
                    "fps=${MatchCuts.FPS},crop=iw*${num(zone.width)}:ih*${num(zone.height)}:iw*${num(zone.x)}:ih*${num(zone.y)}," +
                        "scale=$w:$h:flags=area,format=gray",
                    "-f", "rawvideo", "pipe:1",
                ),
                "animations ${Durations.format(range.start)}",
            ),
            StdoutHandler.Binary { input ->
                val bytes = input.readAllBytes()
                val size = w * h
                for (i in 0 until bytes.size / size) frames += FloatArray(size) { (bytes[i * size + it].toInt() and 0xFF).toFloat() }
            },
        )
        return frames
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    companion object {
        private const val PARALLELISM = 4

        /** Format sur lequel la zone par défaut a été mesurée. */
        private const val REFERENCE_ASPECT = 16.0 / 9
    }
}

/** Déplacements retenus pour une coupe, en images de [MatchCuts.FRAME] : fin du plan sortant, début du plan entrant. */
data class MatchCutChoice(val tail: Int, val head: Int, val similarity: Double)

/** Calculs des raccords, sans décodage : déplacements possibles, ressemblance, et déplacement appliqué au clip. */
object MatchCuts {
    const val FPS = 30
    val FRAME = (1_000_000L / FPS).microseconds
    const val WIDTH = 64
    const val HEIGHT = 36

    /** Portion minimale à vitesse normale pour y loger la rampe qui rattrape le déplacement. */
    private val MIN_SPAN = 300.milliseconds

    /** Marge gardée avec le début et la fin de la capture (l'avance de la coupe lit quelques images avant le plan). */
    private val EDGE = 100.milliseconds

    /**
     * Mouvement minimal dans la zone (écart moyen de niveau de gris entre deux images) : une arme au repos ressemble à
     * toutes les autres, ce n'est pas une animation qui continue.
     */
    private const val MIN_MOTION = 1.5

    /** Part de l'apparence dans la ressemblance ; le reste revient au mouvement. */
    private const val LOOK_WEIGHT = 0.3

    /** Écart de ressemblance en dessous duquel deux raccords se valent. */
    private const val SIMILARITY_TIE = 0.03

    fun frames(d: Duration): Int = (d / FRAME).roundToInt().coerceAtLeast(1)

    /**
     * Déplacements possibles de la fin d'un clip, en images : la portion à vitesse normale qui suit le dernier kill et
     * le ralenti est rejouée un peu plus vite ou plus lentement pour absorber le déplacement. Null : aucune marge.
     */
    fun tailShifts(clip: MontageClip, settings: MatchCut): IntRange? {
        if (clip.padAfter.isPositive()) return null
        val from = (clip.kills + clip.speeds.map { it.range.end }).maxOrNull() ?: return null
        val span = clip.end - from
        if (span < MIN_SPAN) return null
        val room = minOf(settings.maxShift, span * settings.maxSpeedChange)
        val lo = -frames(room, floor = true)
        val hi = minOf(frames(room, floor = true), frames(clip.group.media.duration - EDGE - clip.end, floor = true))
        return (lo..hi).takeUnless { it.isEmpty() || (it.first == 0 && it.last == 0) }
    }

    /** Déplacements possibles du début d'un clip : même principe, sur la portion qui précède le premier kill. */
    fun headShifts(clip: MontageClip, settings: MatchCut): IntRange? {
        if (clip.padBefore.isPositive()) return null
        val to = (clip.kills + clip.speeds.map { it.range.start }).minOrNull() ?: return null
        val span = to - clip.start
        if (span < MIN_SPAN) return null
        val room = minOf(settings.maxShift, span * settings.maxSpeedChange)
        val lo = maxOf(-frames(room, floor = true), -frames(clip.start - clip.group.media.bounds.start - EDGE, floor = true))
        val hi = frames(room, floor = true)
        return (lo..hi).takeUnless { it.isEmpty() || (it.first == 0 && it.last == 0) }
    }

    private fun frames(d: Duration, floor: Boolean): Int = if (d.isNegative()) -1 else (d / FRAME).toInt().let { if (floor) it else it + 1 }

    /**
     * Meilleur raccord entre [a] (images autour de la fin du plan sortant) et [b] (autour du début de l'entrant). [a]
     * commence [margin] images avant le plus petit déplacement de [tail], [b] de même pour [head] : pour un couple de
     * déplacements, les 2 × [margin] images qui entourent chacune des deux coupes sont comparées une à une. La
     * ressemblance mêle l'apparence (même pose de l'arme) et surtout le mouvement (même geste, dans le même sens) : le
     * décor derrière l'arme change d'une partie à l'autre et brouille l'apparence, alors qu'il s'efface de l'écart entre
     * deux images. Les deux zones doivent bouger vraiment. Null si aucun couple ne se compare.
     */
    fun best(a: List<FloatArray>, b: List<FloatArray>, tail: IntRange, head: IntRange, margin: Int): MatchCutChoice? {
        val na = a.map(::normalized)
        val nb = b.map(::normalized)
        val da = a.zipWithNext { x, y -> difference(x, y) }
        val db = b.zipWithNext { x, y -> difference(x, y) }
        val choices = mutableListOf<MatchCutChoice>()
        for (ta in tail) {
            val oa = ta - tail.first
            if (oa + 2 * margin > a.size) continue
            for (hb in head) {
                val ob = hb - head.first
                if (ob + 2 * margin > b.size) continue
                val look = (0 until 2 * margin).map { m -> ncc(na[oa + m], nb[ob + m]) }.average()
                val moves = (0 until 2 * margin - 1).map { m -> da[oa + m] to db[ob + m] }
                if (moves.any { (x, y) -> x.energy < MIN_MOTION || y.energy < MIN_MOTION }) continue
                val motion = moves.map { (x, y) -> ncc(x.normalized, y.normalized) }.average()
                choices += MatchCutChoice(ta, hb, (LOOK_WEIGHT * look + (1 - LOOK_WEIGHT) * motion).coerceIn(0.0, 1.0))
            }
        }
        // À ressemblance presque égale, le plus petit déplacement : la musique avait choisi cette coupe.
        val top = choices.maxOfOrNull { it.similarity } ?: return null
        return choices.filter { it.similarity >= top - SIMILARITY_TIE }.minWith(compareBy<MatchCutChoice> { abs(it.tail) + abs(it.head) }.thenByDescending { it.similarity })
    }

    /** Écart entre deux images : son intensité (niveau de gris moyen) et sa forme, normalisée. */
    private class Difference(val energy: Double, val normalized: FloatArray)

    private fun difference(x: FloatArray, y: FloatArray): Difference {
        val d = FloatArray(x.size) { y[it] - x[it] }
        return Difference(d.sumOf { abs(it.toDouble()) } / d.size, normalized(d))
    }

    /** Centrée, de norme 1 : la corrélation devient un simple produit scalaire, insensible à l'exposition. */
    private fun normalized(v: FloatArray): FloatArray {
        val mean = v.average()
        val out = FloatArray(v.size) { (v[it] - mean).toFloat() }
        val norm = sqrt(out.sumOf { it.toDouble() * it })
        if (norm < 1e-9) return FloatArray(v.size)
        for (i in out.indices) out[i] = (out[i] / norm).toFloat()
        return out
    }

    private fun ncc(x: FloatArray, y: FloatArray): Double {
        var sum = 0.0
        for (i in x.indices) sum += x[i].toDouble() * y[i]
        return sum
    }

    /**
     * Fin du clip déplacée de [shift] dans la source : la portion à vitesse normale d'après le dernier kill est rejouée
     * au rythme qui la fait tenir dans la même durée de sortie. Les kills ne bougent pas.
     */
    fun shiftTail(clip: MontageClip, shift: Duration): MontageClip {
        if (shift == Duration.ZERO) return clip
        val from = (clip.kills + clip.speeds.map { it.range.end }).max()
        val end = clip.end + shift
        val factor = (end - from) / (clip.end - from)
        return clip.copy(end = end, speeds = clip.speeds + SpeedSegment(TimeRange(from, end), factor))
    }

    /** Début du clip déplacé de [shift] dans la source, rattrapé de même avant le premier kill. */
    fun shiftHead(clip: MontageClip, shift: Duration): MontageClip {
        if (shift == Duration.ZERO) return clip
        val to = (clip.kills + clip.speeds.map { it.range.start }).min()
        val start = clip.start + shift
        val factor = (to - start) / (to - clip.start)
        return clip.copy(start = start, speeds = listOf(SpeedSegment(TimeRange(start, to), factor)) + clip.speeds)
    }
}
