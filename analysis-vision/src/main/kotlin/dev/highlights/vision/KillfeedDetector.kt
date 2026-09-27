package dev.highlights.vision

import dev.highlights.core.ConfigException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.ffmpeg.Hwaccel
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Réglages du killfeed. Les tailles sont en pixels de la capture de référence ([referenceHeight]) ; les valeurs par
 * défaut sont celles de VALORANT, mesurées sur quatre parties : trois en 3440x1440 comparées aux événements d'Outplayed
 * et une en 1920x1080 d'un autre joueur, avec un autre agent, relevée à l'image. 79 kills sur 79, 58 morts sur 58,
 * aucun faux positif.
 */
@Serializable
data class KillfeedParams(
    /** Zone du killfeed (normalisée), assez large pour la ligne la plus longue et assez haute pour toutes les lignes. */
    val region: CropRegion,
    /** Hauteur de la capture sur laquelle la zone et les tailles en pixels ont été mesurées. */
    val referenceHeight: Int = 1440,
    /** Largeur de cette même capture : la zone est alors convertie au format de la capture analysée ([ScreenGeometry]). */
    val referenceWidth: Int? = null,
    /** Bord de l'écran auquel le killfeed est accroché. */
    val anchor: RegionAnchor = RegionAnchor.RIGHT,
    val fps: Double = 5.0,
    /** Décodage matériel (toutes les images sont décodées) ; repli automatique sur le logiciel. */
    val hwaccel: String? = Hwaccel.AUTO,
    /**
     * Pixels lus par pixel de référence (0,5 = 4 fois moins de pixels qu'en 1440p). Même finesse quelle que soit la
     * définition de la capture : lue plus réduite, une capture 1080p perdait le contour fin du cadre.
     */
    val scale: Double = 0.5,
    /**
     * Couleur qui marque le joueur, en expression `geq` de FFmpeg (0..255 par pixel). Par défaut le jaune de VALORANT :
     * rouge et vert clairs, bleu nettement plus bas ; les fonds verts, rouges et blancs du killfeed restent à 0.
     */
    val color: String = YELLOW,
    /** Valeur de [color] à partir de laquelle un pixel est « du joueur ». */
    val threshold: Int = 60,
    /**
     * Cadre jaune du portrait du joueur : il a la hauteur d'une ligne du killfeed et un trait en haut. Selon l'agent, le
     * visage en cache plus ou moins le fond, et le contour passe du jaune au vert (sa partie verte n'est pas lue) : seul
     * un cadre de la bonne hauteur est sûr, sa largeur et son remplissage varient. Mesuré : 36 à 46 px de haut, 52 à 90
     * de large (jusqu'à 200 quand le contour suit la ligne), 19 à 49 % coloré. Le décor jaune (murs au soleil, flashs),
     * les icônes du jeu et le graphe « Erreur de tir » tombent hors de ces bornes.
     */
    val minHeight: Int = 34,
    val maxHeight: Int = 52,
    val minWidth: Int = 60,
    val maxWidth: Int = 400,
    val minFill: Double = 0.15,
    val maxFill: Double = 0.6,
    /**
     * Cadre plus étroit que [minWidth] (le dégradé efface sa partie droite) : accepté jusqu'à cette largeur s'il est
     * rempli à [narrowFill] au moins, le fond du portrait restant visible. Une icône de ping, aussi étroite, est vide.
     */
    val narrowWidth: Int = 40,
    val narrowFill: Double = 0.3,
    /**
     * Part des colonnes du cadre colorées dans ses [topRows] premières lignes de pixels : le trait du haut, qui tombe
     * parfois à cheval sur deux lignes une fois réduit. Mesuré : 35 à 97 % sur les vraies lignes.
     */
    val minTopEdge: Double = 0.4,
    val topRows: Int = 2,
    /**
     * Pixels lus de part et d'autre d'un trou comblés en largeur avant de chercher les cadres : le contour fait à peine
     * un pixel et se coupe par endroits (trous de 4 px mesurés en 1080p).
     */
    val bridge: Int = 2,
    /**
     * Largeur de la colonne du portrait de la victime, collée au bord droit : un cadre centré là, c'est le joueur qui
     * meurt ; plus à gauche, c'est lui qui tue.
     */
    val victimWidth: Int = 120,
    /**
     * Distance maximale entre le cadre d'une mort et le bord droit de la zone : les lignes y sont alignées (20 px
     * mesurés), une icône qui traverse la colonne s'arrête plus tôt.
     */
    val deathEdge: Int = 40,
    /**
     * Part de la colonne de la victime colorée sur la hauteur d'un cadre de kill au-delà de laquelle le joueur est aussi
     * la victime : ligne « joueur → joueur » (ultime de Clove qui expire), une mort, pas un kill.
     */
    val selfShare: Double = 0.1,
    /** Écart vertical toléré pour reconnaître la même ligne d'une image à l'autre (elle ne fait que monter). */
    val matchTolerance: Int = 12,
    /** Nombre d'images où une ligne doit être vue : le décor qui passe un instant pour une ligne est écarté. */
    val minSightings: Int = 3,
    /**
     * Une ligne invisible plus longtemps est oubliée ; revue ensuite, elle compterait deux fois. Une fumée ou un flash
     * la masque parfois près de 3 s ; une nouvelle ligne n'est pas confondue avec elle, elle apparaît en dessous.
     */
    val maxGap: SerialDuration = 3.seconds,
    /**
     * Écart minimal entre deux morts : une par round. La ligne de la mort est toujours au même endroit (portrait du
     * joueur au bord droit) ; masquée un instant par l'écran de mort, elle serait sinon comptée deux fois.
     */
    val deathSpacing: SerialDuration = 10.seconds,
    /** Type d'événement émis par sorte de ligne ; une sorte absente n'émet rien. */
    val kinds: Map<KillfeedRole, String> = mapOf(KillfeedRole.KILL to "kill", KillfeedRole.DEATH to "death"),
    /** Décalage ajouté à l'instant où la ligne est vue pour la première fois. */
    val offset: SerialDuration = Duration.ZERO,
) {
    init {
        require(fps > 0) { "fps doit être > 0" }
        require(scale > 0 && scale <= 1) { "scale doit être dans ]0, 1]" }
        require(threshold in 1..255) { "threshold doit être entre 1 et 255" }
        require(minHeight in 1..maxHeight && minWidth in 1..maxWidth) { "tailles du cadre : 1 ≤ min ≤ max" }
        require(minFill in 0.0..maxFill && maxFill <= 1.0) { "remplissage : 0 ≤ minFill ≤ maxFill ≤ 1" }
        require(minTopEdge in 0.0..1.0 && topRows >= 1) { "minTopEdge entre 0 et 1, topRows ≥ 1" }
        require(narrowWidth in 1..minWidth && narrowFill in 0.0..1.0) { "narrowWidth ≤ minWidth, narrowFill entre 0 et 1" }
        require(bridge >= 0) { "bridge doit être ≥ 0" }
        require(minSightings >= 1) { "minSightings doit être ≥ 1" }
    }

    companion object {
        const val YELLOW = "if(gte(min(r(X,Y),g(X,Y)),150),min(r(X,Y),g(X,Y))-b(X,Y),0)"
    }
}

@Serializable
enum class KillfeedRole {
    /** Le joueur est le tueur : son portrait à gauche de la ligne. */
    @SerialName("kill") KILL,

    /** Le joueur est la victime : son portrait au bord droit. */
    @SerialName("death") DEATH,
}

/** Cadre du joueur sur une image : lignes de pixels [top, bottom[ et colonnes [left, right]. */
internal data class FeedRow(val role: KillfeedRole, val top: Int, val bottom: Int, val left: Int, val right: Int) {
    val center: Double get() = (top + bottom) / 2.0
}

/** Bornes d'un cadre, en pixels de la zone lue. */
internal data class FrameShape(
    val minHeight: Int,
    val maxHeight: Int,
    val minWidth: Int,
    val maxWidth: Int,
    val minFill: Double,
    val maxFill: Double,
    val narrowWidth: Int = minWidth,
    val narrowFill: Double = 1.0,
    val minTopEdge: Double,
    val topRows: Int = 1,
    /** Distance maximale entre une mort et le bord droit de la zone. */
    val deathEdge: Int = Int.MAX_VALUE,
    /** Part de la colonne de la victime colorée qui fait d'un kill une ligne « joueur → joueur ». */
    val selfShare: Double = 1.0,
)

/**
 * Lecture d'une image du killfeed, déjà réduite à la couleur du joueur. Les lignes sont collées au bord droit ; celles
 * du joueur ont le portrait dans un cadre coloré : à gauche quand il tue, dans la colonne de la victime ([victimWidth]
 * au bord droit) quand il meurt. Chaque tache colorée (8-connexité, trous de [bridge] pixels comblés en largeur) qui a
 * la forme du cadre en est un.
 */
internal class FeedReader(
    private val threshold: Int,
    private val victimWidth: Int,
    private val bridge: Int,
    private val shape: FrameShape,
) {
    private companion object {
        /** Tache collée au bord gauche : du décor coupé par la zone, les lignes y tiennent en entier. */
        const val EDGE = 4
    }

    fun rows(frame: ZoneFrame): List<FeedRow> {
        val w = frame.width
        val h = frame.height
        val lit = BooleanArray(w * h) { (frame.pixels[it].toInt() and 0xFF) >= threshold }
        // Trous d'un trait comblés en largeur, pour le regroupement seulement : les mesures portent sur les vrais pixels.
        val joined = if (bridge == 0) lit else BooleanArray(w * h) { i ->
            val x = i % w
            val row = i - x
            (maxOf(0, x - bridge)..minOf(w - 1, x + bridge)).any { lit[row + it] }
        }
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        val found = mutableListOf<FeedRow>()
        var next = 0
        for (start in 0 until w * h) {
            if (!joined[start] || label[start] != 0) continue
            next++
            var top = h
            var bottom = -1
            var left = w
            var right = -1
            var size = 0
            label[start] = next
            stack[size++] = start
            while (size > 0) {
                val p = stack[--size]
                val px = p % w
                val py = p / w
                // Boîte des vrais pixels : le comblement ne doit pas élargir le cadre.
                if (lit[p]) {
                    if (py < top) top = py
                    if (py > bottom) bottom = py
                    if (px < left) left = px
                    if (px > right) right = px
                }
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx
                    val ny = py + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val q = ny * w + nx
                    if (joined[q] && label[q] == 0) {
                        label[q] = next
                        stack[size++] = q
                    }
                }
            }
            frameOf(lit, label, next, w, top, bottom + 1, left, right)?.let { found += it }
        }
        return merge(found)
    }

    private fun frameOf(lit: BooleanArray, label: IntArray, id: Int, w: Int, top: Int, bottom: Int, left: Int, right: Int): FeedRow? {
        val height = bottom - top
        val width = right - left + 1
        if (height !in shape.minHeight..shape.maxHeight || width !in shape.narrowWidth..shape.maxWidth) return null
        if (left <= EDGE) return null
        fun on(x: Int, y: Int) = lit[y * w + x] && label[y * w + x] == id
        val edgeRows = top until minOf(bottom, top + shape.topRows)
        val topEdge = (left..right).count { x -> edgeRows.any { on(x, it) } }.toDouble() / width
        if (topEdge < shape.minTopEdge) return null
        var count = 0
        for (y in top until bottom) for (x in left..right) if (on(x, y)) count++
        val fill = count.toDouble() / (height * width)
        if (fill !in shape.minFill..shape.maxFill) return null
        if (width < shape.minWidth && fill < shape.narrowFill) return null
        val split = maxOf(0, w - victimWidth)
        if ((left + right) / 2.0 >= split) {
            return if (right >= w - 1 - shape.deathEdge) FeedRow(KillfeedRole.DEATH, top, bottom, left, right) else null
        }
        // Le joueur aussi victime : sa couleur dans la colonne de la victime, sur la même ligne.
        var self = 0
        for (y in top until bottom) for (x in split until w) if (lit[y * w + x]) self++
        if (self >= shape.selfShare * height * (w - split)) return null
        return FeedRow(KillfeedRole.KILL, top, bottom, left, right)
    }

    /** Morceaux d'un même cadre (contour coupé par le visage) : même ligne, côte à côte. */
    private fun merge(rows: List<FeedRow>): List<FeedRow> {
        val out = mutableListOf<FeedRow>()
        for (row in rows.sortedWith(compareBy({ it.role }, { it.center }, { it.left }))) {
            val last = out.lastOrNull()
            if (last != null && last.role == row.role && abs(last.center - row.center) <= 3 && row.left <= last.right + 2) {
                out[out.lastIndex] = last.copy(right = maxOf(last.right, row.right))
            } else {
                out += row
            }
        }
        return out
    }
}

/**
 * Suivi des lignes d'une image à l'autre : une ligne garde sa place en largeur (le killfeed est aligné à droite) et
 * ne fait que monter quand les plus anciennes disparaissent. Une ligne qu'aucune ligne suivie n'explique est nouvelle :
 * un événement, daté de sa première apparition.
 */
internal class FeedTracker(
    private val tolerance: Int,
    private val minSightings: Int,
    private val maxGap: Duration,
    private val deathSpacing: Duration,
) {
    private class Track(val role: KillfeedRole, var left: Int, var right: Int, var center: Double, val first: Duration, var last: Duration, var sightings: Int)

    private val tracks = mutableListOf<Track>()

    fun add(at: Duration, rows: List<FeedRow>) {
        val used = HashSet<Track>()
        // De haut en bas : une ligne qui monte prend la place d'une plus ancienne, la plus haute est servie la première.
        for (row in rows.sortedBy { it.center }) {
            // Même ligne : colonnes communes, et le bord gauche le plus proche. Le bord droit varie d'une image à l'autre
            // (le contour suit plus ou moins la ligne), le gauche à 2 px près ; deux lignes voisines peuvent se recouvrir.
            val match = tracks.asSequence()
                .filter { it !in used && it.role == row.role && at - it.last <= maxGap }
                .filter { minOf(it.right, row.right) >= maxOf(it.left, row.left) && row.center <= it.center + tolerance }
                .minByOrNull { abs(it.left - row.left) }
            if (match == null) {
                tracks += Track(row.role, row.left, row.right, row.center, at, at, 1).also { used += it }
            } else {
                match.left = row.left
                match.right = row.right
                match.center = row.center
                match.last = at
                match.sightings++
                used += match
            }
        }
    }

    /** Lignes vues assez souvent, dans l'ordre : (sorte, instant d'apparition). */
    fun appearances(): List<Pair<KillfeedRole, Duration>> {
        val seen = tracks.filter { it.sightings >= minSightings }.sortedBy { it.first }
        var lastDeath: Duration? = null
        return seen.mapNotNull { t ->
            if (t.role == KillfeedRole.DEATH) {
                val previous = lastDeath
                if (previous != null && t.first - previous < deathSpacing) return@mapNotNull null
                lastDeath = t.first
            }
            t.role to t.first
        }
    }
}

/**
 * Kills et morts du joueur lus dans le killfeed, sans Outplayed ni modèle d'image : ses lignes y sont marquées de sa
 * couleur (jaune dans VALORANT), quels que soient l'agent, l'arme ou le skin. Chaque nouvelle ligne du joueur est un
 * événement, daté de son apparition.
 */
class KillfeedDetector(override val id: String, private val params: KillfeedParams) : SignalDetector {

    private class Prepared(val subscription: FrameSampler.Subscription, val rows: MutableList<List<FeedRow>>, val tolerance: Int)

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
        // Même finesse pour toutes les définitions : scale pixels lus par pixel de référence, sans jamais agrandir.
        val reduction = (params.scale * params.referenceHeight / video.height).coerceAtMost(1.0)
        val zone = FrameZone(cx, cy, cw, ch, (cw * reduction).roundToInt().coerceAtLeast(1), (ch * reduction).roundToInt().coerceAtLeast(1), params.color)
        // Pixels de référence → pixels de la zone lue.
        val k = zone.height.toDouble() / ch * video.height / params.referenceHeight
        fun px(v: Int) = (v * k).roundToInt().coerceAtLeast(1)
        if (zone.height < px(params.maxHeight) || zone.width < px(params.victimWidth + params.minWidth)) {
            throw ConfigException("$id : zone du killfeed trop petite (${zone.width}x${zone.height} après réduction)")
        }
        val shape = FrameShape(
            px(params.minHeight), px(params.maxHeight), px(params.minWidth), px(params.maxWidth),
            params.minFill, params.maxFill, px(params.narrowWidth), params.narrowFill,
            params.minTopEdge, params.topRows, px(params.deathEdge), params.selfShare,
        )
        val reader = FeedReader(params.threshold, px(params.victimWidth), params.bridge, shape)
        val rows = mutableListOf<List<FeedRow>>()
        log.info { "$id : killfeed ${cw}x$ch à ($cx, $cy), lu en ${zone.width}x${zone.height}, ${params.fps} img/s" }
        val subscription = ctx.frames.subscribe(
            spec = FrameSpec.fps(params.fps, params.hwaccel),
            zones = listOf(zone),
            label = id,
            progress = ctx.progress,
            onReset = { rows.clear() },
            onFrame = { _, zones -> rows += reader.rows(zones[0]) },
        )
        prepared = Prepared(subscription, rows, px(params.matchTolerance))
    }

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        if (ctx.media.video == null) return SignalTrack.missing(id, ctx.grid.count, "pas de flux vidéo")
        // Hors pipeline (tests, usage direct), personne n'a préparé le détecteur : il décode alors pour lui seul.
        if (prepared == null) prepare(ctx)
        val state = prepared ?: return SignalTrack.missing(id, ctx.grid.count, "pas de flux vidéo")
        val times = state.subscription.await()
        val count = minOf(times.size, state.rows.size)
        if (count == 0) return SignalTrack.missing(id, ctx.grid.count, "aucune image analysée")

        val tracker = FeedTracker(state.tolerance, params.minSightings, params.maxGap, params.deathSpacing)
        for (i in 0 until count) tracker.add(times[i], state.rows[i])
        val end = ctx.media.duration
        val events = tracker.appearances().mapNotNull { (role, at) ->
            val kind = params.kinds[role] ?: return@mapNotNull null
            SignalEvent((at + params.offset).coerceIn(Duration.ZERO, end), kind, 1.0)
        }
        log.info { "$id : ${events.groupingBy { it.kind }.eachCount()} : " + events.joinToString { "${it.kind} ${Durations.format(it.at)}" } }
        return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, events)
    }
}

class KillfeedDetectorFactory : SignalDetectorFactory {
    override val type = "killfeed"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        KillfeedDetector(id, params.decode(KillfeedParams.serializer()) { throw ConfigException("$id : paramètre region requis") })
}
