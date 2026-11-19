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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    /**
     * Mises à terre qui font le kill (Fortnite en escouade) : chaque unité du compteur est rattachée à une mise à terre
     * d'avant (événements du détecteur nommé dans `dependsOn`), et le [kind] est émis à l'instant de la mise à terre.
     * null : le [kind] est émis quand le compteur monte. Voir [KnockPairing].
     */
    val knocks: KnockSettings? = null,
) {
    init {
        require(referenceHeight > 0 && fps > 0 && threshold in 0.0..1.0) { "paramètres invalides : $this" }
        require(slack >= 0 && maxStep >= 1 && maxValue in 1..999) { "paramètres invalides : $this" }
        require(stepCost >= 0 && resetCost >= 0 && missCost >= 0) { "coûts négatifs : $this" }
    }
}

@Serializable
data class KnockSettings(
    /** Événement de mise à terre, lu dans les événements des détecteurs de `dependsOn`. */
    val kind: String = "knock",
    /** Une mise à terre est rattachée à une élimination qui la suit de moins que ça. */
    val window: SerialDuration = 30.seconds,
    /** … ou qui la précède d'au plus ça (la notification de mise à terre est vue sur un échantillonnage plus fin). */
    val after: SerialDuration = 1.seconds,
    /** Événement émis à chaque unité du compteur, à l'instant où il monte (vérité terrain, statistiques). */
    val eliminationKind: String = "elimination",
    /** Une mise à terre jamais créditée au joueur (achevée par un coéquipier, fin de partie) reste un kill. */
    val unpaired: Boolean = true,
) {
    init {
        require(window.isPositive() && !after.isNegative()) { "fenêtre de rattachement invalide : $this" }
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
        val increments = (1 until path.size).filter { path[it] > path[it - 1] }.map { times[it] to path[it] - path[it - 1] }
        val unread = values.count { it == null }
        log.info {
            "$id : ${increments.sumOf { it.second }} unité(s) (lu sur ${values.size - unread}/${values.size} images) à " +
                CounterDecoder.changes(path).joinToString { (i, from, to) -> "${Durations.format(times[i])} $from→$to" }
        }
        val knocks = params.knocks
        val events = if (knocks == null) {
            increments.flatMap { (t, k) -> List(k) { SignalEvent(t, params.kind, 1.0) } }
        } else {
            val knocked = ctx.events.filter { it.kind == knocks.kind }.map { it.at }.sorted()
            val pairing = KnockPairing.pair(increments, knocked, knocks.window, knocks.after, knocks.unpaired)
            log.info { "$id : ${pairing.kills.size} ${params.kind} sur ${knocked.size} mise(s) à terre ; ${pairing.summary()}" }
            pairing.kills.map { SignalEvent(it, params.kind, 1.0) } +
                increments.flatMap { (t, k) -> List(k) { SignalEvent(t, knocks.eliminationKind, 1.0) } }
        }
        return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, events.sortedBy { it.at })
    }
}

/**
 * Kills d'une partie en escouade où « mettre à terre, c'est le kill » (choix du joueur, 19/11) : en Fortnite, un
 * adversaire est d'abord mis à terre (« KNOCKED! »), puis éliminé quand on l'achève ou que toute son équipe tombe, et
 * c'est seulement là que le compteur monte, 0,5 à 30 s plus tard. Le tir qui compte est celui de la mise à terre.
 *
 * - +1 du compteur : rattaché à la plus ancienne mise à terre libre de [window] avant (jusqu'à [after] après) ; le kill
 *   est à l'instant de la mise à terre. Sans mise à terre (dernier de son équipe, éliminé d'un coup), il reste à
 *   l'instant du compteur. Une élimination qui achève un adversaire déjà à terre ne fait donc pas un second kill.
 * - +k d'un coup (équipe entière éliminée) : k kills au même instant, un seul plan « QUADRUPLÉ » comme avant ; les
 *   mises à terre de cette équipe ([window] avant) sont absorbées.
 * - Mise à terre jamais suivie d'une élimination du joueur (achevée par un coéquipier, partie gagnée sur ce tir) : un
 *   kill si [unpaired].
 *
 * Mesuré sur la vidéo de réglage (31 éliminations, 27 mises à terre), fenêtre de 30 s : 15 éliminations ramenées à
 * leur mise à terre (0,5 à 16 s avant), 5 équipes éliminées d'un coup (12 kills, 10 mises à terre absorbées), 4
 * éliminations sans mise à terre (aucune notification manquée avant, vérifié à l'œil), 2 mises à terre libres (11:33,
 * et la dernière avant « Victory Royale » à 27:22) : 33 kills. À 20 s, deux mises à terre d'une équipe éliminée 23 et
 * 27 s plus tard (12:31, 12:35) restaient libres et doublaient ce doublé.
 */
data class KnockPairing(val kills: List<Duration>, val paired: Int, val wipes: Int, val lone: Int, val free: Int) {
    fun summary() = "$paired rattachée(s), $wipes équipe(s) éliminée(s) d'un coup, $lone élimination(s) sans mise à terre, $free mise(s) à terre libre(s)"

    companion object {
        fun pair(
            increments: List<Pair<Duration, Int>>,
            knocks: List<Duration>,
            window: Duration,
            after: Duration,
            unpaired: Boolean,
        ): KnockPairing {
            val sorted = knocks.sorted()
            val used = BooleanArray(sorted.size)
            val kills = mutableListOf<Duration>()
            var paired = 0
            var wipes = 0
            var lone = 0
            for ((t, k) in increments.sortedBy { it.first }) {
                val free = sorted.indices.filter { !used[it] && sorted[it] >= t - window && sorted[it] <= t + after }
                free.take(k).forEach { used[it] = true }
                when {
                    k == 1 && free.isNotEmpty() -> { kills += sorted[free.first()]; paired++ }
                    k == 1 -> { kills += t; lone++ }
                    else -> { repeat(k) { kills += t }; wipes++ }
                }
            }
            val left = sorted.indices.filter { !used[it] }.map { sorted[it] }
            if (unpaired) kills += left
            return KnockPairing(kills.sorted(), paired, wipes, lone, left.size)
        }
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
