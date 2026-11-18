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
import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.video.FrameSampler
import dev.highlights.core.video.FrameZone
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

@Serializable
data class HudCounterParams(
    /** Zone du nombre (normalisée), un peu plus large et haute que lui : le nombre y est calé à droite. */
    val region: CropRegion,
    /** Format de la capture sur laquelle zone et chiffres ont été relevés : la zone y est ramenée avant la lecture. */
    val referenceHeight: Int = 480,
    val referenceWidth: Int? = null,
    val anchor: RegionAnchor = RegionAnchor.RIGHT,
    /**
     * Dossier des chiffres (relatif au dossier de configuration) : `digit_0.png` … `digit_9.png`, chacun le chiffre
     * avec une colonne de marge de chaque côté, à l'échelle de [referenceHeight]. La largeur du fichier donne celle
     * du chiffre (les chiffres d'une police proportionnelle n'ont pas tous la même).
     */
    val digits: String,
    /** Type d'événement émis à chaque unité gagnée par le compteur. */
    val kind: String = "kill",
    /** Corrélation minimale pour accepter un chiffre lu ; en dessous, l'image est « illisible » (n'influe pas). */
    val threshold: Double = 0.75,
    /** Colonnes où chercher le bord droit des unités, depuis le bord droit de la zone (le HUD bouge d'un pixel). */
    val slack: Int = 4,
    /** Le compteur ne gagne jamais plus que ça d'un coup (une équipe entière éliminée, une coupe au montage). */
    val maxStep: Int = 5,
    val maxValue: Int = 99,
    /** Coûts du décodage (voir [CounterDecoder]) : un incrément, une remise à zéro (nouvelle partie), une lecture contraire. */
    val stepCost: Double = 3.0,
    val resetCost: Double = 25.0,
    val missCost: Double = 1.0,
    val sampling: Sampling = Sampling.AUTO,
    val hwaccel: String? = Hwaccel.AUTO,
    val fps: Double = 2.0,
    val maxKeyframeInterval: SerialDuration = 1500.milliseconds,
) {
    init {
        require(referenceHeight > 0 && fps > 0 && threshold in 0.0..1.0) { "paramètres invalides : $this" }
        require(slack >= 0 && maxStep >= 1 && maxValue in 1..999) { "paramètres invalides : $this" }
        require(stepCost >= 0 && resetCost >= 0 && missCost >= 0) { "coûts négatifs : $this" }
    }
}

/**
 * Compteur chiffré du HUD (éliminations de Fortnite en haut à droite…) lu image par image avec des modèles de
 * chiffres, puis décodé en une suite croissante : chaque unité gagnée devient un événement [HudCounterParams.kind].
 *
 * Pourquoi un compteur plutôt que la notification : sur une capture 480p (vidéo YouTube 854x480), l'OCR de Windows ne
 * lit ni la notification « ÉLIMINATION! », ni le journal d'XP (essai sur 17 images d'un quadruplé : rien d'utilisable),
 * et la notification ne reste qu'1,5 s. Le compteur, lui, reste affiché en permanence : une mauvaise lecture isolée
 * (chiffre de 5x7 px sur fond clair : un 2 pris pour un 3, un 0 pour un 2) est corrigée par celles d'avant et d'après.
 */
class HudCounterDetector(override val id: String, private val params: HudCounterParams) : SignalDetector {

    private class Prepared(val reads: MutableList<CounterRead?>, val subscription: FrameSampler.Subscription)

    @Volatile
    private var prepared: Prepared? = null

    override suspend fun prepare(ctx: AnalysisContext) {
        val video = ctx.media.video ?: return
        val reader = CounterReader.load(ctx.configDir.resolve(params.digits), params.threshold, params.slack)
        val region = params.referenceWidth?.let {
            ScreenGeometry.rescale(params.region, it.toDouble() / params.referenceHeight, video.width.toDouble() / video.height, params.anchor)
        } ?: params.region
        val scale = params.referenceHeight.toDouble() / video.height
        // Coordonnées et tailles paires : en yuv420, FFmpeg recadre la luminance au pixel près seulement sur une grille de
        // 2 px. Une zone en y = 15 (19x11) donnait des chiffres rééchantillonnés, écart moyen de 17 niveaux avec ceux des
        // modèles : 802 images illisibles sur 5 224 et 40 kills au lieu de 31. Sur une grille paire, l'écart est nul.
        val cx = ((region.x * video.width).roundToInt() and 1.inv()).coerceIn(0, video.width - 2)
        val cy = ((region.y * video.height).roundToInt() and 1.inv()).coerceIn(0, video.height - 2)
        val cw = ((region.width * video.width).roundToInt() + 1 and 1.inv()).coerceIn(2, (video.width - cx) and 1.inv())
        val ch = ((region.height * video.height).roundToInt() + 1 and 1.inv()).coerceIn(2, (video.height - cy) and 1.inv())
        val zone = FrameZone(cx, cy, cw, ch, (cw * scale).roundToInt().coerceAtLeast(1), (ch * scale).roundToInt().coerceAtLeast(1))
        if (zone.height < reader.height) {
            throw ConfigException(Message("error.detector.zoneSmallerThanTemplate", "id" to id, "name" to "digits", "zone" to "${zone.width}x${zone.height}", "template" to "?x${reader.height}"))
        }
        val spec = chooseSampling(ctx, id, params.sampling, params.fps, params.hwaccel, params.maxKeyframeInterval)
        log.info { "$id : compteur ${zone.width}x${zone.height}, ${if (spec.keyframes) "images clés (~${spec.interval})" else "${params.fps} img/s"}" }
        val reads = mutableListOf<CounterRead?>()
        val subscription = ctx.frames.subscribe(
            spec = spec,
            zones = listOf(zone),
            label = id,
            progress = ctx.progress,
            onReset = { synchronized(reads) { reads.clear() } },
            onFrame = { index, rois ->
                val read = reader.read(GrayImage(rois[0].width, rois[0].height, rois[0].pixels))
                synchronized(reads) {
                    while (reads.size <= index) reads += null
                    reads[index] = read
                }
            },
        )
        prepared = Prepared(reads, subscription)
    }

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        if (ctx.media.video == null) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        if (prepared == null) prepare(ctx)
        val state = prepared ?: return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        val times = state.subscription.await()
        val values = synchronized(state.reads) { List(times.size) { state.reads.getOrNull(it)?.value } }
        if (values.isEmpty()) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noFrames"))
        val decoder = CounterDecoder(params.maxValue, params.maxStep, params.stepCost, params.resetCost, params.missCost)
        val path = decoder.decode(values)
        val events = mutableListOf<SignalEvent>()
        for (i in 1 until path.size) {
            val gained = path[i] - path[i - 1]
            repeat(gained.coerceAtLeast(0)) { events += SignalEvent(times[i], params.kind, 1.0) }
        }
        val unread = values.count { it == null }
        log.info {
            "$id : ${events.size} ${params.kind} (lu sur ${values.size - unread}/${values.size} images) à " +
                CounterDecoder.changes(path).joinToString { (i, from, to) -> "${Durations.format(times[i])} $from→$to" }
        }
        return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, events)
    }
}

/** Nombre lu sur une image et corrélation du chiffre le moins sûr. */
data class CounterRead(val value: Int, val score: Double)

/**
 * Lecture d'un nombre calé à droite (une ou deux positions) par corrélation avec les modèles de chiffres : les unités
 * sont cherchées sur les [slack] dernières colonnes et toute la hauteur de la zone, les dizaines juste à leur gauche
 * (une colonne d'écart, ou deux), à la même hauteur.
 */
class CounterReader(private val digits: Map<Int, GrayImage>, private val threshold: Double, private val slack: Int) {
    init {
        require(digits.isNotEmpty()) { "aucun chiffre" }
        require(digits.values.map { it.height }.distinct().size == 1) { "les chiffres doivent avoir la même hauteur" }
    }

    val height: Int = digits.values.first().height

    private class Prepared(val digit: Int, val image: GrayImage) {
        val width = image.width
        val values = DoubleArray(image.width * image.height) { (image.pixels[it].toInt() and 0xFF).toDouble() }
        val mean = values.average()
        val norm = sqrt(values.sumOf { (it - mean) * (it - mean) })
    }

    private val prepared = digits.map { (d, img) -> Prepared(d, img) }

    /** Corrélation normalisée du modèle avec la fenêtre de [image] dont le coin haut gauche est (x, y). */
    private fun ncc(image: GrayImage, t: Prepared, x: Int, y: Int): Double {
        var sum = 0.0
        val n = t.values.size
        for (j in 0 until height) for (i in 0 until t.width) sum += image[x + i, y + j]
        val mean = sum / n
        var cross = 0.0
        var energy = 0.0
        for (j in 0 until height) for (i in 0 until t.width) {
            val v = image[x + i, y + j] - mean
            cross += v * (t.values[j * t.width + i] - t.mean)
            energy += v * v
        }
        val d = sqrt(energy) * t.norm
        return if (d < 1e-6) 0.0 else cross / d
    }

    fun read(image: GrayImage): CounterRead? {
        if (image.height < height) return null
        // Unités : bord droit du chiffre (avant sa colonne de marge) sur les dernières colonnes de la zone.
        var best: Triple<Prepared, Int, Int>? = null
        var bestScore = -1.0
        for (t in prepared) for (y in 0..image.height - height) {
            for (right in image.width - 2 - slack..image.width - 2) {
                val x = right + 2 - t.width
                if (x < 0) continue
                val s = ncc(image, t, x, y)
                if (s > bestScore) { bestScore = s; best = Triple(t, right, y) }
            }
        }
        val (units, right, y) = best ?: return null
        if (bestScore < threshold) return null
        val left = right - (units.width - 2) + 1
        var tens: Prepared? = null
        var tensScore = -1.0
        for (t in prepared) {
            if (t.digit == 0) continue
            for (r in left - 3..left - 2) {
                val x = r + 2 - t.width
                if (x < 0) continue
                val s = ncc(image, t, x, y)
                if (s > tensScore) { tensScore = s; tens = t }
            }
        }
        return if (tens != null && tensScore >= threshold) {
            CounterRead(tens.digit * 10 + units.digit, minOf(bestScore, tensScore))
        } else {
            CounterRead(units.digit, bestScore)
        }
    }

    companion object {
        fun load(dir: java.nio.file.Path, threshold: Double, slack: Int): CounterReader =
            CounterReader((0..9).associateWith { GrayImage.load(dir.resolve("digit_$it.png")) }, threshold, slack)
    }
}

/**
 * Suite de valeurs la plus probable (Viterbi) : le compteur reste, gagne de 1 à [maxStep] (coût [stepCost], plus un
 * demi-point par unité au-delà de la première), ou repart de zéro au début d'une partie ([resetCost]). Chaque image lue
 * rapporte 1 si elle dit la valeur supposée, coûte [missCost] sinon ; une image illisible ne compte pas.
 *
 * Réglé sur une vidéo de 43 min (3 parties, 5 224 images à 2 img/s, 118 illisibles) : les lectures brutes changeaient
 * de valeur 360 fois, le décodage donne les 31 éliminations de la partie, chacune vérifiée à l'œil sur le compteur,
 * stable pour un coût de remise à zéro de 15 à 40 (à 6, les « 0 » lus « 2 » du salon d'attente passaient pour des kills).
 */
class CounterDecoder(
    private val maxValue: Int,
    private val maxStep: Int,
    private val stepCost: Double,
    private val resetCost: Double,
    private val missCost: Double,
) {
    fun decode(reads: List<Int?>): IntArray {
        val n = reads.size
        if (n == 0) return IntArray(0)
        val states = maxValue + 1
        var score = DoubleArray(states)
        val back = Array(n) { IntArray(states) }
        for (i in 0 until n) {
            val next = DoubleArray(states)
            var bestPrev = 0
            for (v in 1 until states) if (score[v] > score[bestPrev]) bestPrev = v
            val r = reads[i]
            for (v in 0 until states) {
                var s = score[v]
                var from = v
                for (k in 1..maxStep) {
                    if (v - k < 0) break
                    val c = score[v - k] - stepCost - 0.5 * (k - 1)
                    if (c > s) { s = c; from = v - k }
                }
                if (v == 0 && i > 0 && score[bestPrev] - resetCost > s) { s = score[bestPrev] - resetCost; from = bestPrev }
                val emit = when (r) { null -> 0.0; v -> 1.0; else -> -missCost }
                next[v] = s + emit
                back[i][v] = if (i == 0) v else from
            }
            score = next
        }
        var v = 0
        for (k in 1 until states) if (score[k] > score[v]) v = k
        val path = IntArray(n)
        for (i in n - 1 downTo 0) {
            path[i] = v
            v = back[i][v]
        }
        return path
    }

    companion object {
        /** Changements de valeur : (indice, avant, après). */
        fun changes(path: IntArray): List<Triple<Int, Int, Int>> =
            (1 until path.size).filter { path[it] != path[it - 1] }.map { Triple(it, path[it - 1], path[it]) }
    }
}

class HudCounterDetectorFactory : SignalDetectorFactory {
    override val type = "hud-counter"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        HudCounterDetector(id, params.decode(HudCounterParams.serializer()) { throw ConfigException(Message("error.detector.paramRequired", "id" to id, "params" to "region, digits")) })
}
