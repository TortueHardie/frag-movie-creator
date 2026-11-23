package dev.highlights.vision

import dev.highlights.core.ConfigException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalTrack
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
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Score des rounds lu dans la barre du haut du HUD (VALORANT : l'équipe du joueur à gauche, en sarcelle, l'adversaire à
 * droite, de part et d'autre du chrono). Zones et chiffres relevés sur une capture 854x480 en 16:9 : chaque chiffre fait
 * 9 px de haut et des traits d'un pixel.
 */
@Serializable
data class RoundScoreParams(
    /** Zone du score de l'équipe du joueur (normalisée), le nombre centré dedans, un peu de marge autour. */
    val ally: CropRegion = CropRegion(0.414520, 0.029167, 0.030445, 0.0375),
    /** Zone du score adverse. */
    val enemy: CropRegion = CropRegion(0.557377, 0.029167, 0.030445, 0.0375),
    /** Format de la capture où zones et chiffres ont été relevés : la zone y est ramenée avant la lecture. */
    val referenceWidth: Int = 854,
    val referenceHeight: Int = 480,
    /** La barre du haut reste centrée quel que soit le format (21:9 compris). */
    val anchor: RegionAnchor = RegionAnchor.CENTER,
    /** Dossier des chiffres (relatif au dossier de configuration) : `digit_0.png` … `digit_9.png`, même hauteur. */
    val digits: String = "templates/valorant/score",
    /**
     * Colonnes de fond entre les deux chiffres d'un nombre, marges des modèles retirées (mesuré sur « 10 » à « 13 » : avec
     * 2, « 12 » passait pour « 22 » et « 11 » pour « 10 » ; avec 3, « 13 » pour « 10 » ; avec 4, tous justes).
     */
    val gap: Int = 4,
    /**
     * Écarts essayés autour de [gap] pour les nombres de deux chiffres. Mesuré le 23/11 sur une partie 3440x1440 (zone
     * réduite au tiers) : avec 4 seul, « 10 » passait pour « 13 » et « 12 » restait fragile ; avec 3 seul, « 11 » pour
     * « 17 » et « 13 » pour « 10 » (le « 1 » n'a pas la même chasse que dans la vidéo 480p). Avec 3 à 5, les 24 zones
     * 480p et les 17 zones 1440p sont justes (`round-score`, `round-score-hd` des tests).
     */
    val gapSlack: Int = 1,
    /** Décalage horizontal toléré du nombre autour du centre de la zone, en pixels de référence. */
    val slack: Int = 2,
    /** Plus haut score cherché (prolongations comprises). */
    val maxValue: Int = 30,
    /** Corrélation minimale d'une lecture ; en dessous, l'image ne dit rien (ciel clair derrière le score, écran de fin). */
    val threshold: Double = 0.6,
    /** Coûts du décodage ([ScoreDecoder]) : un round, un retour à 0-0 (nouvelle partie), une lecture contraire. */
    val stepCost: Double = 3.0,
    val resetCost: Double = 25.0,
    val missCost: Double = 1.0,
    /** Moins d'images lues que ça (part) : pas de score dans cette capture, rien n'est émis. */
    val minRead: Double = 0.3,
    val sampling: Sampling = Sampling.AUTO,
    /**
     * Décodage logiciel : le matériel ternit les traits d'un pixel des petites vidéos (voir `killfeed-teams`). Le score
     * change une fois par round : 2 img/s suffisent. Les images clés seulement si elles tombent au moins chaque seconde :
     * celles de « Valorant- 44 kills », mesurées à moins de 2 s au milieu mais irrégulières, dataient les changements
     * jusqu'à 7 s en retard et perdaient le dernier round (13-10, 16 s avant la fin).
     */
    val hwaccel: String? = null,
    val fps: Double = 2.0,
    val maxKeyframeInterval: SerialDuration = 1.seconds,
    /** Round gagné par l'équipe du joueur, perdu, retour à 0-0. */
    val wonKind: String = "round-won",
    val lostKind: String = "round-lost",
    val resetKind: String = "score-reset",
) {
    init {
        require(referenceWidth > 0 && referenceHeight > 0 && fps > 0) { "round-score : tailles et cadence positives" }
        require(threshold in 0.0..1.0 && minRead in 0.0..1.0) { "round-score : threshold et minRead entre 0 et 1" }
        require(gap >= 0 && slack >= 0 && maxValue in 1..99) { "round-score : gap, slack ≥ 0, maxValue entre 1 et 99" }
        require(stepCost >= 0 && resetCost >= 0 && missCost >= 0) { "round-score : coûts négatifs" }
    }
}

/**
 * Lecture d'un nombre centré dans sa zone (score des rounds) : chaque nombre de 0 à [maxValue] est composé à partir des
 * chiffres, posé sur un fond uni de la largeur de la zone, et comparé à toute la zone par corrélation normalisée. Toute
 * la largeur compte : un modèle d'un seul chiffre comparé à une partie du nombre (« 1 » dans « 11 ») gagnait sinon.
 */
class ScoreReader(
    digits: Map<Int, GrayImage>,
    val width: Int,
    private val threshold: Double,
    gap: Int,
    slack: Int,
    maxValue: Int,
    gapSlack: Int = 0,
) {
    init {
        require(digits.isNotEmpty()) { "aucun chiffre" }
        require(digits.values.map { it.height }.distinct().size == 1) { "les chiffres doivent avoir la même hauteur" }
    }

    val height: Int = digits.values.first().height

    private class Candidate(val value: Int, val pixels: DoubleArray)

    private val candidates: List<Candidate> = buildList {
        for (n in 0..maxValue) {
            val parts = n.toString().map { digits[it.digitToInt()] ?: return@map null }
            if (parts.any { it == null }) continue
            // Un seul chiffre : l'écart ne compte pas, une seule composition.
            val gaps = if (parts.size > 1) (gap - gapSlack).coerceAtLeast(0)..gap + gapSlack else gap..gap
            for (g in gaps) {
                val number = compose(parts.filterNotNull(), g)
                val x0 = kotlin.math.round((width - 1) / 2.0 - number.second / 2.0).toInt()
                for (o in -slack..slack) normalized(place(number, x0 + o))?.let { add(Candidate(n, it)) }
            }
        }
    }

    /** Nombre composé : pixels (ligne par ligne) et largeur. Marges intérieures des modèles retirées entre deux chiffres. */
    private fun compose(parts: List<GrayImage>, gap: Int): Pair<DoubleArray, Int> {
        val bg = parts.map { border(it) }.average()
        val columns = mutableListOf<DoubleArray>()
        parts.forEachIndexed { k, img ->
            val from = if (k > 0) 1 else 0
            val to = if (k < parts.lastIndex) img.width - 1 else img.width
            if (k > 0) repeat(gap) { columns += DoubleArray(height) { bg } }
            for (x in from until to) columns += DoubleArray(height) { y -> img[x, y].toDouble() }
        }
        val w = columns.size
        // Le fond autour du nombre : celui de ses modèles.
        return DoubleArray(w * height + 1) { i -> if (i == w * height) bg else columns[i % w][i / w] } to w
    }

    private fun border(img: GrayImage): Double {
        var sum = 0.0
        var n = 0
        for (x in 0 until img.width) { sum += img[x, 0] + img[x, img.height - 1]; n += 2 }
        for (y in 0 until img.height) { sum += img[0, y] + img[img.width - 1, y]; n += 2 }
        return sum / n
    }

    private fun place(number: Pair<DoubleArray, Int>, x: Int): DoubleArray {
        val (pixels, w) = number
        val bg = pixels.last()
        return DoubleArray(width * height) { i ->
            val cx = i % width - x
            if (cx in 0 until w) pixels[(i / width) * w + cx] else bg
        }
    }

    private fun normalized(v: DoubleArray): DoubleArray? {
        val mean = v.average()
        val out = DoubleArray(v.size) { v[it] - mean }
        val norm = sqrt(out.sumOf { it * it })
        if (norm < 1e-6) return null
        for (i in out.indices) out[i] /= norm
        return out
    }

    /** Nombre lu et sa corrélation, ou null sous le seuil. La zone fait [width] de large (ramenée à la référence). */
    fun read(image: GrayImage): CounterRead? {
        if (image.width != width || image.height < height) return null
        var best = -1.0
        var value = -1
        val window = DoubleArray(width * height)
        for (y in 0..image.height - height) {
            for (j in 0 until height) for (i in 0 until width) window[j * width + i] = image[i, y + j].toDouble()
            val w = normalized(window) ?: continue
            for (c in candidates) {
                var s = 0.0
                for (k in w.indices) s += w[k] * c.pixels[k]
                if (s > best) { best = s; value = c.value }
            }
        }
        return if (value >= 0 && best >= threshold) CounterRead(value, best) else null
    }

    companion object {
        fun load(dir: Path, width: Int, threshold: Double, gap: Int, slack: Int, maxValue: Int, gapSlack: Int = 0): ScoreReader {
            val files = (0..9).associateWith { dir.resolve("digit_$it.png") }.filterValues { it.toFile().isFile }
            if (files.isEmpty()) GrayImage.load(dir.resolve("digit_0.png"))
            return ScoreReader(files.mapValues { GrayImage.load(it.value) }, width, threshold, gap, slack, maxValue, gapSlack)
        }
    }
}

/**
 * Suite des scores la plus probable (Viterbi sur les couples), les deux côtés décodés ensemble : d'une image à l'autre le
 * score reste, ou un seul côté gagne un round (coût [stepCost]), ou tout repart de 0-0 ([resetCost], une nouvelle
 * partie dans la même capture). Chaque lecture d'un côté rapporte 1 si elle dit la valeur supposée, coûte [missCost]
 * sinon ; une image illisible ne compte pas. Décoder les deux côtés ensemble interdit qu'un round compte des deux côtés.
 */
class ScoreDecoder(
    private val maxValue: Int,
    private val stepCost: Double,
    private val resetCost: Double,
    private val missCost: Double,
) {
    fun decode(ally: List<Int?>, enemy: List<Int?>): List<Pair<Int, Int>> {
        require(ally.size == enemy.size) { "autant de lectures des deux côtés" }
        val n = ally.size
        if (n == 0) return emptyList()
        val s = maxValue + 1
        var score = DoubleArray(s * s)
        // Origine de chaque état : -1 reste, -2 côté joueur +1, -3 côté adverse +1, sinon indice d'où l'on repart à 0-0.
        val back = Array(n) { IntArray(s * s) }
        for (i in 0 until n) {
            val next = DoubleArray(s * s)
            var bestPrev = 0
            for (k in 1 until s * s) if (score[k] > score[bestPrev]) bestPrev = k
            val ra = ally[i]
            val rb = enemy[i]
            for (a in 0 until s) for (b in 0 until s) {
                val k = a * s + b
                var v = score[k]
                var from = -1
                if (i > 0) {
                    if (a > 0 && score[k - s] - stepCost > v) { v = score[k - s] - stepCost; from = -2 }
                    if (b > 0 && score[k - 1] - stepCost > v) { v = score[k - 1] - stepCost; from = -3 }
                    if (k == 0 && score[bestPrev] - resetCost > v) { v = score[bestPrev] - resetCost; from = bestPrev }
                }
                var emit = 0.0
                if (ra != null) emit += if (ra == a) 1.0 else -missCost
                if (rb != null) emit += if (rb == b) 1.0 else -missCost
                next[k] = v + emit
                back[i][k] = from
            }
            score = next
        }
        var k = 0
        for (j in 1 until s * s) if (score[j] > score[k]) k = j
        val path = arrayOfNulls<Pair<Int, Int>>(n)
        for (i in n - 1 downTo 0) {
            path[i] = k / s to k % s
            k = when (val from = back[i][k]) {
                -1 -> k
                -2 -> k - s
                -3 -> k - 1
                else -> from
            }
        }
        return path.map { it!! }
    }

    companion object {
        /**
         * Événements d'une suite de scores ([times] : instant de chaque image) : le score de départ (rounds déjà joués,
         * capture commencée en cours de partie) à la première image, puis chaque round entre les deux images qui l'encadrent, et
         * [resetKind] à chaque retour à 0-0. Le score à un instant est le compte de ces événements jusque-là.
         */
        fun events(path: List<Pair<Int, Int>>, times: List<Duration>, wonKind: String, lostKind: String, resetKind: String): List<SignalEvent> {
            if (path.isEmpty()) return emptyList()
            val out = mutableListOf<SignalEvent>()
            val (a0, b0) = path.first()
            repeat(a0) { out += SignalEvent(times[0], wonKind, 1.0) }
            repeat(b0) { out += SignalEvent(times[0], lostKind, 1.0) }
            for (i in 1 until path.size) {
                val (pa, pb) = path[i - 1]
                val (a, b) = path[i]
                // Au milieu des deux images qui encadrent le changement : datée à la première image qui le montre, la
                // bascule du bandeau suivait celle du HUD recollé jusqu'à une demi-seconde plus tard (vu à 2 img/s).
                val at = times[i - 1] + (times[i] - times[i - 1]) / 2
                when {
                    a == pa + 1 && b == pb -> out += SignalEvent(at, wonKind, 1.0)
                    b == pb + 1 && a == pa -> out += SignalEvent(at, lostKind, 1.0)
                    a == 0 && b == 0 && (pa != 0 || pb != 0) -> out += SignalEvent(at, resetKind, 1.0)
                }
            }
            return out
        }
    }
}

/**
 * Score des rounds lu dans la barre du haut : un événement [RoundScoreParams.wonKind] par round gagné par l'équipe du
 * joueur, [RoundScoreParams.lostKind] par round perdu (le bandeau de score du montage en refait le compte à chaque
 * kill). Les lectures image par image se trompent parfois (score derrière un ciel clair, un flash, l'écran de fin) ; le
 * décodage des deux côtés ensemble ([ScoreDecoder]) les corrige.
 *
 * Mesuré sur deux parties 854x480 (vidéos YouTube, montées : phases d'achat coupées) à 2 img/s : 98 à 99 % des images
 * lues d'un côté disent le score décodé ; 23 et 25 changements trouvés, tous justes (vérifiés à l'œil, avant et après
 * chacun), fins 13-10 et 12-13 comme les tableaux de fin. Non vérifié en 1080p ni 1440p.
 */
class RoundScoreDetector(override val id: String, private val params: RoundScoreParams) : SignalDetector {

    private class Prepared(val ally: MutableList<CounterRead?>, val enemy: MutableList<CounterRead?>, val subscription: FrameSampler.Subscription)

    @Volatile
    private var prepared: Prepared? = null

    override suspend fun prepare(ctx: AnalysisContext) {
        val video = ctx.media.video ?: return
        val scale = params.referenceHeight.toDouble() / video.height
        fun zone(r: CropRegion): FrameZone {
            val region = ScreenGeometry.rescale(r, params.referenceWidth.toDouble() / params.referenceHeight, video.width.toDouble() / video.height, params.anchor)
            // Coordonnées et tailles paires, comme `hud-counter` : en yuv420, FFmpeg recadre au pixel près seulement sur
            // une grille de 2 px ; hors grille, les chiffres d'un pixel de large sont rééchantillonnés.
            val cx = ((region.x * video.width).roundToInt() and 1.inv()).coerceIn(0, video.width - 2)
            val cy = ((region.y * video.height).roundToInt() and 1.inv()).coerceIn(0, video.height - 2)
            val cw = ((region.width * video.width).roundToInt() + 1 and 1.inv()).coerceIn(2, (video.width - cx) and 1.inv())
            val ch = ((region.height * video.height).roundToInt() + 1 and 1.inv()).coerceIn(2, (video.height - cy) and 1.inv())
            return FrameZone(cx, cy, cw, ch, (cw * scale).roundToInt().coerceAtLeast(1), (ch * scale).roundToInt().coerceAtLeast(1))
        }
        val allyZone = zone(params.ally)
        val enemyZone = zone(params.enemy)
        val reader = ScoreReader.load(ctx.configDir.resolve(params.digits), allyZone.width, params.threshold, params.gap, params.slack, params.maxValue, params.gapSlack)
        if (allyZone.height < reader.height || enemyZone.width != allyZone.width) {
            throw ConfigException(Message("error.detector.zoneSmallerThanTemplate", "id" to id, "name" to "digits", "zone" to "${allyZone.width}x${allyZone.height}", "template" to "?x${reader.height}"))
        }
        val spec = chooseSampling(ctx, id, params.sampling, params.fps, params.hwaccel, params.maxKeyframeInterval)
        log.info { "$id : score ${allyZone.width}x${allyZone.height} par côté, ${if (spec.keyframes) "images clés (~${spec.interval})" else "${params.fps} img/s"}" }
        val ally = mutableListOf<CounterRead?>()
        val enemy = mutableListOf<CounterRead?>()
        val subscription = ctx.frames.subscribe(
            spec = spec,
            zones = listOf(allyZone, enemyZone),
            label = id,
            progress = ctx.progress,
            onReset = { synchronized(ally) { ally.clear(); enemy.clear() } },
            onFrame = { index, rois ->
                val a = reader.read(GrayImage(rois[0].width, rois[0].height, rois[0].pixels))
                val b = reader.read(GrayImage(rois[1].width, rois[1].height, rois[1].pixels))
                synchronized(ally) {
                    while (ally.size <= index) { ally += null; enemy += null }
                    ally[index] = a
                    enemy[index] = b
                }
            },
        )
        prepared = Prepared(ally, enemy, subscription)
    }

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        if (ctx.media.video == null) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        if (prepared == null) prepare(ctx)
        val state = prepared ?: return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        val times = state.subscription.await()
        val (ally, enemy) = synchronized(state.ally) {
            List(times.size) { state.ally.getOrNull(it)?.value } to List(times.size) { state.enemy.getOrNull(it)?.value }
        }
        if (times.isEmpty()) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noFrames"))
        val read = (ally.count { it != null } + enemy.count { it != null }) / (2.0 * times.size)
        val empty = SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN })
        if (read < params.minRead) {
            log.info { "$id : score lu sur ${(read * 100).roundToInt()} % des images seulement, pas de score dans cette capture" }
            return empty
        }
        val path = ScoreDecoder(params.maxValue, params.stepCost, params.resetCost, params.missCost).decode(ally, enemy)
        val events = ScoreDecoder.events(path, times, params.wonKind, params.lostKind, params.resetKind)
        log.info {
            "$id : score lu sur ${(read * 100).roundToInt()} % des images, ${path.first().first}-${path.first().second} au début, " +
                (1 until path.size).filter { path[it] != path[it - 1] }.joinToString { "${Durations.format(times[it])} ${path[it].first}-${path[it].second}" }
        }
        return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, events)
    }
}

class RoundScoreDetectorFactory : SignalDetectorFactory {
    override val type = "round-score"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        RoundScoreDetector(id, params.decode(RoundScoreParams.serializer()) { RoundScoreParams() })
}
