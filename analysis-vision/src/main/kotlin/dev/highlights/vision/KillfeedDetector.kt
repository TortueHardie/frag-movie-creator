package dev.highlights.vision

import dev.highlights.core.i18n.tr
import dev.highlights.core.i18n.Message
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
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.video.FrameSampler
import dev.highlights.core.video.FrameSpec
import dev.highlights.core.video.FrameZone
import dev.highlights.core.video.ZoneFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

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
     * Clips Outplayed 2580x1080 (22/11) : contour jaune pâle, ses traits du haut et du bas à moitié perdus une fois la
     * zone réduite : 30 à 32 px de haut et jusqu'à 13 % coloré quand il suit la ligne. Bornes baissées de 34 à 30 px et
     * de 15 à 12 % : kills retrouvés 2 → 4 sur 5 dans le clip de l'ACE, sans faux.
     */
    val minHeight: Int = 30,
    val maxHeight: Int = 52,
    val minWidth: Int = 60,
    val maxWidth: Int = 400,
    val minFill: Double = 0.12,
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
    /**
     * Durée d'affichage maximale d'une ligne du killfeed : environ 4,9 s mesurés (4 lignes d'un clip 2580x1080, de
     * l'apparition à l'effacement ; « 5 s » en 480p). Une ligne suivie apparue il y a plus longtemps ne peut plus être
     * celle qu'on voit : ce qui est là est une nouvelle ligne. Sans cela, le kill suivant, affiché à l'endroit exact d'une
     * ligne effacée 0,7 s plus tôt, passait pour elle (revue après une fumée, [maxGap]) et n'était pas compté (ACE du
     * 10/01, cinquième kill). null = aucune limite (comportement d'avant).
     */
    val lineLife: SerialDuration? = 5300.milliseconds,
    /** Type d'événement émis par sorte de ligne ; une sorte absente n'émet rien. */
    val kinds: Map<KillfeedRole, String> = mapOf(KillfeedRole.KILL to "kill", KillfeedRole.DEATH to "death", KillfeedRole.ASSIST to "assist"),
    /** Décalage ajouté à l'instant où la ligne est vue pour la première fois. */
    val offset: SerialDuration = Duration.ZERO,
    /**
     * Lecture en deux temps quand les images clés de la capture sont espacées de cette durée au plus : elles seules sont
     * d'abord lues (sans décoder les autres images, une vingtaine de fois plus rapide), puis seuls les intervalles où
     * une nouvelle ligne du joueur apparaît sont décodés à [fps], de l'image clé qui la précède à une seconde après celle
     * qui la montre. Une ligne reste plusieurs secondes : elle tombe toujours sur une image clé. Au-delà, ou à null,
     * toute la vidéo est décodée à [fps].
     */
    val keyframeScan: SerialDuration? = 2.seconds,
    /**
     * Captures moins hautes que ça : le contour du cadre (3 px en 1440p) y fait moins d'un pixel et demi, et la couleur
     * sous-échantillonnée (4:2:0) puis la compression le délavent jusqu'à la teinte du décor. Mesuré sur deux parties
     * en 854x480 (vidéos YouTube) : contour à 25-50 de jaune pour un seuil de 60, comme le sable des murs ; 2 kills lus
     * sur des dizaines. On y lit alors l'aplat jaune pur derrière le portrait du joueur ([fillColor]), qui reste net.
     */
    val fillBelow: Int = 720,
    /** Jaune pur de l'aplat : rouge et vert clairs et égaux, bleu bas (le sable et les murs au soleil tirent vers le rouge). */
    val fillColor: String = PURE_YELLOW,
    /** Aplat du portrait, en pixels de référence : 30 px de haut et 24 de large en 480p mesurés, ligne de 48. */
    val fillMinHeight: Int = 19,
    val fillMaxHeight: Int = 53,
    val fillMinWidth: Int = 12,
    val fillMaxWidth: Int = 77,
    /** Remplissage minimal de l'aplat : c'est une surface pleine (80 % mesurés), pas un contour. */
    val fillMin: Double = 0.45,
    /**
     * Durée pendant laquelle une ligne lue par son aplat doit être vue (au lieu de [minSightings]) : une ligne reste
     * 5 s, le décor jaune passe plus vite (bordure dorée d'une capacité : 0,6 à 1,6 s). Mesuré en 480p : 44 fausses
     * morts sur une partie avec 3 images, 21 vraies avec 2 s.
     */
    val fillMinSeen: SerialDuration = 2.seconds,
    /** Basse définition : une ligne pas encore confirmée qu'on ne revoit pas pendant ce temps est oubliée (voir [FeedTracker]). */
    val fillPendingGap: SerialDuration = 500.milliseconds,
    /**
     * Joueur marqué par sa ligne entière, encadrée quand il tue et sur fond plein quand il meurt (Counter-Strike 2), au
     * lieu du cadre de son portrait : voir [OutlineParams]. Les réglages du cadre (minHeight… victimWidth, fill*) ne
     * servent plus, [fillBelow] non plus. null = cadre du portrait (VALORANT).
     */
    val outline: OutlineParams? = null,
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
        require(fillMinHeight in 1..fillMaxHeight && fillMinWidth in 1..fillMaxWidth) { "tailles de l'aplat : 1 ≤ min ≤ max" }
        require(fillMin in 0.0..1.0) { "fillMin entre 0 et 1" }
    }

    companion object {
        const val YELLOW = "if(gte(min(r(X,Y),g(X,Y)),150),min(r(X,Y),g(X,Y))-b(X,Y),0)"

        /** [YELLOW] restreint au jaune pur : |rouge − vert| ≤ 30 (aplat 233/236/128, sable 180/135/97). */
        const val PURE_YELLOW = "if(gte(min(r(X,Y),g(X,Y)),170)*lte(abs(r(X,Y)-g(X,Y)),30),min(r(X,Y),g(X,Y))-b(X,Y),0)"
    }
}

@Serializable
enum class KillfeedRole {
    /** Le joueur est le tueur : son portrait à gauche de la ligne. */
    @SerialName("kill") KILL,

    /** Le joueur est la victime : son portrait au bord droit. */
    @SerialName("death") DEATH,

    /** Le joueur a seulement aidé : ligne encadrée qui ne commence pas par son pseudo (CS2, [PlayerParams]). */
    @SerialName("assist") ASSIST,
}

/** Cadre du joueur sur une image : lignes de pixels [top, bottom[ et colonnes [left, right]. */
internal data class FeedRow(
    val role: KillfeedRole,
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
    /** Bord gauche de la ligne pour lire son texte (lignes encadrées : le plus à gauche des deux traits), [left] sinon. */
    val edge: Int = left,
) {
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
    /**
     * Basse définition : la tache est l'aplat plein derrière le portrait, pas le contour (ni trait du haut ni règle des
     * cadres étroits ; [minFill] seul, sans plafond). Une tache collée au bord droit de la zone (donc de l'écran) est du
     * décor : les lignes s'arrêtent avant (6 px en 480p).
     */
    val solid: Boolean = false,
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
) : RowReader {
    private companion object {
        /** Tache collée au bord gauche : du décor coupé par la zone, les lignes y tiennent en entier. */
        const val EDGE = 4

        /** Aplat ([FrameShape.solid]) à moins de ça du bord droit : du décor (bordure dorée d'une capacité). */
        const val SOLID_EDGE = 2
    }

    override fun rows(frame: ZoneFrame): List<FeedRow> {
        val w = frame.width
        val h = frame.height
        val pixels = frame.pixels
        // La plupart des images n'ont aucun pixel de la couleur du joueur : rien à allouer ni à parcourir.
        var any = false
        for (i in 0 until w * h) {
            if ((pixels[i].toInt() and 0xFF) >= threshold) {
                any = true
                break
            }
        }
        if (!any) return emptyList()
        val lit = BooleanArray(w * h) { (pixels[it].toInt() and 0xFF) >= threshold }
        // Trous d'un trait comblés en largeur, pour le regroupement seulement : les mesures portent sur les vrais pixels.
        val joined = if (bridge == 0) lit else bridged(lit, w, h)
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

    /** Pixel allumé s'il y en a un à [bridge] pixels au plus sur sa ligne : fenêtre glissante, un passage par ligne. */
    private fun bridged(lit: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var inside = 0
            for (x in 0 until minOf(w, bridge)) if (lit[row + x]) inside++
            for (x in 0 until w) {
                val enter = x + bridge
                if (enter < w && lit[row + enter]) inside++
                val leave = x - bridge - 1
                if (leave >= 0 && lit[row + leave]) inside--
                out[row + x] = inside > 0
            }
        }
        return out
    }

    private fun frameOf(lit: BooleanArray, label: IntArray, id: Int, w: Int, top: Int, bottom: Int, left: Int, right: Int): FeedRow? {
        val height = bottom - top
        val width = right - left + 1
        if (height !in shape.minHeight..shape.maxHeight || width !in shape.narrowWidth..shape.maxWidth) return null
        if (left <= EDGE) return null
        if (shape.solid && right >= w - 1 - SOLID_EDGE) return null
        fun on(x: Int, y: Int) = lit[y * w + x] && label[y * w + x] == id
        if (!shape.solid) {
            val edgeRows = top until minOf(bottom, top + shape.topRows)
            val topEdge = (left..right).count { x -> edgeRows.any { on(x, it) } }.toDouble() / width
            if (topEdge < shape.minTopEdge) return null
        }
        var count = 0
        for (y in top until bottom) for (x in left..right) if (on(x, y)) count++
        val fill = count.toDouble() / (height * width)
        if (fill < shape.minFill || (!shape.solid && fill > shape.maxFill)) return null
        if (!shape.solid && width < shape.minWidth && fill < shape.narrowFill) return null
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
 * Ligne du killfeed forcément effacée : apparue il y a plus de [life] (sa durée d'affichage maximale). Ce qu'on voit à
 * sa place est une autre ligne, pas elle revue après une fumée. Avec la première image lue (pas celle de l'apparition,
 * parfois plus tardive), la règle ne peut que la garder plus longtemps.
 */
internal class LineExpiry(private val life: Duration) {
    fun expired(first: Duration, at: Duration): Boolean = at - first > life
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
    /**
     * Écart maximal pour une ligne pas encore vue [minSightings] fois : une vraie ligne qui arrive est vue en continu.
     * Sans cela, une tache de décor vue un instant restait un suivi ouvert [maxGap] et absorbait la vraie ligne apparue
     * plus haut (une ligne ne fait que monter) : en 480p à 30 img/s, le deuxième kill d'un doublé daté de la tache, ou
     * perdu. Par défaut [maxGap] (pleine définition inchangée).
     */
    private val pendingGap: Duration = maxGap,
    /**
     * Lignes encadrées (CS2) : le bord gauche d'une ligne n'est pas sûr (son trait s'efface par morceaux), toutes les
     * lignes finissent au même bord droit. Une ligne est alors rapprochée du suivi le plus proche en hauteur, et une
     * ligne qui dépasse de ce nombre de pixels à gauche le bord gauche le plus à gauche du suivi en est une autre (voir
     * [OutlineParams.newLineGrowth]). null = rapprochement par le bord gauche (VALORANT).
     */
    private val newLineGrowth: Int? = null,
    /** Ligne expirée jamais reprise (voir [KillfeedParams.lineLife]) ; null = toujours reprise dans [maxGap]. */
    private val expiry: LineExpiry? = null,
) {
    private class Track(
        val role: KillfeedRole,
        var left: Int,
        var right: Int,
        var center: Double,
        val first: Duration,
        var last: Duration,
        var sightings: Int,
        /** Ligne déjà présente avant le passage relu (vue sur une image clé) : suivie, jamais comptée. */
        val known: Boolean = false,
    ) {
        /** Bord gauche le plus à gauche vu pour cette ligne. */
        var minLeft = left
    }

    private val tracks = mutableListOf<Track>()

    /** Lignes vues sur une image clé avant un passage relu : elles y seront reconnues, pas prises pour de nouvelles. */
    fun known(at: Duration, rows: List<FeedRow>, firsts: List<Duration>? = null) {
        rows.forEachIndexed { i, it -> tracks += Track(it.role, it.left, it.right, it.center, firsts?.get(i) ?: at, at, minSightings, known = true) }
    }

    /** Ajoute les lignes d'une image ; renvoie, pour chacune (dans l'ordre de [rows]), le numéro de la ligne suivie. */
    fun add(at: Duration, rows: List<FeedRow>): List<Int> {
        val ids = IntArray(rows.size)
        val used = HashSet<Track>()
        // De haut en bas : une ligne qui monte prend la place d'une plus ancienne, la plus haute est servie la première.
        for (index in rows.indices.sortedBy { rows[it].center }) {
            val row = rows[index]
            // Même ligne : colonnes communes, et le bord gauche le plus proche. Le bord droit varie d'une image à l'autre
            // (le contour suit plus ou moins la ligne), le gauche à 2 px près ; deux lignes voisines peuvent se recouvrir.
            val match = tracks.asSequence()
                .filter { it !in used && it.role == row.role && at - it.last <= (if (it.sightings >= minSightings) maxGap else pendingGap) }
                .filter { minOf(it.right, row.right) >= maxOf(it.left, row.left) && row.center <= it.center + tolerance }
                .filter { expiry == null || !expiry.expired(it.first, at) }
                .filter { newLineGrowth == null || row.left >= it.minLeft - newLineGrowth }
                .minByOrNull { if (newLineGrowth == null) abs(it.left - row.left).toDouble() else abs(it.center - row.center) }
            if (match == null) {
                tracks += Track(row.role, row.left, row.right, row.center, at, at, 1).also { used += it }
                ids[index] = tracks.lastIndex
            } else {
                ids[index] = tracks.indexOf(match)
                match.left = row.left
                match.minLeft = minOf(match.minLeft, row.left)
                match.right = row.right
                match.center = row.center
                match.last = at
                match.sightings++
                used += match
            }
        }
        return ids.toList()
    }

    /** Lignes vues assez souvent, dans l'ordre : (sorte, instant d'apparition). */
    fun appearances(): List<Pair<KillfeedRole, Duration>> = appearanceTracks().map { (role, at, _) -> role to at }

    /** Comme [appearances], avec le numéro de chaque ligne suivie (celui que rend [add]). */
    fun appearanceTracks(): List<Triple<KillfeedRole, Duration, Int>> {
        val seen = tracks.indices.filter { !tracks[it].known && tracks[it].sightings >= minSightings }.sortedBy { tracks[it].first }
        var lastDeath: Duration? = null
        return seen.mapNotNull { i ->
            val t = tracks[i]
            if (t.role == KillfeedRole.DEATH) {
                val previous = lastDeath
                if (previous != null && t.first - previous < deathSpacing) return@mapNotNull null
                lastDeath = t.first
            }
            Triple(t.role, t.first, i)
        }
    }
}

/**
 * Kills et morts du joueur lus dans le killfeed, sans Outplayed ni modèle d'image : ses lignes y sont marquées de sa
 * couleur (jaune dans VALORANT), quels que soient l'agent, l'arme ou le skin. Chaque nouvelle ligne du joueur est un
 * événement, daté de son apparition.
 */
class KillfeedDetector(override val id: String, private val params: KillfeedParams) : SignalDetector {

    private class Prepared(
        val subscription: FrameSampler.Subscription,
        val rows: MutableList<List<FeedRow>>,
        val tolerance: Int,
        val zone: FrameZone,
        val reader: RowReader,
        /** Intervalle des images clés quand [subscription] ne lit qu'elles (lecture en deux temps), sinon null. */
        val keyframes: Duration?,
        /** Part de la progression réservée à la relecture des passages repérés. */
        val review: ProgressReporter,
        /** Images où une ligne doit être vue ([KillfeedParams.minSightings], ou [KillfeedParams.fillMinSeen] en basse définition). */
        val sightings: Int,
        /** Basse définition : lecture de l'aplat du portrait. */
        val solid: Boolean,
        /** Lignes encadrées ([KillfeedParams.outline]) : allongement qui fait d'une ligne suivie une nouvelle ligne. */
        val newLineGrowth: Int?,
        /** Kill ou assistance ([PlayerParams]) : images des lignes de kill par image lue, lignes de récompense du chat. */
        val player: PlayerReading? = null,
    )

    /** Ce qui est lu pour séparer kills et assistances ([PlayerParams]), image par image. */
    private class PlayerReading(
        val params: PlayerParams,
        val name: PlayerName,
        /** Par image lue : pour chaque ligne de l'image (même ordre que ses lignes), son image si c'est un kill. */
        val samples: MutableMap<Int, List<GrayImage?>>,
        /** Par image lue : lignes de récompense visibles dans le chat. */
        val rewards: MutableMap<Int, Int>,
    )

    @Volatile
    private var prepared: Prepared? = null

    override suspend fun prepare(ctx: AnalysisContext) {
        val video = ctx.media.video ?: return
        val setup = feedSetup(id, params, video.width, video.height)
        val (cx, cy, cw, ch) = setup.crop
        val zone = setup.zone
        val reader = setup.reader
        val solid = setup.solid
        val k = setup.k
        val reduction = setup.reduction
        val outline = params.outline
        fun px(v: Int) = (v * k).roundToInt().coerceAtLeast(1)
        val sightings = if (solid) maxOf(params.minSightings, ceil(params.fillMinSeen.toDouble(DurationUnit.SECONDS) * params.fps).toInt()) else params.minSightings
        val rows = mutableListOf<List<FeedRow>>()
        // Basse définition : toute la vidéo est lue. Les passages relus autour des images clés (2,4 s après celle qui
        // montre la ligne) sont trop courts pour [KillfeedParams.fillMinSeen] : sur la partie du 22/09 en 480p, 37 kills
        // sur 46, dont la moitié datés 2 à 3 s trop tard. Décoder 480p entier coûte peu (1 min 30 pour 31 min).
        val keyframes = if (solid) null else params.keyframeScan?.let { max -> ctx.frames.keyframeInterval(id)?.takeIf { it <= max } }
        log.info {
            "$id : killfeed ${cw}x$ch à ($cx, $cy), lu en ${zone.width}x${zone.height}, ${params.fps} img/s" +
                (if (solid) ", basse définition : aplat du portrait, vu $sightings fois" else "") +
                (if (outline != null) ", lignes encadrées" else "") +
                (keyframes?.let { ", repéré sur les images clés (toutes les $it)" } ?: ", toute la vidéo")
        }
        val scanProgress = if (keyframes == null) ctx.progress else ctx.progress.child(tr("progress.keyframes"), SCAN_SHARE)
        // Kill ou assistance : la même zone en canal le plus clair (le texte), et les débuts de ligne du chat.
        val player = outline?.player?.takeIf { keyframes == null }?.let { pp ->
            PlayerReading(pp, PlayerName(pp, k, margin = px(2), inner = px(3)), ConcurrentHashMap(), ConcurrentHashMap())
        }
        val nameZone = player?.let { zone.copy(color = BRIGHTEST) }
        val reward = player?.params?.reward
        val rewardZone = reward?.let { rw ->
            val r = params.referenceWidth?.let { ScreenGeometry.rescale(rw.region, it.toDouble() / params.referenceHeight, video.width.toDouble() / video.height, RegionAnchor.LEFT) } ?: rw.region
            val rx = (r.x * video.width).roundToInt().coerceIn(0, video.width - 1)
            val ry = (r.y * video.height).roundToInt().coerceIn(0, video.height - 1)
            val rw2 = (r.width * video.width).roundToInt().coerceIn(1, video.width - rx)
            val rh = (r.height * video.height).roundToInt().coerceIn(1, video.height - ry)
            FrameZone(rx, ry, rw2, rh, (rw2 * reduction).roundToInt().coerceAtLeast(1), (rh * reduction).roundToInt().coerceAtLeast(1), rw.color)
        }
        val rewardLines = reward?.let { RewardLines(it.threshold, it.minPixels, px(2), px(10)) }
        val subscription = ctx.frames.subscribe(
            spec = keyframes?.let { FrameSpec.keyframes(it) } ?: FrameSpec.fps(params.fps, params.hwaccel),
            zones = listOfNotNull(zone, nameZone, rewardZone),
            label = id,
            progress = scanProgress,
            onReset = {
                rows.clear()
                player?.samples?.clear()
                player?.rewards?.clear()
            },
            onFrame = { index, frames ->
                val found = reader.rows(frames[0])
                rows += found
                if (player != null) runCatching {
                    // Une image sur deux : PlayerParams.samples images couvrent les 5 premières secondes de la ligne. Toutes les
                    // images (2,5 s) donnaient un pseudo appris moins net : 29 kills sur 33 au lieu de 30.
                    if (index % 2 == 0 && found.any { it.role == KillfeedRole.KILL }) {
                        val bright = GrayImage(frames[1].width, frames[1].height, frames[1].pixels)
                        player.samples[index] = found.map { r -> if (r.role == KillfeedRole.KILL) lineImage(bright, r, px(2)) else null }
                    }
                    if (rewardLines != null) player.rewards[index] = rewardLines.count(GrayImage(frames[2].width, frames[2].height, frames[2].pixels))
                }.onFailure { log.warn(it) { "$id : image $index : lignes du joueur illisibles" } }
            },
        )
        val review = if (keyframes == null) ProgressReporter.NONE else ctx.progress.child("killfeed", 1 - SCAN_SHARE)
        prepared = Prepared(subscription, rows, px(params.matchTolerance), zone, reader, keyframes, review, sightings, solid, outline?.let { px(it.newLineGrowth) }, player)
    }

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        if (ctx.media.video == null) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        // Hors pipeline (tests, usage direct), personne n'a préparé le détecteur : il décode alors pour lui seul.
        if (prepared == null) prepare(ctx)
        val state = prepared ?: return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        val times = state.subscription.await()
        val count = minOf(times.size, state.rows.size)
        if (count == 0) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noFrames"))

        val tracker = FeedTracker(
            state.tolerance, state.sightings, params.maxGap, params.deathSpacing, if (state.solid) params.fillPendingGap else params.maxGap,
            newLineGrowth = state.newLineGrowth,
            // Lignes encadrées (CS2) : durée d'affichage non mesurée, règle non appliquée.
            expiry = if (params.outline == null) params.lineLife?.let(::LineExpiry) else null,
        )
        val end = ctx.media.duration
        val trackOf = HashMap<Pair<Int, Int>, Int>()
        if (state.keyframes == null) {
            for (i in 0 until count) tracker.add(times[i], state.rows[i]).forEachIndexed { r, t -> trackOf[i to r] = t }
        } else {
            review(ctx, state, times.take(count), end, tracker)
        }
        val assists = state.player?.let { assists(it, state.rows, tracker, trackOf, times.take(count)) }.orEmpty()
        val events = tracker.appearanceTracks().mapNotNull { (role, at, track) ->
            val kind = params.kinds[if (track in assists) KillfeedRole.ASSIST else role] ?: return@mapNotNull null
            SignalEvent((at + params.offset).coerceIn(Duration.ZERO, end), kind, 1.0)
        }
        log.info { "$id : ${events.groupingBy { it.kind }.eachCount()} : " + events.joinToString { "${it.kind} ${Durations.format(it.at)}" } }
        return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, events)
    }

    /**
     * Lignes de kill qui sont des assistances ([PlayerParams]) : le pseudo du joueur est appris en tête des lignes, puis
     * chaque ligne est un kill si elle commence par lui, ou presque et que le chat montre une récompense. Rend les numéros
     * des lignes suivies à compter comme assistances.
     */
    private fun assists(
        reading: PlayerReading,
        rows: List<List<FeedRow>>,
        tracker: FeedTracker,
        trackOf: Map<Pair<Int, Int>, Int>,
        times: List<Duration>,
    ): Set<Int> {
        val kills = tracker.appearanceTracks().filter { it.first == KillfeedRole.KILL }
        val samples = HashMap<Int, MutableList<LineSample>>()
        for ((index, images) in reading.samples.toSortedMap()) {
            images.forEachIndexed { r, img ->
                val track = trackOf[index to r]
                if (img != null && track != null) {
                    val list = samples.getOrPut(track) { mutableListOf() }
                    if (list.size < reading.params.samples) list += LineSample(rows[index][r].edge, img)
                }
            }
        }
        val lines = kills.mapNotNull { (_, _, t) -> samples[t]?.takeIf { it.isNotEmpty() }?.let { t to it } }
        val name = reading.name.learn(lines.map { it.second }) ?: run {
            log.info { "$id : pseudo du joueur non appris, lignes encadrées toutes comptées en kills" }
            return emptySet()
        }
        val reward = reading.params.reward
        val rewards = if (reward == null) emptyList() else RewardLines.appearances(times, times.indices.map { reading.rewards[it] ?: 0 })
        val out = HashSet<Int>()
        val notes = mutableListOf<String>()
        for ((track, list) in lines) {
            val at = kills.first { it.third == track }.second
            val score = reading.name.score(list, name)
            val paid = reward != null && rewards.any { it >= at - reward.before && it <= at + reward.after }
            val kill = score >= reading.params.minScore || (paid && score >= reading.params.weakScore)
            if (!kill) out += track
            notes += Durations.format(at) + " " + String.format(Locale.ROOT, "%.2f", score) + (if (paid) " chat" else "") + (if (kill) "" else " aide")
        }
        log.info { "$id : pseudo appris (${name.width}x${name.height} px), ${out.size} assistance(s) sur ${lines.size} lignes : ${notes.joinToString()}" }
        return out
    }

    /**
     * Relit à [KillfeedParams.fps] les seuls passages où une image clé montre une nouvelle ligne du joueur, plusieurs à la
     * fois ; le suivi les reçoit ensuite dans l'ordre.
     */
    private suspend fun review(ctx: AnalysisContext, state: Prepared, keyTimes: List<Duration>, end: Duration, tracker: FeedTracker) {
        val interval = state.keyframes ?: return
        val plan = planReview(
            keyTimes, keyTimes.indices.map { state.rows[it] }, end, state.tolerance,
            lookback = params.maxGap + interval,
            tail = ((state.sightings + 2) / params.fps).seconds,
            lineLife = params.lineLife.takeIf { params.outline == null },
        )
        val total = plan.fold(Duration.ZERO) { acc, r -> acc + (r.stop - r.start) }
        log.info { "$id : ${plan.size} passage(s) à relire, ${Durations.format(total)} sur ${Durations.format(end)}" }
        val done = AtomicLong()
        val semaphore = Semaphore(PARALLEL_REVIEWS)
        val frames = coroutineScope {
            plan.map { r ->
                async {
                    semaphore.withPermit {
                        val rows = mutableListOf<List<FeedRow>>()
                        val times = ctx.frames.extractRange(
                            spec = FrameSpec.fps(params.fps, params.hwaccel),
                            zones = listOf(state.zone),
                            from = r.start,
                            length = r.stop - r.start,
                            label = id,
                            progress = ProgressReporter.NONE,
                            onReset = { rows.clear() },
                            onFrame = { _, zones -> rows += state.reader.rows(zones[0]) },
                        )
                        val read = done.addAndGet((r.stop - r.start).inWholeMilliseconds).milliseconds
                        state.review.update(read / total, tr("progress.reviewed", "done" to Durations.format(read), "total" to Durations.format(total)))
                        times.zip(rows)
                    }
                }
            }.awaitAll()
        }
        plan.forEachIndexed { i, r ->
            r.known.forEachIndexed { k, (at, rows) -> tracker.known(at, rows, r.origins.getOrNull(k)) }
            frames[i].forEach { (at, rows) -> tracker.add(at, rows) }
        }
        state.review.complete()
    }

    private companion object {
        /** Canal le plus clair : le texte du killfeed, bleu ou jaune, ressort sur le fond sombre de la ligne. */
        const val BRIGHTEST = "max(max(r(X,Y),g(X,Y)),b(X,Y))"

        /** Part de la progression prise par la lecture des images clés, bien plus rapide que la relecture. */
        const val SCAN_SHARE = 0.3

        /** Passages relus en même temps : chaque lecture est courte, le lancement de FFmpeg et la recherche pèsent. */
        const val PARALLEL_REVIEWS = 3
    }
}

/**
 * Passage relu de près, avec les lignes déjà présentes sur les images clés qui le précèdent (instant, lignes) et, avec
 * une durée de vie des lignes ([planReview] `lineLife`), l'instant où chacune est apparue sur les images clés.
 */
internal data class Review(
    val start: Duration,
    val stop: Duration,
    val known: List<Pair<Duration, List<FeedRow>>>,
    val origins: List<List<Duration>> = emptyList(),
)

/**
 * Passages à relire d'après les images clés ([times], lignes lues [rows]). Une ligne d'une image clé que n'explique
 * aucune ligne des images clés des [lookback] précédentes (même sorte, même bord gauche à [tolerance] près, pas plus bas)
 * est nouvelle : elle est apparue depuis l'image clé précédente. Seul cet intervalle est relu, prolongé de [tail] pour
 * que la ligne y soit vue assez de fois. Une ligne qui reste, ou qui monte, ne coûte plus rien.
 *
 * Les passages qui se recouvrent sont fusionnés. Chacun garde les lignes des images clés de [lookback] avant son
 * début : le suivi les reconnaît au lieu de les compter, même masquées un instant par un flash.
 */
internal fun planReview(
    times: List<Duration>,
    rows: List<List<FeedRow>>,
    end: Duration,
    tolerance: Int,
    lookback: Duration,
    tail: Duration,
    /**
     * Durée d'affichage maximale d'une ligne ([KillfeedParams.lineLife]) : une ligne d'image clé apparue il y a plus
     * longtemps n'explique plus rien, ce qui est à sa place est nouveau (ligne effacée puis remplacée entre deux images
     * clés). Chaque ligne hérite de l'instant d'apparition de celle qui l'explique. null = aucune limite.
     */
    lineLife: Duration? = null,
): List<Review> {
    fun before(k: Int) = (k - 1 downTo 0).takeWhile { times[it] >= times[k] - lookback }
    val origins = ArrayList<List<Duration>>(times.size)
    val out = mutableListOf<Review>()
    for (k in times.indices) {
        val candidates = before(k).flatMap { i -> rows[i].indices.map { i to it } }
            .filter { (i, r) -> lineLife == null || times[k] - origins[i][r] <= lineLife }
        val matches = matches(rows[k], candidates.map { (i, r) -> rows[i][r] }, tolerance)
        origins += rows[k].indices.map { r -> matches[r]?.let { candidates[it] }?.let { (i, j) -> origins[i][j] } ?: times[k] }
        if (rows[k].isEmpty() || matches.all { it != null }) continue
        val start = if (k > 0) times[k - 1] else Duration.ZERO
        val stop = minOf(times[k] + tail, end)
        val last = out.lastOrNull()
        if (last != null && start <= last.stop) {
            out[out.lastIndex] = last.copy(stop = maxOf(last.stop, stop))
        } else {
            val known = if (k == 0) emptyList() else (listOf(k - 1) + before(k - 1)).filter { rows[it].isNotEmpty() }
            val sorted = known.sorted()
            out += Review(start, stop, sorted.map { times[it] to rows[it] }, if (lineLife == null) emptyList() else sorted.map { origins[it] })
        }
    }
    return out
}

/**
 * Pour chaque ligne de [rows] (même ordre), la ligne de [previous] qui l'explique, restée en place ou montée, ou null :
 * nouvelle. Une ligne ancienne n'en explique qu'une.
 */
private fun matches(rows: List<FeedRow>, previous: List<FeedRow>, tolerance: Int): List<Int?> {
    val used = BooleanArray(previous.size)
    val out = arrayOfNulls<Int>(rows.size)
    for (r in rows.indices.sortedBy { rows[it].center }) {
        val row = rows[r]
        val match = previous.indices
            .filter { !used[it] && previous[it].role == row.role && row.center <= previous[it].center + tolerance }
            .filter { abs(previous[it].left - row.left) <= tolerance }
            .minByOrNull { abs(previous[it].left - row.left) }
        if (match != null) used[match] = true
        out[r] = match
    }
    return out.toList()
}

class KillfeedDetectorFactory : SignalDetectorFactory {
    override val type = "killfeed"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        KillfeedDetector(id, params.decode(KillfeedParams.serializer()) { throw ConfigException(Message("error.detector.paramRequired", "id" to id, "params" to "region")) })
}

/** Zone lue, lecteur des lignes et échelle d'un killfeed pour une capture donnée ; partagé avec [KillfeedHeadshotDetector]. */
internal class FeedSetup(
    /** Découpe dans la capture : x, y, largeur, hauteur. */
    val crop: List<Int>,
    val zone: FrameZone,
    val reader: RowReader,
    /** Basse définition : lecture de l'aplat du portrait. */
    val solid: Boolean,
    /** Pixels de la zone lue par pixel de référence. */
    val k: Double,
    val reduction: Double,
)

internal fun feedSetup(id: String, params: KillfeedParams, width: Int, height: Int): FeedSetup {
    val region = params.referenceWidth?.let {
        ScreenGeometry.rescale(params.region, it.toDouble() / params.referenceHeight, width.toDouble() / height, params.anchor)
    } ?: params.region
    val cx = (region.x * width).roundToInt().coerceIn(0, width - 1)
    val cy = (region.y * height).roundToInt().coerceIn(0, height - 1)
    val cw = (region.width * width).roundToInt().coerceIn(1, width - cx)
    val ch = (region.height * height).roundToInt().coerceIn(1, height - cy)
    // Même finesse pour toutes les définitions : scale pixels lus par pixel de référence, sans jamais agrandir.
    val reduction = (params.scale * params.referenceHeight / height).coerceAtMost(1.0)
    // Basse définition : le contour du cadre n'est plus lisible, l'aplat jaune du portrait l'est encore.
    val solid = params.outline == null && height < params.fillBelow
    val zone = FrameZone(
        cx, cy, cw, ch, (cw * reduction).roundToInt().coerceAtLeast(1), (ch * reduction).roundToInt().coerceAtLeast(1),
        if (solid) params.fillColor else params.color,
    )
    // Pixels de référence → pixels de la zone lue.
    val k = zone.height.toDouble() / ch * height / params.referenceHeight
    fun px(v: Int) = (v * k).roundToInt().coerceAtLeast(1)
    val tooSmall = params.outline?.let { zone.height < px(it.maxHeight) || zone.width < px(it.minWidth) }
        ?: (zone.height < px(params.maxHeight) || zone.width < px(params.victimWidth + params.minWidth))
    if (tooSmall) {
        throw ConfigException(Message("error.detector.zoneTooSmall", "id" to id, "size" to "${zone.width}x${zone.height}"))
    }
    val shape = if (solid) {
        FrameShape(
            px(params.fillMinHeight), px(params.fillMaxHeight), px(params.fillMinWidth), px(params.fillMaxWidth),
            params.fillMin, 1.0, minTopEdge = 0.0, deathEdge = px(params.deathEdge), selfShare = params.selfShare, solid = true,
        )
    } else {
        FrameShape(
            px(params.minHeight), px(params.maxHeight), px(params.minWidth), px(params.maxWidth),
            params.minFill, params.maxFill, px(params.narrowWidth), params.narrowFill,
            params.minTopEdge, params.topRows, px(params.deathEdge), params.selfShare,
        )
    }
    // Un aplat est plein : rien à combler entre deux pixels.
    val outline = params.outline
    val reader = if (outline != null) {
        OutlineReader(
            params.threshold, params.bridge,
            outline.copy(
                minHeight = px(outline.minHeight), maxHeight = px(outline.maxHeight), lineHeight = px(outline.lineHeight),
                minWidth = px(outline.minWidth), maxWidth = px(outline.maxWidth), edgeTolerance = px(outline.edgeTolerance),
                minEdgeGap = (outline.minEdgeGap * k).roundToInt(), maxEdgeGap = px(outline.maxEdgeGap),
                newLineGrowth = px(outline.newLineGrowth),
            ),
        )
    } else {
        FeedReader(params.threshold, px(params.victimWidth), if (solid) 0 else params.bridge, shape)
    }
    return FeedSetup(listOf(cx, cy, cw, ch), zone, reader, solid, k, reduction)
}
