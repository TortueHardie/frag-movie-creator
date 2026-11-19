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
import dev.highlights.core.video.FrameSpec
import dev.highlights.core.video.FrameZone
import dev.highlights.core.video.ZoneFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Réglages de la lecture des équipes dans le killfeed ([KillfeedTeamsDetector]). Tailles en pixels de la capture de
 * référence ([referenceHeight]) ; valeurs par défaut mesurées sur deux parties de VALORANT en 854x480 (16:9).
 */
@Serializable
data class KillfeedTeamsParams(
    /** Zone du killfeed (normalisée), la même que celle du détecteur `killfeed`. */
    val region: CropRegion,
    val referenceHeight: Int = 1440,
    val referenceWidth: Int? = null,
    val anchor: RegionAnchor = RegionAnchor.RIGHT,
    /**
     * Images lues par seconde. 5 comme le détecteur `killfeed` : en basse définition, qui le lit en entier à cette
     * cadence, les deux lectures partagent la même passe de décodage. Une ligne reste 5 s : vue 25 fois.
     */
    val fps: Double = 5.0,
    val hwaccel: String? = Hwaccel.AUTO,
    /** Pixels lus par pixel de référence, sans jamais agrandir (480p : lue telle quelle). */
    val scale: Double = 0.5,
    /**
     * Colonne lue pour la couleur de la victime, en pixels de référence depuis le bord droit de la zone : juste à gauche
     * du portrait de la victime, là où le fond de son nom est toujours visible, quelle que soit sa longueur. Mesuré en
     * 480p : portrait à 12-36 px du bord, fond du nom avant lui (dégradé vers le sombre, mais toujours de la teinte).
     */
    val victimFrom: Int = 190,
    val victimTo: Int = 110,
    /** Part des pixels d'une ligne de la colonne qui doivent être de la couleur d'une équipe. */
    val minShare: Double = 0.5,
    /**
     * Pas des lignes du killfeed : deux lignes de la même équipe l'une sous l'autre forment une seule bande de couleur,
     * coupée à ce pas (17,6 px en 480p mesurés : 16 px de couleur, 1,6 d'écart).
     */
    val rowPitch: Double = 52.8,
    /** Écart toléré entre la hauteur d'une bande et un nombre entier de lignes, en part du pas. */
    val pitchTolerance: Double = 0.35,
    /** Au plus autant de lignes empilées : une bande plus haute est du décor (le killfeed en montre six). */
    val maxRows: Int = 6,
    /**
     * Part des colonnes à gauche de la colonne de la victime qui doivent porter la couleur de l'autre équipe (le tueur) :
     * c'est ce qui écarte les panneaux du menu d'achat et le résumé de combat, unis. Mesuré en 480p : 39 fausses morts
     * alliées sur 111 sans cette règle (menu d'achat, résumé de combat), aucune vraie perdue avec 0,08.
     */
    val minKillerShare: Double = 0.08,
    /** Écart vertical toléré pour reconnaître la même ligne d'une image à l'autre (elle ne fait que monter). */
    val matchTolerance: Int = 12,
    /** Durée pendant laquelle une ligne doit être vue : le décor qui en prend la couleur passe plus vite. */
    val minSeen: SerialDuration = 800.milliseconds,
    /** Une ligne confirmée invisible plus longtemps est oubliée (masquée par une fumée, un flash). */
    val maxGap: SerialDuration = 3.seconds,
    /** Une ligne pas encore confirmée qu'on ne revoit pas pendant ce temps est oubliée. */
    val pendingGap: SerialDuration = 500.milliseconds,
    /** Couleur de l'équipe du joueur (sarcelle dans VALORANT) et de l'adversaire (rouge), en expressions `geq`. */
    val allyColor: String = TEAL,
    val enemyColor: String = RED,
    /** Événements émis : mort d'un allié (le joueur compris : le montage le retire), mort d'un adversaire. */
    val allyKind: String = "ally-death",
    val enemyKind: String = "enemy-death",
    /** Décalage ajouté à l'instant d'apparition, le même que celui du détecteur `killfeed` (kills et morts du joueur). */
    val offset: SerialDuration = (-150).milliseconds,
) {
    init {
        require(fps > 0 && scale > 0 && scale <= 1) { "fps > 0 et scale dans ]0, 1]" }
        require(victimFrom > victimTo && victimTo >= 0) { "victimFrom doit dépasser victimTo" }
        require(minShare in 0.0..1.0 && minKillerShare in 0.0..1.0) { "parts entre 0 et 1" }
        require(rowPitch > 0 && pitchTolerance in 0.0..0.5 && maxRows >= 1) { "pas des lignes invalide" }
    }

    companion object {
        /** Sarcelle des alliés : vert et bleu nettement au-dessus du rouge (107/186/165 clair, 58/103/90 sombre en 480p). */
        const val TEAL = "gt(g(X,Y)-r(X,Y),30)*gt(b(X,Y)-r(X,Y),15)*gt(g(X,Y),60)"

        /** Rouge des adversaires (245/90/85 clair, 107/37/28 sombre). */
        const val RED = "gt(r(X,Y)-g(X,Y),40)*gt(r(X,Y)-b(X,Y),30)*gt(r(X,Y),80)"

        /** Un octet par pixel : 255 allié, 128 adversaire, 0 sinon. */
        fun classes(ally: String, enemy: String) = "if($ally,255,if($enemy,128,0))"
    }
}

/** Équipe de la victime d'une ligne du killfeed. */
enum class FeedTeam { ALLY, ENEMY }

/** Ligne du killfeed : équipe de la victime, centre vertical en pixels de la zone lue. */
internal data class TeamRow(val team: FeedTeam, val center: Double)

/**
 * Lecture d'une image du killfeed déjà réduite aux classes de couleur ([KillfeedTeamsParams.classes]). Dans VALORANT,
 * chaque moitié d'une ligne a la couleur de l'équipe de son joueur : le tueur à gauche, la victime à droite (sarcelle
 * pour les alliés, rouge pour l'adversaire). La couleur de la victime est lue dans une colonne étroite juste avant son
 * portrait ([from], [to] : colonnes de la zone) ; une bande de lignes de pixels de la même couleur, haute d'un nombre
 * entier de lignes du killfeed ([pitch]), en contient autant. Une ligne n'est gardée que si le côté du tueur porte la
 * couleur de l'autre équipe ([minKillerShare]) : un panneau uni de l'interface n'en est pas une.
 */
internal class TeamRowReader(
    private val from: Int,
    private val to: Int,
    private val pitch: Double,
    private val minShare: Double = 0.5,
    private val tolerance: Double = 0.35,
    private val maxRows: Int = 6,
    private val minKillerShare: Double = 0.08,
) {
    fun rows(frame: ZoneFrame): List<TeamRow> {
        val w = frame.width
        val h = frame.height
        val x0 = (w - from).coerceIn(0, w)
        val x1 = (w - to).coerceIn(x0, w)
        if (x1 - x0 == 0) return emptyList()
        val px = frame.pixels
        fun team(v: Int) = when {
            v >= 192 -> FeedTeam.ALLY
            v in 64..191 -> FeedTeam.ENEMY
            else -> null
        }
        val label = arrayOfNulls<FeedTeam>(h)
        for (y in 0 until h) {
            var ally = 0
            var enemy = 0
            for (x in x0 until x1) when (team(px[y * w + x].toInt() and 0xFF)) {
                FeedTeam.ALLY -> ally++
                FeedTeam.ENEMY -> enemy++
                null -> {}
            }
            val need = minShare * (x1 - x0)
            label[y] = if (ally >= need) FeedTeam.ALLY else if (enemy >= need) FeedTeam.ENEMY else null
        }
        val out = mutableListOf<TeamRow>()
        var y = 0
        while (y < h) {
            val t = label[y]
            if (t == null) {
                y++
                continue
            }
            val start = y
            while (y < h && label[y] == t) y++
            val height = y - start
            val n = (height / pitch).roundToInt()
            if (n !in 1..maxRows || abs(height - n * pitch) > tolerance * pitch) continue
            for (k in 0 until n) {
                val a = start + height * k / n
                val b = start + height * (k + 1) / n
                if (killerShare(frame, a, b, x0, if (t == FeedTeam.ALLY) FeedTeam.ENEMY else FeedTeam.ALLY, ::team) >= minKillerShare) {
                    out += TeamRow(t, (a + b) / 2.0)
                }
            }
        }
        return out
    }

    /** Part des colonnes à gauche de [x0] dont la moitié au moins des lignes [a, b[ est de la couleur [other]. */
    private fun killerShare(frame: ZoneFrame, a: Int, b: Int, x0: Int, other: FeedTeam, team: (Int) -> FeedTeam?): Double {
        if (x0 == 0 || b <= a) return 0.0
        var columns = 0
        for (x in 0 until x0) {
            var n = 0
            for (y in a until b) if (team(frame.pixels[y * frame.width + x].toInt() and 0xFF) == other) n++
            if (n * 2 >= b - a) columns++
        }
        return columns.toDouble() / x0
    }
}

/**
 * Suivi des lignes d'une image à l'autre (comme [FeedTracker] : une ligne ne fait que monter). Chaque ligne suivie
 * assez longtemps est une mort, datée de sa première apparition.
 */
internal class TeamTracker(
    private val tolerance: Int,
    private val minSeen: Duration,
    private val maxGap: Duration,
    private val pendingGap: Duration,
    private val minSightings: Int = 3,
) {
    private class Track(val team: FeedTeam, var center: Double, val first: Duration, var last: Duration, var sightings: Int)

    private val tracks = mutableListOf<Track>()

    private fun Track.confirmed() = sightings >= minSightings && last - first >= minSeen

    fun add(at: Duration, rows: List<TeamRow>) {
        val used = HashSet<Track>()
        for (row in rows.sortedBy { it.center }) {
            // La plus ancienne ligne suivie qui peut être celle-ci : même équipe, pas plus haut qu'elle (à la tolérance près).
            val match = tracks.firstOrNull {
                it !in used && it.team == row.team && row.center <= it.center + tolerance &&
                    at - it.last <= (if (it.confirmed()) maxGap else pendingGap)
            }
            if (match == null) {
                tracks += Track(row.team, row.center, at, at, 1).also { used += it }
            } else {
                match.center = row.center
                match.last = at
                match.sightings++
                used += match
            }
        }
    }

    /** Morts vues, dans l'ordre : (équipe de la victime, instant d'apparition). */
    fun deaths(): List<Pair<FeedTeam, Duration>> = tracks.filter { it.confirmed() }.sortedBy { it.first }.map { it.team to it.first }
}

/**
 * Morts des alliés et des adversaires lues dans le killfeed, à la couleur de la victime de chaque ligne. C'est ce qui
 * permet de compter les alliés en vie dans un round, et de reconnaître un vrai clutch (le joueur, dernier en vie,
 * gagne le round) sans Outplayed. Les morts du joueur y sont des morts alliées : le montage les retire grâce aux morts
 * du détecteur `killfeed` (ou d'Outplayed).
 */
class KillfeedTeamsDetector(override val id: String, private val params: KillfeedTeamsParams) : SignalDetector {
    private class Prepared(val subscription: FrameSampler.Subscription, val rows: MutableList<List<TeamRow>>, val tolerance: Int)

    @Volatile
    private var prepared: Prepared? = null

    override suspend fun prepare(ctx: AnalysisContext) {
        val video = ctx.media.video ?: return
        val region = params.referenceWidth?.let {
            ScreenGeometry.rescale(params.region, it.toDouble() / params.referenceHeight, video.width.toDouble() / video.height, params.anchor)
        } ?: params.region
        val cx = (region.x * video.width).roundToInt().coerceIn(0, video.width - 1)
        val cy = (region.y * video.height).roundToInt().coerceIn(0, video.height - 1)
        val cw = (region.width * video.width).roundToInt().coerceIn(1, video.width - cx)
        val ch = (region.height * video.height).roundToInt().coerceIn(1, video.height - cy)
        val reduction = (params.scale * params.referenceHeight / video.height).coerceAtMost(1.0)
        val zone = FrameZone(
            cx, cy, cw, ch, (cw * reduction).roundToInt().coerceAtLeast(1), (ch * reduction).roundToInt().coerceAtLeast(1),
            KillfeedTeamsParams.classes(params.allyColor, params.enemyColor),
        )
        // Pixels de référence → pixels de la zone lue.
        val k = zone.height.toDouble() / ch * video.height / params.referenceHeight
        fun px(v: Int) = (v * k).roundToInt().coerceAtLeast(1)
        if (zone.width < px(params.victimFrom) || zone.height < params.rowPitch * k) {
            throw ConfigException(Message("error.detector.zoneTooSmall", "id" to id, "size" to "${zone.width}x${zone.height}"))
        }
        val reader = TeamRowReader(px(params.victimFrom), px(params.victimTo), params.rowPitch * k, params.minShare, params.pitchTolerance, params.maxRows, params.minKillerShare)
        val rows = mutableListOf<List<TeamRow>>()
        log.info { "$id : équipes du killfeed ${cw}x$ch à ($cx, $cy), lu en ${zone.width}x${zone.height}, ${params.fps} img/s" }
        val subscription = ctx.frames.subscribe(
            spec = FrameSpec.fps(params.fps, params.hwaccel),
            zones = listOf(zone),
            label = id,
            progress = ctx.progress,
            onReset = { rows.clear() },
            onFrame = { _, frames -> rows += reader.rows(frames[0]) },
        )
        prepared = Prepared(subscription, rows, px(params.matchTolerance))
    }

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        if (ctx.media.video == null) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        if (prepared == null) prepare(ctx)
        val state = prepared ?: return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        val times = state.subscription.await()
        val count = minOf(times.size, state.rows.size)
        if (count == 0) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noFrames"))
        val tracker = TeamTracker(state.tolerance, params.minSeen, params.maxGap, params.pendingGap)
        for (i in 0 until count) tracker.add(times[i], state.rows[i])
        val end = ctx.media.duration
        val events = tracker.deaths().map { (team, at) ->
            SignalEvent((at + params.offset).coerceIn(Duration.ZERO, end), if (team == FeedTeam.ALLY) params.allyKind else params.enemyKind, 1.0)
        }
        log.info { "$id : ${events.groupingBy { it.kind }.eachCount()} : " + events.joinToString { "${it.kind} ${Durations.format(it.at)}" } }
        return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, events)
    }
}

class KillfeedTeamsDetectorFactory : SignalDetectorFactory {
    override val type = "killfeed-teams"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        KillfeedTeamsDetector(id, params.decode(KillfeedTeamsParams.serializer()) { throw ConfigException(Message("error.detector.paramRequired", "id" to id, "params" to "region")) })
}
