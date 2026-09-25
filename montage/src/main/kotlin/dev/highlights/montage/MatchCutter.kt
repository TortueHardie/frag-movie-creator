package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.MatchCut
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.PoseKind
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
 * Raccords visée sur visée (voir [MatchCut]) : repère, pour chaque groupe de kills, depuis quand le joueur vise avant
 * le premier kill et jusqu'où il vise encore après le dernier. La planification s'en sert pour placer les kills dans
 * leurs plans, puis [ScopeCuts.apply] ramène la fin du plan sortant et le début de l'entrant dans leur visée, ralentis
 * pour garder la durée du slot : le viseur reste au centre de l'écran par-dessus la coupe.
 */
class MatchCutter(private val ffmpeg: FfmpegService) {

    /**
     * Visée autour des kills de chaque groupe (voir [Aim]) : avant le premier kill, sur [HEAD_REACH] au plus, et après
     * le dernier, sur [TAIL_REACH]. Mesurée avant la planification, qui s'en sert pour placer les kills dans leurs plans.
     */
    suspend fun inspect(groups: List<KillGroup>, settings: MontageSettings, progress: ProgressReporter): List<KillGroup> {
        val scope = settings.matchCut
        if (!scope.enabled || groups.size < 2) return groups.also { progress.complete() }
        val semaphore = Semaphore(PARALLELISM)
        var done = 0
        val result = coroutineScope {
            groups.map { group ->
                async {
                    semaphore.withPermit {
                        val head = read(group, head = true, scope)
                        val tail = read(group, head = false, scope)
                        val aim = Aim(head = head?.first, tail = tail?.first, headPose = head?.second, tailPose = tail?.second)
                        synchronized(this@MatchCutter) { done++ }
                        progress.update(done.toDouble() / groups.size, "groupe $done/${groups.size}")
                        group.copy(aim = aim)
                    }
                }
            }.awaitAll()
        }
        log.info {
            "Pose de l'arme : tenue avant le premier kill dans ${result.count { it.aim.head != null }} groupe(s), " +
                "après le dernier dans ${result.count { it.aim.tail != null }}, sur ${groups.size}"
        }
        progress.complete()
        return result
    }

    /**
     * Fenêtre où le plan peut commencer avant le premier kill ([head]) ou finir après le dernier, dans la source, et la
     * pose de l'arme qu'on y voit ; null si la capture ne se lit pas ou si la pose attendue n'est pas tenue.
     */
    private suspend fun read(group: KillGroup, head: Boolean, settings: MatchCut): Pair<TimeRange, Pose>? {
        val media = group.media
        val kill = if (head) group.kills.first() else group.kills.last()
        val frame = ScopeCuts.FRAME
        val range = if (head) TimeRange(maxOf(media.bounds.start, kill - HEAD_REACH), kill)
        else TimeRange(kill - settings.reference - frame, minOf(media.duration, kill + TAIL_REACH))
        return try {
            val frames = decode(media, range, settings.region) ?: return null
            val killIndex = ((kill - range.start) / frame).toInt().coerceIn(0, frames.size)
            fun at(i: Int) = range.start + frame * i
            val margin = ScopeCuts.MARGIN
            when (settings.pose) {
                PoseKind.AIM -> {
                    val held = ScopeCuts.aimCurve(frames, killIndex, ScopeCuts.frames(settings.reference), settings.stillShare, settings.minSymmetry) ?: return null
                    // Quelques images en deçà du bord de la visée : le viseur y est posé, pas encore en train d'arriver ou de partir.
                    val window = if (head) ScopeCuts.aimStart(held.curve, killIndex, settings.minSimilarity)?.let { TimeRange(at(it + margin), kill) }
                    else ScopeCuts.aimEnd(held.curve, killIndex, settings.minSimilarity)?.let { TimeRange(kill, at(it - margin)) }
                    window?.takeIf { it.length.isPositive() }?.let { it to held.pose }
                }
                PoseKind.REST -> {
                    val held = ScopeCuts.restCurve(frames, settings.stillShare, killIndex, ScopeCuts.frames(settings.reference), settings.minSimilarity) ?: return null
                    val runs = ScopeCuts.runs(held.curve, settings.minSimilarity)
                    // Le repos le plus proche du kill, de son côté, arrêté au kill : l'arme est souvent au repos à travers lui.
                    val run = if (head) runs.lastOrNull { it.first < killIndex }?.let { it.first + margin..minOf(it.last - margin, killIndex) }
                    else runs.firstOrNull { it.last >= killIndex }?.let { maxOf(it.first + margin, killIndex)..it.last - margin }
                    run?.let { TimeRange(at(it.first), at(it.last)) }?.takeIf { it.length.isPositive() }?.let { it to held.pose }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "Pose de l'arme illisible autour de ${Durations.format(kill)} : ${e.message}" }
            null
        }
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

        /** Visée cherchée avant le premier kill : de quoi couvrir le plus long début de plan qu'on voudrait raccorder. */
        private val HEAD_REACH = 2.seconds

        /** Visée cherchée après le dernier kill : le joueur baisse son arme bien avant. */
        private val TAIL_REACH = 1500.milliseconds

        /** Format sur lequel la zone par défaut a été mesurée. */
        private const val REFERENCE_ASPECT = 16.0 / 9
    }
}

/**
 * Pose de l'arme : l'image de référence de la zone ([mean], [ScopeCuts.SIZE] de côté), la visée juste avant le kill
 * ou l'arme au repos, et les pixels qu'on compare ([still]) : l'arme plutôt que le décor.
 */
class Pose(val mean: DoubleArray, val still: BooleanArray)

/** Ressemblance de chaque image à la pose ([curve]), et cette pose. */
class HeldPose(val curve: DoubleArray, val pose: Pose)

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

    /** Part minimale de la zone immobile dans les deux poses pour les comparer. */
    private const val MIN_COMMON = 0.05

    /** Images gardées en deçà des bords de la visée. */
    const val MARGIN = 2

    /** Images sous le seuil tolérées au milieu d'une visée : le recul, la flamme du tir, un éclat. */
    private const val MAX_DIP = 2

    /** Accélération maximale d'une portion rejouée plus vite, quand la coupe doit reculer pour tomber dans la pose. */
    const val MAX_SPEED = 1.25

    /** Images moyennées pour juger la symétrie d'une image de la visée. */
    private const val SYMMETRY_SPAN = 5

    /** Pose tenue au moins ce nombre d'images pour compter comme repos. */
    private const val MIN_RUN = 5

    /** Visée minimale montrée avant le premier kill du plan entrant : le viseur doit se poser avant le tir. */
    val MIN_AIM_BEFORE = 200.milliseconds

    /** Portion minimale gardée après le dernier kill du plan sortant : l'impact doit rester visible. */
    val MIN_AIM_AFTER = 100.milliseconds

    fun frames(d: Duration): Int = (d / FRAME).toInt().coerceAtLeast(1)

    /**
     * Raccorde les coupes du plan d'après la visée de chaque groupe (voir [MatchCutter.inspect]) : chaque coupe dont les
     * deux plans visent est retaillée par [cut]. Le journal dit, coupe par coupe, ce qui a été trouvé et fait.
     */
    fun apply(plan: MontagePlan): MontagePlan = apply(plan, verbose = true)

    /** Nombre de coupes que [apply] raccorderait, sans rien écrire au journal. */
    fun count(plan: MontagePlan): Int = apply(plan, verbose = false).clips.count { it.matchCut }

    private fun apply(plan: MontagePlan, verbose: Boolean): MontagePlan {
        val settings = plan.settings.matchCut
        if (!settings.enabled || plan.clips.size < 2) return plan
        val clips = plan.clips.toMutableList()
        for (i in 1 until clips.size) {
            val out = plan.clips[i - 1]
            val into = plan.clips[i]
            val tail = out.group.aim.tail
            // La pose mesurée précède le premier kill du groupe : sans lui à l'écran, elle ne dit rien du début du plan.
            val head = into.group.aim.head?.takeIf { into.kills.firstOrNull() == into.group.kills.first() }
            val alike = compatible(out.group, into.group, settings)
            val cut = if (alike) cut(out, into, tail, head, settings) else null
            if (verbose) log.info { "Coupe $i : ${describe(out, into, tail, head, cut)}${poses(out.group, into.group, alike)}" }
            if (cut == null) continue
            // Le plan sortant a pu être retaillé par la coupe précédente : on ne touche qu'à sa fin.
            clips[i - 1] = retimeTail(clips[i - 1], cut.end) ?: continue
            clips[i] = cut.into.copy(matchCut = true)
        }
        if (verbose) log.info { "Raccords sur la pose de l'arme : ${clips.count { it.matchCut }} coupe(s) sur ${plan.clips.size - 1}" }
        return plan.copy(clips = clips)
    }

    /** Ressemblance des deux poses, pour le journal : de quoi régler [MatchCut.minPoseMatch]. */
    private fun poses(out: KillGroup, into: KillGroup, alike: Boolean): String {
        val a = out.aim.tailPose ?: return ""
        val b = into.aim.headPose ?: return ""
        return " (poses ${if (alike) "semblables" else "différentes"} : ${"%.2f".format(Locale.ROOT, similarity(a, b))})"
    }

    private fun describe(out: MontageClip, into: MontageClip, tail: TimeRange?, head: TimeRange?, cut: ScopeCut?): String {
        fun s(d: Duration) = "%+.2f".format(Locale.ROOT, d.inWholeMilliseconds / 1000.0)
        val after = tail?.let { "pose de ${s(it.start - out.anchor)} à ${s(it.end - out.anchor)} s du kill sortant" } ?: "pas de pose après le kill sortant"
        val before = head?.let { "de ${s(it.start - into.kills.first())} à ${s(it.end - into.kills.first())} s du kill entrant" } ?: "pas de pose avant le kill entrant"
        fun speed(f: Double?) = f?.let { "×${"%.2f".format(Locale.ROOT, it)}" } ?: "inchangée"
        val result = cut?.let { ", raccord (fin ${speed(it.tailSpeed)}, début ${speed(it.headSpeed)})" } ?: ", coupe laissée"
        return "$after, $before$result"
    }

    /**
     * Ressemblance de chaque image avec la visée du kill : la référence est la moyenne des [reference] images qui
     * précèdent l'image [killIndex], réduite à ses pixels les plus immobiles (part [stillShare]). Pendant qu'il vise,
     * le joueur suit sa cible et le décor défile derrière le viseur ; l'arme, elle, ne bouge pas à l'écran. Null si la
     * référence n'a rien de lisible (image uniforme, trop peu d'images) ou si le joueur tire à la hanche : en visée,
     * l'arme descend du viseur au bas de l'écran, symétrique de part et d'autre du centre ; à la hanche, elle est
     * décalée sur le côté. La moitié basse de la référence doit ressembler à son reflet d'au moins [minSymmetry], et
     * chaque image aussi (en moyenne sur [SYMMETRY_SPAN] images) pour compter comme visée : caméra immobile, le décor
     * ressemble encore à la référence alors que le joueur a déjà incliné ou baissé son arme.
     */
    fun aimCurve(frames: List<FloatArray>, killIndex: Int, reference: Int, stillShare: Double, minSymmetry: Double): HeldPose? {
        val ref = frames.subList((killIndex - reference).coerceAtLeast(0), killIndex.coerceAtMost(frames.size))
        if (ref.size < 3) return null
        val n = ref.first().size
        val mean = DoubleArray(n) { p -> ref.sumOf { it[p].toDouble() } / ref.size }
        if (symmetry(mean) < minSymmetry) return null
        val spread = DoubleArray(n) { p -> sqrt(ref.sumOf { (it[p] - mean[p]).let { d -> d * d } } / ref.size) }
        val cutoff = spread.sorted()[((n - 1) * stillShare).toInt()]
        val still = BooleanArray(n) { spread[it] <= cutoff }
        val mask = (0 until n).filter { still[it] }.toIntArray()
        val template = normalized(DoubleArray(mask.size) { mean[mask[it]] }) ?: return null
        val curve = DoubleArray(frames.size) { i ->
            val v = normalized(DoubleArray(mask.size) { frames[i][mask[it]].toDouble() }) ?: return@DoubleArray 0.0
            if (minSymmetry > -1.0 && symmetry(average(frames, i)) < minSymmetry) return@DoubleArray -1.0
            v.indices.sumOf { v[it] * template[it] }
        }
        return HeldPose(curve, Pose(mean, still))
    }

    /**
     * Ressemblance de chaque image avec l'arme au repos : la référence est l'image médiane de [frames], l'arme telle
     * qu'on la voit le plus souvent, réduite à ses pixels les plus stables (part [stillShare] ; 1 : toute la zone, quand
     * l'arme la remplit). Le repos doit ressembler d'au moins [threshold] aux [reference] images qui précèdent le kill
     * [killIndex] : le joueur tire, l'arme est là. Sinon, le plus souvent, l'arme n'était pas en main (capacité,
     * changement d'arme) et la médiane ne montre que le sol : null, comme pour une référence illisible.
     */
    fun restCurve(frames: List<FloatArray>, stillShare: Double, killIndex: Int, reference: Int, threshold: Double): HeldPose? {
        if (frames.size < MIN_RUN) return null
        val n = frames.first().size
        val median = DoubleArray(n) { p -> frames.map { it[p] }.sorted()[frames.size / 2].toDouble() }
        val spread = DoubleArray(n) { p -> sqrt(frames.sumOf { (it[p] - median[p]).let { d -> d * d } } / frames.size) }
        val cutoff = spread.sorted()[((n - 1) * stillShare).toInt()]
        val still = BooleanArray(n) { spread[it] <= cutoff }
        val mask = (0 until n).filter { still[it] }.toIntArray()
        val template = normalized(DoubleArray(mask.size) { median[mask[it]] }) ?: return null
        val shots = frames.subList((killIndex - reference).coerceAtLeast(0), killIndex.coerceIn(0, frames.size))
        if (shots.isEmpty()) return null
        val firing = normalized(DoubleArray(mask.size) { k -> shots.sumOf { it[mask[k]].toDouble() } / shots.size }) ?: return null
        if (firing.indices.sumOf { firing[it] * template[it] } < threshold) return null
        val curve = DoubleArray(frames.size) { i ->
            val v = normalized(DoubleArray(mask.size) { frames[i][mask[it]].toDouble() }) ?: return@DoubleArray 0.0
            v.indices.sumOf { v[it] * template[it] }
        }
        return HeldPose(curve, Pose(median, still))
    }

    /** Suites d'images où la pose est tenue ([threshold]), quelques images plus basses tolérées ; au moins [MIN_RUN] images. */
    fun runs(curve: DoubleArray, threshold: Double): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var first = -1
        var last = -1
        for (i in curve.indices) {
            if (curve[i] >= threshold) {
                if (first < 0) first = i
                last = i
            } else if (first >= 0 && i - last > MAX_DIP) {
                runs += first..last
                first = -1
            }
        }
        if (first >= 0) runs += first..last
        return runs.filter { it.count() >= MIN_RUN }
    }

    /**
     * Ressemblance (-1..1) de deux poses de l'arme, sur les pixels immobiles des deux : même arme tenue de la même façon
     * au même endroit de l'écran. 0 si elles n'ont pas assez de pixels en commun pour en juger.
     */
    fun similarity(a: Pose, b: Pose): Double {
        val common = a.still.indices.filter { a.still[it] && b.still[it] }
        if (common.size < a.still.size * MIN_COMMON) return 0.0
        val x = normalized(DoubleArray(common.size) { a.mean[common[it]] }) ?: return 0.0
        val y = normalized(DoubleArray(common.size) { b.mean[common[it]] }) ?: return 0.0
        return x.indices.sumOf { x[it] * y[it] }
    }

    /**
     * Les deux plans d'une coupe peuvent-ils se raccorder : le sortant tient sa pose après son dernier kill, l'entrant
     * avant son premier, et les deux poses se ressemblent assez ([MatchCut.minPoseMatch]).
     */
    fun compatible(out: KillGroup, into: KillGroup, settings: MatchCut): Boolean {
        if (out.aim.tail == null || into.aim.head == null) return false
        if (settings.minPoseMatch <= -1.0) return true
        val a = out.aim.tailPose ?: return false
        val b = into.aim.headPose ?: return false
        return similarity(a, b) >= settings.minPoseMatch
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
     * Raccord d'une coupe : fin du plan sortant ramenée dans sa fenêtre de pose ([tail]), début du plan entrant dans la
     * sienne ([head]), au plus près de la coupe prévue ; chaque portion déplacée est rejouée à la vitesse qui garde sa
     * durée de sortie. Null sans pose d'un côté, si la vitesse sortirait de [MatchCut.minSpeed]..[MAX_SPEED], ou si les
     * deux plans se suivent dans la même capture (c'est déjà la même scène).
     */
    fun cut(out: MontageClip, into: MontageClip, tail: TimeRange?, head: TimeRange?, settings: MatchCut): ScopeCut? {
        if (tail == null || head == null) return null
        val end = out.end.coerceIn(tail.start, tail.end)
        val start = into.start.coerceIn(head.start, head.end)
        val retimedTail = retimeTail(out, end) ?: return null
        val retimedHead = retimeHead(into, start) ?: return null
        val tailSpeed = if (end != out.end) retimedTail.speeds.last().factor else null
        val headSpeed = if (start != into.start) retimedHead.speeds.first().factor else null
        if (listOfNotNull(tailSpeed, headSpeed).any { it < settings.minSpeed - 1e-9 || it > MAX_SPEED + 1e-9 }) return null
        if (out.group.media.path == into.group.media.path && (end - start).absoluteValue < 1.seconds) return null
        return ScopeCut(end, retimedHead, tailSpeed, headSpeed)
    }

    /**
     * Fin du clip ramenée à [end] : tout ce qui suit le kill d'ancrage est rejoué à une seule vitesse, qui fait tenir la
     * portion dans sa durée de sortie : plus lentement si [end] avance, plus vite s'il recule. Inchangé si [end] est la
     * fin actuelle ; null sans place pour l'impact, au-delà de la capture ou avec une image gelée à la fin.
     */
    fun retimeTail(clip: MontageClip, end: Duration): MontageClip? {
        if (end == clip.end) return clip
        if (clip.padAfter.isPositive() || end - clip.anchor < MIN_AIM_AFTER || end > clip.group.media.duration) return null
        val output = clip.toOutput(clip.end) - clip.toOutput(clip.anchor)
        val speeds = split(clip.speeds, clip.anchor).filter { it.range.end <= clip.anchor }
        return clip.copy(end = end, speeds = speeds + SpeedSegment(TimeRange(clip.anchor, end), (end - clip.anchor) / output, SpeedKind.SLOW))
    }

    /**
     * Début du clip ramené à [start] : tout ce qui précède le premier kill visible est rejoué à une seule vitesse.
     * Inchangé si [start] est le début actuel ; null sans pose assez longue avant le kill, avant le début de la
     * capture ou avec une image gelée au début.
     */
    fun retimeHead(clip: MontageClip, start: Duration): MontageClip? {
        if (start == clip.start) return clip
        val kill = clip.kills.firstOrNull() ?: return null
        if (clip.padBefore.isPositive() || kill - start < MIN_AIM_BEFORE || start < clip.group.media.bounds.start) return null
        val output = clip.toOutput(kill) - clip.toOutput(clip.start)
        val speeds = split(clip.speeds, kill).filter { it.range.start >= kill }
        return clip.copy(start = start, speeds = listOf(SpeedSegment(TimeRange(start, kill), (kill - start) / output, SpeedKind.SLOW)) + speeds)
    }

    /** Moyenne des images autour de [i], sur [SYMMETRY_SPAN] images : une image seule est trop bruitée pour la symétrie. */
    private fun average(frames: List<FloatArray>, i: Int): DoubleArray {
        val from = (i - SYMMETRY_SPAN / 2).coerceAtLeast(0)
        val to = (i + SYMMETRY_SPAN / 2).coerceAtMost(frames.lastIndex)
        return DoubleArray(frames[i].size) { p -> (from..to).sumOf { frames[it][p].toDouble() } / (to - from + 1) }
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
