package dev.highlights.vision

import dev.highlights.core.ConfigException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.ffmpeg.Hwaccel
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.RegionAnchor
import dev.highlights.core.model.ScreenGeometry
import dev.highlights.core.serialization.Durations
import dev.highlights.core.video.FrameSampler
import dev.highlights.core.video.FrameSpec
import dev.highlights.core.video.FrameZone
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Réglages de [HudChangeDetector]. Faits pour l'assistant « nouveau jeu » : un compteur du HUD (éliminations) dont on
 * ne connaît pas les chiffres. Chaque nouvelle valeur affichée est un événement, sans lire le nombre.
 */
@Serializable
data class HudChangeParams(
    /** Zone du compteur (normalisée). */
    val region: CropRegion,
    /** Format de la capture sur laquelle la zone a été relevée : la zone y est ramenée avant la comparaison. */
    val referenceHeight: Int = 480,
    val referenceWidth: Int? = null,
    val anchor: RegionAnchor = RegionAnchor.AUTO,
    val kind: String = "kill",
    val fps: Double = 2.0,
    val hwaccel: String? = Hwaccel.AUTO,
    /** Écart-type minimal des niveaux de gris : en dessous, la zone est vide (écran de chargement, menu) et ignorée. */
    val minContrast: Double = 10.0,
    /** Corrélation au-dessus de laquelle deux images de la zone montrent la même valeur. */
    val sameScore: Double = 0.9,
    /** Images consécutives semblables avant qu'une valeur compte (2 s à 2 img/s) : un flash ou un passage ne compte pas. */
    val stableFrames: Int = 4,
    /**
     * Ne comparer que le chiffre des unités, trouvé dans la zone : la colonne de pixels clairs la plus à droite qui
     * change au fil de la partie (une icône à côté du nombre reste allumée). Le fond derrière un HUD transparent change
     * sans cesse : sur la zone entière du compteur de Fortnite (38x16 px en 480p), 89 changements pour 31 kills ; sur
     * les unités seules (6x6 px), 42 dont 23 vrais.
     */
    val unitsOnly: Boolean = true,
    /** Niveau de gris d'un pixel « clair » (chiffres blancs) pour trouver les unités. */
    val bright: Int = 170,
) {
    init {
        require(fps > 0 && referenceHeight > 0) { "fps et referenceHeight doivent être > 0" }
        require(sameScore in 0.0..1.0 && stableFrames >= 1 && minContrast >= 0) { "paramètres invalides : $this" }
    }
}

/**
 * Valeurs successives d'un compteur dont on ne connaît pas les chiffres. Une valeur est une image stable de la zone ;
 * le compteur ne fait que monter dans une partie, donc une image déjà vue depuis la dernière remise à zéro n'est pas un
 * nouveau kill (retour après un menu, un flash). La première valeur vue est le zéro : la revoir, c'est une nouvelle
 * partie. Un saut de plusieurs unités (toute une équipe éliminée d'un coup) ne compte qu'un événement.
 */
internal class ChangeTracker(private val sameScore: Double, private val stableFrames: Int, private val minContrast: Double) {
    private class Shape(val values: DoubleArray)

    private val clusters = mutableListOf<Shape>()
    private var zero: Int? = null
    private val seen = HashSet<Int>()
    private var current: Int? = null
    private var candidate: Shape? = null
    private var candidateStart: Duration = Duration.ZERO
    private var candidateCount = 0
    val events = mutableListOf<Duration>()

    fun add(at: Duration, image: GrayImage) {
        val shape = normalized(image) ?: return
        val c = candidate
        if (c != null && similarity(c, shape) >= sameScore) {
            candidateCount++
        } else {
            candidate = shape
            candidateStart = at
            candidateCount = 1
        }
        if (candidateCount != stableFrames) return
        val stable = candidate ?: return
        val cluster = clusters.indexOfFirst { similarity(it, stable) >= sameScore }.takeIf { it >= 0 } ?: clusters.size.also { clusters += stable }
        if (cluster == current) return
        current = cluster
        when {
            zero == null -> {
                zero = cluster
                seen += cluster
            }
            cluster == zero -> {
                seen.clear()
                seen += cluster
            }
            cluster !in seen -> {
                seen += cluster
                events += candidateStart
            }
        }
    }

    private fun normalized(image: GrayImage): Shape? {
        val n = image.width * image.height
        if (n == 0) return null
        val v = DoubleArray(n) { (image.pixels[it].toInt() and 0xFF).toDouble() }
        val mean = v.average()
        val std = sqrt(v.sumOf { (it - mean) * (it - mean) } / n)
        if (std < minContrast) return null
        for (i in v.indices) v[i] = (v[i] - mean) / std
        return Shape(v)
    }

    private fun similarity(a: Shape, b: Shape): Double {
        if (a.values.size != b.values.size) return 0.0
        var s = 0.0
        for (i in a.values.indices) s += a.values[i] * b.values[i]
        return s / a.values.size
    }
}

/** Rectangle d'une [GrayImage]. */
internal data class GrayBox(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun crop(image: GrayImage): GrayImage {
        val out = ByteArray(width * height)
        for (j in 0 until height) System.arraycopy(image.pixels, (y + j) * image.width + x, out, j * width, width)
        return GrayImage(width, height, out)
    }
}

/**
 * Chiffre des unités d'un compteur dans sa zone : les colonnes où les pixels clairs sont fréquents forment des blocs ;
 * celui d'un chiffre change (écart-type de l'allumage proche de sa moyenne : 0,9 mesuré), celui d'une icône reste
 * allumé (0,45). Le bloc variable le plus à droite, une colonne de marge, sur les lignes où il s'allume.
 */
internal object UnitsFinder {
    fun find(frames: List<GrayImage>, bright: Int): GrayBox? {
        if (frames.isEmpty()) return null
        val w = frames[0].width
        val h = frames[0].height
        if (frames.any { it.width != w || it.height != h }) return null
        val sum = DoubleArray(w * h)
        val sq = DoubleArray(w * h)
        for (f in frames) for (i in 0 until w * h) {
            val on = if ((f.pixels[i].toInt() and 0xFF) >= bright) 1.0 else 0.0
            sum[i] += on
            sq[i] += on
        }
        val n = frames.size.toDouble()
        val mean = DoubleArray(w) { x -> (0 until h).sumOf { y -> sum[y * w + x] } / (n * h) }
        val std = DoubleArray(w) { x -> (0 until h).sumOf { y -> val m = sum[y * w + x] / n; sqrt((sq[y * w + x] / n - m * m).coerceAtLeast(0.0)) } / h }
        val blocks = mutableListOf<IntRange>()
        var x = 0
        while (x < w) {
            if (mean[x] > 0.12) {
                val start = x
                while (x + 1 < w && mean[x + 1] > 0.12) x++
                blocks += start..x
            }
            x++
        }
        val digit = blocks.lastOrNull { b -> b.sumOf { std[it] } / b.sumOf { mean[it] } > 0.6 } ?: return null
        val x0 = (digit.first - 1).coerceAtLeast(0)
        val x1 = (digit.last + 1).coerceAtMost(w - 1)
        val rows = (0 until h).filter { y -> digit.sumOf { sum[y * w + it] } / (n * digit.count()) > 0.1 }
        if (rows.isEmpty()) return null
        return GrayBox(x0, rows.first(), x1 - x0 + 1, rows.last() - rows.first() + 1)
    }
}

/** Compteur du HUD lu sans connaître ses chiffres : chaque nouvelle valeur stable est un événement (voir [ChangeTracker]). */
class HudChangeDetector(override val id: String, private val params: HudChangeParams) : SignalDetector {

    private class Prepared(val frames: MutableList<GrayImage?>, val subscription: FrameSampler.Subscription)

    @Volatile
    private var prepared: Prepared? = null

    override suspend fun prepare(ctx: AnalysisContext) {
        val video = ctx.media.video ?: return
        val region = params.referenceWidth?.let {
            ScreenGeometry.rescale(params.region, it.toDouble() / params.referenceHeight, video.width.toDouble() / video.height, params.anchor)
        } ?: params.region
        val scale = (params.referenceHeight.toDouble() / video.height).coerceAtMost(1.0)
        // Grille paire : en yuv420, FFmpeg ne recadre la luminance au pixel près que tous les 2 px (voir HudCounterDetector).
        val cx = ((region.x * video.width).roundToInt() and 1.inv()).coerceIn(0, video.width - 2)
        val cy = ((region.y * video.height).roundToInt() and 1.inv()).coerceIn(0, video.height - 2)
        val cw = ((region.width * video.width).roundToInt() + 1 and 1.inv()).coerceIn(2, (video.width - cx) and 1.inv())
        val ch = ((region.height * video.height).roundToInt() + 1 and 1.inv()).coerceIn(2, (video.height - cy) and 1.inv())
        val zone = FrameZone(cx, cy, cw, ch, (cw * scale).roundToInt().coerceAtLeast(2), (ch * scale).roundToInt().coerceAtLeast(2))
        log.info { "$id : compteur ${zone.width}x${zone.height} à ($cx, $cy), ${params.fps} img/s, valeurs comparées sans les lire" }
        val frames = mutableListOf<GrayImage?>()
        val subscription = ctx.frames.subscribe(
            spec = FrameSpec.fps(params.fps, params.hwaccel),
            zones = listOf(zone),
            label = id,
            progress = ctx.progress,
            onReset = { synchronized(frames) { frames.clear() } },
            onFrame = { index, rois ->
                val image = GrayImage(rois[0].width, rois[0].height, rois[0].pixels.copyOf())
                synchronized(frames) {
                    while (frames.size <= index) frames += null
                    frames[index] = image
                }
            },
        )
        prepared = Prepared(frames, subscription)
    }

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        if (ctx.media.video == null) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        if (prepared == null) prepare(ctx)
        val state = prepared ?: return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        val times = state.subscription.await()
        val frames = synchronized(state.frames) { state.frames.toList() }
        if (frames.isEmpty()) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noFrames"))
        val tracker = ChangeTracker(params.sameScore, params.stableFrames, params.minContrast)
        val units = if (params.unitsOnly) UnitsFinder.find(frames.filterNotNull(), params.bright) else null
        log.info { "$id : " + (units?.let { "unités en colonnes ${it.x}..${it.x + it.width - 1}, lignes ${it.y}..${it.y + it.height - 1}" } ?: "zone entière") }
        for (i in times.indices) frames.getOrNull(i)?.let { tracker.add(times[i], units?.crop(it) ?: it) }
        val events = tracker.events.map { SignalEvent(it, params.kind, 1.0) }
        log.info { "$id : ${events.size} ${params.kind} : " + events.joinToString { Durations.format(it.at) } }
        return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, events)
    }
}

class HudChangeDetectorFactory : SignalDetectorFactory {
    override val type = "hud-change"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        HudChangeDetector(id, params.decode(HudChangeParams.serializer()) { throw ConfigException(Message("error.detector.paramRequired", "id" to id, "params" to "region")) })
}
