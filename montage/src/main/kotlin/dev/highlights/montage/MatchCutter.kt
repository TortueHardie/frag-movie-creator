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
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Raccords visée sur visée (voir [MatchCut]) : à chaque coupe, repère jusqu'où le joueur vise encore après le dernier
 * kill du plan sortant, et depuis quand il vise avant le premier kill du plan entrant. Si les deux plans peuvent être
 * taillés dans leur visée, la fin de l'un et le début de l'autre y sont ramenés, ralentis pour garder la durée du slot :
 * le viseur reste au centre de l'écran par-dessus la coupe. Une coupe qu'on n'arrive pas à lire reste où elle est.
 */
class MatchCutter(private val ffmpeg: FfmpegService) {

    suspend fun apply(plan: MontagePlan, progress: ProgressReporter): MontagePlan {
        val settings = plan.settings.matchCut
        if (!settings.enabled || plan.clips.size < 2) return plan.also { progress.complete() }
        val semaphore = Semaphore(PARALLELISM)
        // Chaque clip est lu une fois de chaque côté : sa fin pour la coupe qui le suit, son début pour celle qui l'ouvre.
        val sides = coroutineScope {
            plan.clips.mapIndexed { i, clip ->
                async {
                    semaphore.withPermit {
                        val tail = if (i < plan.clips.lastIndex) read(clip, head = false, settings) else null
                        val head = if (i > 0) read(clip, head = true, settings) else null
                        head to tail
                    }.also { progress.update((i + 1).toDouble() / plan.clips.size, "plan ${i + 1}/${plan.clips.size}") }
                }
            }.awaitAll()
        }
        val clips = plan.clips.toMutableList()
        for (i in 1 until clips.size) {
            val out = plan.clips[i - 1]
            val into = plan.clips[i]
            val aimEnd = sides[i - 1].second
            val aimStart = sides[i].first
            val cut = ScopeCuts.cut(out, into, aimEnd, aimStart, settings)
            log.info { "Coupe $i : ${describe(out, into, aimEnd, aimStart, cut)}" }
            if (cut == null) continue
            // Le plan sortant a pu être retaillé par la coupe précédente : on ne touche qu'à sa fin.
            clips[i - 1] = ScopeCuts.retimeTail(clips[i - 1], cut.end) ?: continue
            clips[i] = cut.into.copy(matchCut = true)
        }
        log.info { "Raccords visée sur visée : ${clips.count { it.matchCut }} coupe(s) sur ${plan.clips.size - 1}" }
        progress.complete()
        return plan.copy(clips = clips)
    }

    /**
     * Instant où la visée commence avant le premier kill ([head]) ou finit après le dernier, dans la source ; null si
     * le clip ne se lit pas ou si le joueur ne vise pas au moment du kill.
     */
    private suspend fun read(clip: MontageClip, head: Boolean, settings: MatchCut): Duration? {
        val kill = (if (head) clip.kills.firstOrNull() else clip.kills.lastOrNull()) ?: return null
        val frame = ScopeCuts.FRAME
        val range = if (head) TimeRange(clip.start, kill) else TimeRange(kill - settings.reference - frame, clip.end)
        return try {
            val frames = decode(clip.group.media, range, settings.region) ?: return null
            val killIndex = ((kill - range.start) / frame).toInt().coerceIn(0, frames.size)
            val curve = ScopeCuts.aimCurve(frames, killIndex, ScopeCuts.frames(settings.reference), settings.stillShare, settings.minSymmetry) ?: return null
            // Quelques images en deçà du bord de la visée : le viseur y est posé, pas encore en train d'arriver ou de partir.
            val index = if (head) ScopeCuts.aimStart(curve, killIndex, settings.minSimilarity)?.plus(ScopeCuts.MARGIN)
            else ScopeCuts.aimEnd(curve, killIndex, settings.minSimilarity)?.minus(ScopeCuts.MARGIN)
            index?.let { range.start + frame * it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "Visée illisible autour de ${Durations.format(kill)} : ${e.message}" }
            null
        }
    }

    private fun describe(out: MontageClip, into: MontageClip, aimEnd: Duration?, aimStart: Duration?, cut: ScopeCut?): String {
        fun s(d: Duration) = "%.2f s".format(Locale.ROOT, d.inWholeMilliseconds / 1000.0)
        val after = aimEnd?.let { "visée ${s(it - out.anchor)} après le kill sortant" } ?: "pas de visée après le kill sortant"
        val before = aimStart?.let { "${s(into.kills.first() - it)} avant le kill entrant" } ?: "pas de visée avant le kill entrant"
        fun speed(f: Double?) = f?.let { "×${"%.2f".format(Locale.ROOT, it)}" } ?: "inchangée"
        val result = cut?.let { ", raccord (fin ${speed(it.tailSpeed)}, début ${speed(it.headSpeed)})" } ?: ", coupe laissée"
        return "$after, $before$result"
    }

    /** Petites images en niveaux de gris de la zone du viseur sur [range], une par [ScopeCuts.FRAME]. */
    private suspend fun decode(media: MediaInfo, range: TimeRange, region: CropRegion): List<FloatArray>? {
        val video = media.video ?: return null
        if (!media.path.isRegularFile()) return null
        if (range.start < media.bounds.start || range.end > media.duration || !range.length.isPositive()) return null
        val zone = ScreenGeometry.rescale(region, REFERENCE_ASPECT, video.width.toDouble() / video.height, RegionAnchor.CENTER)
        val size = ScopeCuts.SIZE
        val frames = mutableListOf<FloatArray>()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSecondsPrecise(range.start), "-t", Durations.ffmpegSeconds(range.length), "-i", media.path.toString(),
                    "-an", "-vf",
                    "fps=${ScopeCuts.FPS},crop=iw*${num(zone.width)}:ih*${num(zone.height)}:iw*${num(zone.x)}:ih*${num(zone.y)}," +
                        "scale=$size:$size:flags=area,format=gray",
                    "-f", "rawvideo", "pipe:1",
                ),
                "visée ${Durations.format(range.start)}",
            ),
            StdoutHandler.Binary { input ->
                val bytes = input.readAllBytes()
                val pixels = size * size
                for (i in 0 until bytes.size / pixels) frames += FloatArray(pixels) { (bytes[i * pixels + it].toInt() and 0xFF).toFloat() }
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

/**
 * Raccord retenu pour une coupe : nouvelle fin du plan sortant, plan entrant retaillé, et vitesse des portions rejouées
 * (null : portion laissée telle quelle).
 */
data class ScopeCut(val end: Duration, val into: MontageClip, val tailSpeed: Double?, val headSpeed: Double?)

/** Calculs des raccords visée sur visée, sans décodage : courbe de visée, bornes de la visée, retaille des clips. */
object ScopeCuts {
    const val FPS = 30
    val FRAME = (1_000_000L / FPS).microseconds
    const val SIZE = 48

    /** Images gardées en deçà des bords de la visée. */
    const val MARGIN = 2

    /** Images sous le seuil tolérées au milieu d'une visée : le recul, la flamme du tir, un éclat. */
    private const val MAX_DIP = 2

    /** Visée minimale montrée avant le premier kill du plan entrant : le viseur doit se poser avant le tir. */
    val MIN_AIM_BEFORE = 200.milliseconds

    /** Portion minimale gardée après le dernier kill du plan sortant : l'impact doit rester visible. */
    val MIN_AIM_AFTER = 100.milliseconds

    fun frames(d: Duration): Int = (d / FRAME).toInt().coerceAtLeast(1)

    /**
     * Ressemblance de chaque image avec la visée du kill : la référence est la moyenne des [reference] images qui
     * précèdent l'image [killIndex], réduite à ses pixels les plus immobiles (part [stillShare]). Pendant qu'il vise,
     * le joueur suit sa cible et le décor défile derrière le viseur ; l'arme, elle, ne bouge pas à l'écran. Null si la
     * référence n'a rien de lisible (image uniforme, trop peu d'images) ou si le joueur tire à la hanche : en visée,
     * l'arme descend du viseur au bas de l'écran, symétrique de part et d'autre du centre ; à la hanche, elle est
     * décalée sur le côté. La moitié basse de la référence doit ressembler à son reflet d'au moins [minSymmetry].
     */
    fun aimCurve(frames: List<FloatArray>, killIndex: Int, reference: Int, stillShare: Double, minSymmetry: Double): DoubleArray? {
        val ref = frames.subList((killIndex - reference).coerceAtLeast(0), killIndex.coerceAtMost(frames.size))
        if (ref.size < 3) return null
        val n = ref.first().size
        val mean = DoubleArray(n) { p -> ref.sumOf { it[p].toDouble() } / ref.size }
        if (symmetry(mean) < minSymmetry) return null
        val spread = DoubleArray(n) { p -> sqrt(ref.sumOf { (it[p] - mean[p]).let { d -> d * d } } / ref.size) }
        val cutoff = spread.sorted()[((n - 1) * stillShare).toInt()]
        val mask = (0 until n).filter { spread[it] <= cutoff }.toIntArray()
        val template = normalized(DoubleArray(mask.size) { mean[mask[it]] }) ?: return null
        return DoubleArray(frames.size) { i ->
            val v = normalized(DoubleArray(mask.size) { frames[i][mask[it]].toDouble() }) ?: return@DoubleArray 0.0
            v.indices.sumOf { v[it] * template[it] }
        }
    }

    /** Première image de la visée qui mène au kill [killIndex] (exclu), en remontant ; null si le joueur ne vise pas. */
    fun aimStart(curve: DoubleArray, killIndex: Int, threshold: Double): Int? {
        var first: Int? = null
        var dip = 0
        for (i in (killIndex - 1).coerceAtMost(curve.lastIndex) downTo 0) {
            if (curve[i] >= threshold) {
                first = i
                dip = 0
            } else if (first == null || ++dip > MAX_DIP) {
                break
            }
        }
        return first
    }

    /** Dernière image de la visée qui suit le kill [killIndex] ; null si le joueur ne vise pas au moment du kill. */
    fun aimEnd(curve: DoubleArray, killIndex: Int, threshold: Double): Int? {
        var last: Int? = null
        var dip = 0
        for (i in (killIndex - 1).coerceAtLeast(0)..curve.lastIndex) {
            if (curve[i] >= threshold) {
                last = i
                dip = 0
            } else if (last == null || ++dip > MAX_DIP) {
                break
            }
        }
        return last
    }

    /**
     * Raccord d'une coupe : fin du plan sortant ramenée dans sa visée ([aimEnd]), début du plan entrant dans la sienne
     * ([aimStart]), chacun ralenti pour garder sa durée de sortie. Null si l'un des deux ne vise pas, si le ralenti
     * dépasserait [MatchCut.minSpeed], ou si les deux plans se suivent dans la même capture (c'est déjà la même scène).
     */
    fun cut(out: MontageClip, into: MontageClip, aimEnd: Duration?, aimStart: Duration?, settings: MatchCut): ScopeCut? {
        if (aimEnd == null || aimStart == null) return null
        val end = minOf(out.end, aimEnd)
        val start = maxOf(into.start, aimStart)
        val tail = retimeTail(out, end) ?: return null
        val head = retimeHead(into, start) ?: return null
        val tailSpeed = if (end < out.end) tail.speeds.last().factor else null
        val headSpeed = if (start > into.start) head.speeds.first().factor else null
        if (listOfNotNull(tailSpeed, headSpeed).any { it < settings.minSpeed - 1e-9 }) return null
        if (out.group.media.path == into.group.media.path && (end - start).absoluteValue < 1.seconds) return null
        return ScopeCut(end, head, tailSpeed, headSpeed)
    }

    /**
     * Fin du clip ramenée à [end] : tout ce qui suit le kill d'ancrage est rejoué à une seule vitesse, qui fait tenir la
     * portion dans sa durée de sortie. Inchangé si [end] est la fin actuelle ; null sans place pour l'impact ou avec une
     * image gelée à la fin.
     */
    fun retimeTail(clip: MontageClip, end: Duration): MontageClip? {
        if (end >= clip.end) return clip
        if (clip.padAfter.isPositive() || end - clip.anchor < MIN_AIM_AFTER) return null
        val output = clip.toOutput(clip.end) - clip.toOutput(clip.anchor)
        val speeds = split(clip.speeds, clip.anchor).filter { it.range.end <= clip.anchor }
        return clip.copy(end = end, speeds = speeds + SpeedSegment(TimeRange(clip.anchor, end), (end - clip.anchor) / output, SpeedKind.SLOW))
    }

    /**
     * Début du clip ramené à [start] : tout ce qui précède le premier kill visible est rejoué à une seule vitesse.
     * Inchangé si [start] est le début actuel ; null sans visée assez longue avant le kill ou avec une image gelée au
     * début.
     */
    fun retimeHead(clip: MontageClip, start: Duration): MontageClip? {
        if (start <= clip.start) return clip
        val kill = clip.kills.firstOrNull() ?: return null
        if (clip.padBefore.isPositive() || kill - start < MIN_AIM_BEFORE) return null
        val output = clip.toOutput(kill) - clip.toOutput(clip.start)
        val speeds = split(clip.speeds, kill).filter { it.range.start >= kill }
        return clip.copy(start = start, speeds = listOf(SpeedSegment(TimeRange(start, kill), (kill - start) / output, SpeedKind.SLOW)) + speeds)
    }

    /** Ressemblance (-1..1) de la moitié basse d'une image carrée [SIZE] x [SIZE] avec son reflet gauche-droite. */
    fun symmetry(image: DoubleArray): Double {
        val rows = SIZE / 2 until SIZE
        val half = normalized(DoubleArray(rows.count() * SIZE) { image[(SIZE / 2 + it / SIZE) * SIZE + it % SIZE] }) ?: return 0.0
        val mirror = normalized(DoubleArray(rows.count() * SIZE) { image[(SIZE / 2 + it / SIZE) * SIZE + SIZE - 1 - it % SIZE] }) ?: return 0.0
        return half.indices.sumOf { half[it] * mirror[it] }
    }

    /** Changements de vitesse coupés en deux à [at] : le palier le plus lent du ralenti enjambe le kill. */
    private fun split(speeds: List<SpeedSegment>, at: Duration): List<SpeedSegment> = speeds.flatMap {
        if (at > it.range.start && at < it.range.end) listOf(it.copy(range = TimeRange(it.range.start, at)), it.copy(range = TimeRange(at, it.range.end)))
        else listOf(it)
    }

    /** Centré, de norme 1 : la corrélation devient un simple produit scalaire, insensible à l'exposition. */
    private fun normalized(v: DoubleArray): DoubleArray? {
        val mean = v.average()
        val out = DoubleArray(v.size) { v[it] - mean }
        val norm = sqrt(out.sumOf { it * it })
        if (norm < 1e-6) return null
        for (i in out.indices) out[i] /= norm
        return out
    }
}
