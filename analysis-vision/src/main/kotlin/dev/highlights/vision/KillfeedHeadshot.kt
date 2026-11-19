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
import dev.highlights.core.i18n.tr
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.video.FrameSpec
import dev.highlights.core.video.ZoneFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * Tirs à la tête lus dans le killfeed : VALORANT met une icône (une cible) entre l'arme et la victime de chaque kill à
 * la tête, sur les lignes du joueur comme sur les autres. Revérifié le 19/11 sur les deux captures du disque (854x480,
 * deux joueurs) : 18 icônes sur 46 kills d'une partie, 9 sur 15 de l'autre ; le profil disait l'icône absente des lignes
 * du joueur, à tort.
 *
 * Ne relit que de [before] avant à [after] après chaque kill des détecteurs dont il dépend : la ligne du joueur y est
 * retrouvée comme le fait le killfeed ([killfeed], même zone, même lecteur), le début de la victime (fond rouge de
 * l'adversaire) y est cherché à droite du portrait, et l'icône juste avant lui, par corrélation avec [template].
 *
 * Mesuré à l'image sur les 46 kills de la première partie (gabarit moyen de 9 icônes nettes de cette partie), analyse
 * complète : icônes à 0,69-0,94, kills sans icône à 0,45 au plus. Seuil 0,6 : 18 sur 18, aucun faux. Sur la seconde
 * partie, jamais vue pour le régler : 8 sur 9, aucun faux (le manqué à 0,57, sous trois autres lignes).
 *
 * Avec Outplayed (événements [kind] déjà donnés par les détecteurs dont il dépend), ne relit rien.
 */
@Serializable
data class KillfeedHeadshotParams(
    /** Le killfeed tel que le lit le détecteur `killfeed` du profil : zone, couleur du joueur, formes. */
    val killfeed: KillfeedParams,
    /** Icône du tir à la tête, en niveaux de gris (le canal le plus sombre : le blanc de l'icône sur le fond sarcelle). */
    val template: String,
    /** Hauteur de la capture d'où vient [template] (ses pixels sont ceux de cette capture). */
    val templateHeight: Int = 480,
    val threshold: Double = 0.6,
    /** Kills à examiner. */
    val kill: String = "kill",
    /** Événement émis, à l'instant du kill examiné. */
    val kind: String = "headshot",
    /** La ligne s'affiche 0,15 s après le kill du killfeed (0,39 s avant celui d'Outplayed recalé), puis glisse 0,4 s. */
    val before: SerialDuration = 400.milliseconds,
    val after: SerialDuration = 1600.milliseconds,
    val fps: Double = 10.0,
    val hwaccel: String? = Hwaccel.AUTO,
    /** Hauteur d'une ligne du killfeed, en pixels de référence du [killfeed] (48 en 1440p). */
    val lineHeight: Int = 48,
    /** Largeur cherchée avant le début de la victime : l'icône et son écart (24 px de 480p). */
    val searchWidth: Int = 72,
    /** Fond rouge de la victime sur au moins cette largeur (en pixels de référence) : un nom n'est jamais plus court. */
    val minVictim: Int = 36,
) {
    init {
        require(threshold in 0.0..1.0) { "killfeed-headshot : threshold entre 0 et 1" }
        require(fps > 0 && templateHeight > 0) { "killfeed-headshot : fps et templateHeight positifs" }
        require(before.isPositive() && after.isPositive()) { "killfeed-headshot : before et after positifs" }
    }
}

class KillfeedHeadshotDetector(override val id: String, private val params: KillfeedHeadshotParams) : SignalDetector {

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        val raw = DoubleArray(ctx.grid.count) { Double.NaN }
        val video = ctx.media.video ?: return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        if (ctx.events.any { it.kind == params.kind }) {
            log.info { "$id : tirs à la tête déjà donnés par le jeu, killfeed non relu" }
            return SignalTrack(id, raw)
        }
        val kills = ctx.events.filter { it.kind == params.kill }.map { it.at }.distinct().sorted()
        if (kills.isEmpty()) return SignalTrack(id, raw)
        val setup = feedSetup(id, params.killfeed, video.width, video.height)
        val k = setup.k
        fun px(v: Int) = (v * k).roundToInt().coerceAtLeast(1)
        val factor = k * params.killfeed.referenceHeight / params.templateHeight
        val template = PreparedTemplate(GrayImage.load(ctx.configDir.resolve(params.template)).resized(factor), params.template)
        val icon = HeadshotIcon(template, px(params.lineHeight) / 2, px(params.searchWidth), px(params.minVictim), px(params.killfeed.matchTolerance))
        val zones = listOf(setup.zone, setup.zone.copy(color = DARKEST), setup.zone.copy(color = RED))
        val done = AtomicInteger()
        val semaphore = Semaphore(PARALLEL_READS)
        val scores = coroutineScope {
            kills.map { kill ->
                async {
                    semaphore.withPermit {
                        val frames = mutableListOf<IconFrame>()
                        val from = (kill - params.before).coerceAtLeast(Duration.ZERO)
                        val times = ctx.frames.extractRange(
                            spec = FrameSpec.fps(params.fps, params.hwaccel),
                            zones = zones,
                            from = from,
                            length = params.before + params.after,
                            label = id,
                            progress = ProgressReporter.NONE,
                            onReset = { frames.clear() },
                            onFrame = { _, z: List<ZoneFrame> ->
                                val rows = setup.reader.rows(z[0]).filter { it.role == KillfeedRole.KILL }
                                frames += IconFrame(rows.map { it.center to icon.score(it, z[1], z[2]) })
                            },
                        )
                        ctx.progress.update(done.incrementAndGet().toDouble() / kills.size, tr("progress.kill", "done" to done.get(), "total" to kills.size))
                        icon.pick(times.take(frames.size), frames, kill)
                    }
                }
            }.awaitAll()
        }
        val events = kills.zip(scores).mapNotNull { (at, s) -> if (s != null && s >= params.threshold) SignalEvent(at, params.kind, s) else null }
        log.info {
            "$id : ${events.size} tir(s) à la tête sur ${kills.size} kill(s) : " +
                kills.zip(scores).joinToString { (at, s) -> Durations.format(at) + " " + (s?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-") }
        }
        return SignalTrack(id, raw, events)
    }

    private companion object {
        /** Canal le plus sombre : l'icône blanche ressort, le fond sarcelle (vert et bleu clairs, rouge bas) reste sombre. */
        const val DARKEST = "min(min(r(X,Y),g(X,Y)),b(X,Y))"

        /** Fond de la victime adverse : rouge franc (230, 85, 85 mesuré), au-dessus du vert et du bleu de 50 au moins. */
        const val RED = "if(gte(r(X,Y),140)*gte(r(X,Y)-max(g(X,Y),b(X,Y)),50),255,0)"

        const val PARALLEL_READS = 3
    }
}

/** Lignes de kill du joueur sur une image : (centre, note de l'icône ou null si la victime n'est pas trouvée). */
internal class IconFrame(val rows: List<Pair<Double, Double?>>)

/**
 * Lecture de l'icône sur une ligne du joueur ; tailles en pixels de la zone lue. [half] : demi-hauteur d'une ligne,
 * [search] : largeur cherchée avant la victime, [minVictim] : largeur minimale du fond rouge, [tolerance] : écart vertical
 * qui fait de deux lignes la même d'une image à l'autre.
 */
internal class HeadshotIcon(
    private val template: PreparedTemplate,
    private val half: Int,
    private val search: Int,
    private val minVictim: Int,
    private val tolerance: Int,
) {
    /** Note de l'icône avant la victime de la ligne [row], ou null si la ligne n'a pas de victime rouge lisible. */
    fun score(row: FeedRow, dark: ZoneFrame, red: ZoneFrame): Double? {
        val w = red.width
        val center = row.center.roundToInt()
        val top = (center - half).coerceAtLeast(0)
        val bottom = (center + half).coerceAtMost(red.height)
        if (bottom - top < template.height) return null
        val start = victimStart(red, top, bottom, row.right + 1) ?: return null
        val left = (start - search).coerceAtLeast(0)
        val y0 = (top - 1).coerceAtLeast(0)
        val y1 = (bottom + 1).coerceAtMost(dark.height)
        if (start - left < template.width) return null
        val roi = GrayImage(start - left, y1 - y0, ByteArray((start - left) * (y1 - y0)) { i ->
            dark.pixels[(y0 + i / (start - left)) * w + left + i % (start - left)]
        })
        return TemplateMatcher.match(roi, template).score
    }

    /** Première colonne d'une suite d'au moins [minVictim] colonnes à moitié rouges entre [top] et [bottom]. */
    internal fun victimStart(red: ZoneFrame, top: Int, bottom: Int, from: Int): Int? {
        var run = 0
        for (x in from.coerceAtLeast(0) until red.width) {
            var lit = 0
            for (y in top until bottom) if ((red.pixels[y * red.width + x].toInt() and 0xFF) > 127) lit++
            run = if (lit * 2 >= bottom - top) run + 1 else 0
            if (run >= minVictim) return x - minVictim + 1
        }
        return null
    }

    /**
     * Note de la ligne du kill [kill] : les lignes déjà là sur la première image sont anciennes ; parmi les nouvelles
     * (suivies d'image en image à [tolerance] près), celle apparue le plus près du kill, à sa meilleure image (la ligne
     * glisse puis se pose). Deux kills à 0,4 s d'écart ont ainsi chacun leur ligne. Sans nouvelle ligne, rien : une
     * ancienne ligne à la tête ferait passer le kill pour un tir à la tête.
     */
    fun pick(times: List<Duration>, frames: List<IconFrame>, kill: Duration): Double? {
        if (frames.isEmpty()) return null
        val old = frames.first().rows.map { it.first }
        class Track(val first: Duration, var center: Double, var best: Double?)
        val tracks = mutableListOf<Track>()
        for (i in 1 until frames.size) {
            for ((center, score) in frames[i].rows) {
                if (old.any { abs(it - center) <= tolerance }) continue
                val t = tracks.firstOrNull { abs(it.center - center) <= tolerance }
                    ?: Track(times[i], center, null).also { tracks += it }
                t.center = center
                if (score != null && (t.best == null || score > t.best!!)) t.best = score
            }
        }
        val scored = tracks.filter { it.best != null }
        return scored.minByOrNull { (it.first - kill - SHOWN).absoluteValue }?.best
    }

    private companion object {
        /** La ligne s'affiche environ 0,15 s après l'instant du kill (décalage du détecteur killfeed). */
        val SHOWN = 150.milliseconds
    }
}

class KillfeedHeadshotDetectorFactory : SignalDetectorFactory {
    override val type = "killfeed-headshot"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        KillfeedHeadshotDetector(id, params.decode(KillfeedHeadshotParams.serializer()) {
            throw ConfigException(Message("error.detector.paramRequired", "id" to id, "params" to "killfeed, template"))
        })
}
