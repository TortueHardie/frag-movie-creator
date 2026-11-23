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
import dev.highlights.core.video.FrameSpec
import dev.highlights.core.video.FrameZone
import dev.highlights.core.video.ZoneFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

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
    /** Images lues par seconde : une ligne reste 5 s, vue 25 fois. */
    val fps: Double = 5.0,
    /**
     * Décodage logiciel par défaut : le décodage matériel (d3d11va) rend les arcs du cercle de Phoenix (1 px en 480p)
     * plus ternes, et 2 de ses 4 morts n'étaient plus reconnues. Coût : une passe à part, 480p décodé en 1 min 30 pour
     * 30 min de partie. « auto » pour le matériel.
     */
    val hwaccel: String? = null,
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
    /**
     * Écart vertical toléré pour reconnaître la même ligne d'une image à l'autre (elle ne fait que monter). 20 px de
     * référence (une ligne en occupe ~52) : pendant un flash, le centre lu d'une ligne remonte de 6 px puis revient
     * (clip 2580x1080 du 10/01) ; avec 12, la ligne revenue paraissait plus bas que son suivi et comptait deux fois.
     * Mesuré en 480p (« Valorant- 44 kills ») : mêmes 6 fausses morts alliées qu'avec 12.
     */
    val matchTolerance: Int = 20,
    /** Durée pendant laquelle une ligne doit être vue : le décor qui en prend la couleur passe plus vite. */
    val minSeen: SerialDuration = 800.milliseconds,
    /** Une ligne confirmée invisible plus longtemps est oubliée (masquée par une fumée, un flash). */
    val maxGap: SerialDuration = 3.seconds,
    /** Une ligne pas encore confirmée qu'on ne revoit pas pendant ce temps est oubliée. */
    val pendingGap: SerialDuration = 500.milliseconds,
    /**
     * Durée d'affichage maximale d'une ligne du killfeed : environ 4,9 s mesurés (4 lignes d'un clip 2580x1080, de
     * l'apparition à l'effacement ; « 5 s » en 480p). Une ligne suivie apparue il y a plus longtemps ne peut plus être
     * celle qu'on voit : ce qui est là est une nouvelle ligne. Sans cela, le kill suivant, affiché à l'endroit exact d'une
     * ligne effacée 0,7 s plus tôt, passait pour elle (revue après une fumée, [maxGap]) et n'était pas compté (ACE du
     * 10/01, cinquième kill). null = aucune limite (comportement d'avant).
     */
    val lineLife: SerialDuration? = 5300.milliseconds,
    /** Couleur de l'équipe du joueur (sarcelle dans VALORANT) et de l'adversaire (rouge), en expressions `geq`. */
    val allyColor: String = TEAL,
    val enemyColor: String = RED,
    /**
     * Condition ajoutée à [enemyColor] pour un rouge « franc » ; null = aucune. Une bande dont la hauteur n'est pas un
     * nombre entier de lignes est relue avec ce rouge seul (voir [TeamRowReader]) : derrière une ligne adverse, un mur
     * orange ou brun pris pour du rouge la prolongeait. La lecture habituelle ne change pas : en 480p, l'écran vert de
     * la mort du joueur teinte les bandes rouges, qui perdaient sinon leurs bords (mesuré : 3 morts adverses sur 97
     * datées 2 s trop tard avec le rouge franc partout).
     */
    val enemyStrict: String? = RED_STRICT,
    /** Événements émis : mort d'un allié (le joueur compris : le montage le retire), mort d'un adversaire. */
    val allyKind: String = "ally-death",
    val enemyKind: String = "enemy-death",
    /**
     * Ligne marquée d'un grand cercle blanc entre le tueur et la victime : Phoenix mort pendant son ultime (« Run it
     * Back »), qui revient aussitôt. Ce n'est pas une mort : émise sous ces sortes à la place de [allyKind] et
     * [enemyKind] (le montage retire aussi la mort du joueur lue au même instant). Vide : comptée comme une mort.
     * Mesuré sur la partie « Valorant- 44 kills » : les 4 morts du joueur marquées ainsi sont celles où il revient à
     * l'image (4:26, 9:30, 15:13, 23:36), aucune autre de ses lignes ne l'est.
     */
    val allyRevivedKind: String = "ally-run-it-back",
    val enemyRevivedKind: String = "enemy-run-it-back",
    /**
     * Le cercle déborde de la ligne en haut et en bas (2 px en 480p, rien d'autre ne dépasse) : arcs blancs cherchés
     * dans cette hauteur au-dessus et au-dessous, en pixels de référence. Vu sur au moins [ringShare] des images d'une
     * ligne et pendant [ringSeen] : 6 à 9 images (12 à 37 %) pour les 4 morts de Phoenix, 4 au plus pour les autres
     * lignes du joueur et des alliés (2 sur 47 : 4 %) (icône de tir à la tête, plus petite, et décor clair qui passe). Le décodage matériel en perdait la
     * moitié : voir [hwaccel].
     */
    val ringMargin: Int = 6,
    /**
     * Lignes de pixels presque toutes blanches ou claires dans la colonne de la victime ([textRows] de référence au plus,
     * texte à [TEXT_SHARE] au moins) prises entre deux lignes de pixels de la même équipe : comptées avec elles. Le trait
     * blanc du cadre de l'incrustation « Erreur de tir » (option d'affichage du jeu, sous le killfeed) traverse la
     * quatrième ligne et la coupait en deux bandes trop courtes (partie 3440x1440 du 22/11 : 2 morts adverses
     * manquées). Sans incrustation, rien ne change : une ligne du killfeed n'a jamais toute sa colonne en texte.
     */
    val textRows: Int = 4,
    val ringShare: Double = 0.12,
    val ringSeen: SerialDuration = 1.seconds,
    /** Décalage ajouté à l'instant d'apparition, le même que celui du détecteur `killfeed` (kills et morts du joueur). */
    val offset: SerialDuration = (-150).milliseconds,
    /**
     * Barres de vie des alliés, en haut de l'écran (sous leurs portraits, à gauche du score), mesurées en 854x480 : à
     * chaque début de round, les cinq reviennent pleines. C'est ce qui sépare les rounds quand un silence ne suffit pas :
     * dans une vidéo montée (phases d'achat coupées), le round suivant commence 10 s après le précédent. Mesuré sur
     * deux parties : 22 débuts trouvés pour 23 rounds au score (13-10), 23 pour 25 (12-13). null : pas de lecture.
     */
    val roundBar: RoundBarParams? = RoundBarParams(),
    /**
     * Bandeau « CLUTCH » de fin de round, lu au centre de l'écran : il attrape les clutchs gagnés au spike (désamorcé,
     * explosé), que le killfeed ne voit pas finir. Son panneau est gris quelle que soit l'équipe (vu pour un coéquipier
     * et pour l'adversaire) : le montage le rattache, comme un clutch annoncé par Outplayed, au groupe du joueur dont le
     * dernier kill le précède sans mort entre les deux. null : pas de lecture.
     */
    val clutchBanner: BannerParams? = BannerParams(),
) {
    init {
        require(fps > 0 && scale > 0 && scale <= 1) { "fps > 0 et scale dans ]0, 1]" }
        require(victimFrom > victimTo && victimTo >= 0) { "victimFrom doit dépasser victimTo" }
        require(minShare in 0.0..1.0 && minKillerShare in 0.0..1.0) { "parts entre 0 et 1" }
        require(rowPitch > 0 && pitchTolerance in 0.0..0.5 && maxRows >= 1) { "pas des lignes invalide" }
        require(ringMargin >= 1 && ringShare in 0.0..1.0) { "ringMargin ≥ 1, ringShare entre 0 et 1" }
    }

    companion object {
        /**
         * Sarcelle des alliés : vert et bleu au-dessus du rouge (107/186/165 clair, 58/103/90 sombre en 480p). La ligne
         * de la mort du joueur est plus terne (98/131/115) : avec 30 et 15 au lieu de 20 et 5, sa bande se coupait.
         */
        const val TEAL = "gt(g(X,Y)-r(X,Y),20)*gt(b(X,Y)-r(X,Y),5)*gt(g(X,Y),60)"

        /**
         * Rouge des adversaires (245/90/85 clair, 107/37/28 sombre). Les murs orange et bruns du décor (Sunset,
         * 126/76/38) en sont aussi : voir [RED_STRICT].
         */
        const val RED = "gt(r(X,Y)-g(X,Y),40)*gt(r(X,Y)-b(X,Y),30)*gt(r(X,Y),80)"

        /**
         * Rouge franc ([enemyStrict]) : bleu à peu près égal au vert. Partie 3440x1440 du 22/11 : derrière
         * une ligne adverse, la bande rouge se fondait dans le mur et n'avait plus la hauteur d'une ligne (5 morts
         * adverses manquées). Mesuré : bleu − vert de −8 à −20 au milieu des bandes rouges, de −25 à −41 sur les murs,
         * y compris une bande sombre sur un mur orange (162/73/54 : −19). Un mur de sable éclairé (175 de rouge, −46) est
         * aussi exclu : « ou rouge au-dessus de 150 », d'abord ajouté, le laissait passer sous l'incrustation « Erreur de tir ».
         */
        const val RED_STRICT = "gt(b(X,Y)-g(X,Y),-22)"

        /** Blanc (cercle de Phoenix, texte) ; clair : le nom de la victime fondu dans son fond (125/145/144). */
        const val WHITE = "gt(min(min(r(X,Y),g(X,Y)),b(X,Y)),150)"
        const val LIGHT = "gt(min(min(r(X,Y),g(X,Y)),b(X,Y)),110)"

        /**
         * Un octet par pixel : 255 allié, 191 adversaire (rouge franc si [strict] est donné), 175 rouge seulement sans
         * [strict], 127 blanc, 63 clair, 0 sinon (voir [PixelClass]).
         */
        fun classes(ally: String, enemy: String, strict: String? = null): String {
            val red = if (strict == null) "191" else "if($strict,191,175)"
            return "if($ally,255,if($enemy,$red,if($WHITE,127,if($LIGHT,63,0))))"
        }
    }
}

/** Lecture des barres de vie alliées pour dater les débuts de round ([KillfeedTeamsParams.roundBar]). */
@Serializable
data class RoundBarParams(
    val region: CropRegion = CropRegion(0.2108, 0.0688, 0.1932, 0.0083),
    val referenceWidth: Int = 854,
    val referenceHeight: Int = 480,
    val anchor: RegionAnchor = RegionAnchor.CENTER,
    /** Pixel de barre de vie : les trois composantes au-dessus de ce niveau (blanc). */
    val white: Int = 170,
    /** Barres pleines : part des colonnes de la zone où passe une barre (cinq barres : 90 colonnes sur 165 mesurées). */
    val fullShare: Double = 0.48,
    /** Pleines aussi longtemps, après au moins [before] sans l'être : un nouveau round. */
    val held: SerialDuration = 3.seconds,
    val before: SerialDuration = 10.seconds,
    val kind: String = "round-start",
) {
    init {
        require(white in 1..255 && fullShare in 0.0..1.0) { "roundBar : white entre 1 et 255, fullShare entre 0 et 1" }
    }
}

/**
 * Lecture d'un mot de bandeau de fin de round ([KillfeedTeamsParams.clutchBanner]) : les lettres blanches de la zone,
 * comparées au gabarit du mot (corrélation, la meilleure position dans la zone). Mesuré sur deux parties en 854x480 :
 * 0,79 à 1,00 sur les 3 bandeaux « CLUTCH », 0,34 au plus sur tout le reste (WON, LOST, ACE, FLAWLESS, BUY PHASE…).
 */
@Serializable
data class BannerParams(
    /** Gabarit du mot (blanc sur noir, à l'échelle de [referenceHeight]), relatif au dossier de configuration. */
    val template: String = "templates/valorant/banner_clutch.png",
    /** Zone où le chercher : le mot et 8 px de marge (le bandeau glisse en apparaissant), mesurée en 854x480. */
    val region: CropRegion = CropRegion(0.4286, 0.1458, 0.1393, 0.1083),
    val referenceWidth: Int = 854,
    val referenceHeight: Int = 480,
    val anchor: RegionAnchor = RegionAnchor.CENTER,
    /** Lettres : les trois composantes au-dessus de ce niveau. */
    val white: Int = 200,
    val minScore: Double = 0.5,
    /** Vu aussi longtemps (le bandeau reste 4 à 5 s) : un passage blanc de l'image ne suffit pas. */
    val held: SerialDuration = 1.seconds,
    /** Un même bandeau reconnu à nouveau avant ce délai n'en est pas un autre (perdu une seconde sous un éclat : vu). */
    val apart: SerialDuration = 10.seconds,
    val kind: String = "clutch-banner",
) {
    init {
        require(white in 1..255 && minScore in 0.0..1.0) { "clutchBanner : white entre 1 et 255, minScore entre 0 et 1" }
    }
}

/**
 * Apparitions d'un bandeau : début de chaque suite d'images où il est reconnu, longue d'au moins [held], à plus de
 * [apart] de la précédente.
 */
internal fun bannerAppearances(times: List<Duration>, seen: List<Boolean>, held: Duration, apart: Duration = Duration.ZERO): List<Duration> {
    val out = mutableListOf<Duration>()
    var start: Duration? = null
    var counted = false
    for (i in times.indices) {
        if (seen[i]) {
            val first = start ?: times[i].also { start = it }
            if (!counted && times[i] - first >= held) {
                val last = out.lastOrNull()
                if (last == null || first - last > apart) out += first
                counted = true
            }
        } else {
            start = null
            counted = false
        }
    }
    return out
}

/**
 * Débuts de round d'après les barres de vie alliées : pleines pendant [held] après au moins [before] sans l'être. Le
 * début est daté de leur retour.
 */
internal fun roundStarts(times: List<Duration>, full: List<Boolean>, held: Duration, before: Duration): List<Duration> {
    val out = mutableListOf<Duration>()
    var since: Duration? = null
    var lastEmpty: Duration? = null
    var emptyFrom: Duration? = times.firstOrNull()
    for (i in times.indices) {
        val t = times[i]
        if (full[i]) {
            if (since == null) since = t
            val from = emptyFrom
            val last = lastEmpty
            if (t - since >= held && from != null && last != null && last - from >= before) {
                out += since
                emptyFrom = null
            }
        } else {
            since = null
            if (emptyFrom == null) emptyFrom = t
            lastEmpty = t
        }
    }
    return out
}

/** Équipe de la victime d'une ligne du killfeed. */
enum class FeedTeam { ALLY, ENEMY }

/** Ligne du killfeed : équipe de la victime, centre vertical en pixels de la zone lue, cercle de Phoenix vu. */
internal data class TeamRow(val team: FeedTeam, val center: Double, val ring: Boolean = false)

/** Classe d'un pixel de la zone lue (octet de [KillfeedTeamsParams.classes]). */
internal enum class PixelClass {
    /** [DULL] : rouge, mais pas franc ([KillfeedTeamsParams.enemyStrict]) ; compte comme [ENEMY] sauf en relecture. */
    ALLY, ENEMY, DULL, WHITE, LIGHT, OTHER;

    companion object {
        fun of(v: Int) = when {
            v >= 223 -> ALLY
            v >= 183 -> ENEMY
            v >= 159 -> DULL
            v >= 95 -> WHITE
            v >= 32 -> LIGHT
            else -> OTHER
        }
    }
}

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
    /** Hauteur où chercher les arcs du cercle de Phoenix au-dessus et au-dessous de la ligne, en pixels de la zone. */
    private val ringMargin: Int = 2,
    /** Lignes de pixels de texte comblées entre deux lignes de la même équipe ([KillfeedTeamsParams.textRows]), 0 = aucune. */
    private val textRows: Int = 0,
) {
    private companion object {
        /** Part minimale de la colonne de couleur franche : le nom de la victime (blanc, clair) n'en prend pas plus. */
        const val MIN_COLORED = 0.2

        /** Part de texte (blanc, clair) d'une ligne de pixels comblée ([textRows]) : un trait ou une ligne de lettres. */
        const val TEXT_SHARE = 0.75
    }

    fun rows(frame: ZoneFrame): List<TeamRow> {
        val w = frame.width
        val h = frame.height
        val x0 = (w - from).coerceIn(0, w)
        val x1 = (w - to).coerceIn(x0, w)
        if (x1 - x0 == 0) return emptyList()
        val cls = Array(h) { y -> Array(w) { x -> PixelClass.of(frame.pixels[y * w + x].toInt() and 0xFF) } }
        val out = mutableListOf<TeamRow>()
        for ((t, band) in bands(labels(cls, 0 until h, x0, x1, strict = false), 0)) {
            if (emit(cls, t, band, x0, out)) continue
            // Bande impossible : relue au rouge franc, elle se coupe en vraies lignes quand un mur la prolongeait.
            for ((t2, sub) in bands(labels(cls, band, x0, x1, strict = true), band.first)) emit(cls, t2, sub, x0, out)
        }
        return out.sortedBy { it.center }
    }

    /** Équipe de chaque ligne de pixels [ys] d'après la colonne de la victime ; [strict] : rouge franc seulement. */
    private fun labels(cls: Array<Array<PixelClass>>, ys: IntRange, x0: Int, x1: Int, strict: Boolean): Array<FeedTeam?> {
        val texty = BooleanArray(ys.count())
        val label = Array(ys.count()) { i ->
            val y = ys.first + i
            var ally = 0
            var enemy = 0
            var text = 0
            for (x in x0 until x1) when (cls[y][x]) {
                PixelClass.ALLY -> ally++
                PixelClass.ENEMY -> enemy++
                PixelClass.DULL -> if (!strict) enemy++
                PixelClass.WHITE, PixelClass.LIGHT -> text++
                PixelClass.OTHER -> {}
            }
            texty[i] = text >= TEXT_SHARE * (x1 - x0)
            // Le nom de la victime passe dans la colonne : ses lettres ne comptent ni pour ni contre.
            val need = minShare * (x1 - x0 - text)
            val colored = MIN_COLORED * (x1 - x0)
            when {
                ally >= need && ally >= colored -> FeedTeam.ALLY
                enemy >= need && enemy >= colored -> FeedTeam.ENEMY
                else -> null
            }
        }
        if (textRows > 0) {
            var i = 1
            while (i < label.size) {
                val before = label[i - 1]
                if (label[i] != null || before == null || !texty[i]) {
                    i++
                    continue
                }
                var j = i
                while (j < label.size && label[j] == null && texty[j]) j++
                if (j < label.size && j - i <= textRows && label[j] == before) for (k in i until j) label[k] = before
                i = j
            }
        }
        return label
    }

    /** Suites de lignes de pixels de la même équipe : (équipe, lignes de la zone), [offset] = ligne de label[0]. */
    private fun bands(label: Array<FeedTeam?>, offset: Int): List<Pair<FeedTeam, IntRange>> {
        val out = mutableListOf<Pair<FeedTeam, IntRange>>()
        var y = 0
        while (y < label.size) {
            val t = label[y]
            if (t == null) {
                y++
                continue
            }
            val start = y
            while (y < label.size && label[y] == t) y++
            out += t to (offset + start until offset + y)
        }
        return out
    }

    /** Lignes du killfeed d'une bande [band] de l'équipe [t] ajoutées à [out] ; false si sa hauteur n'en fait aucune. */
    private fun emit(cls: Array<Array<PixelClass>>, t: FeedTeam, band: IntRange, x0: Int, out: MutableList<TeamRow>): Boolean {
        val start = band.first
        val height = band.count()
        val n = (height / pitch).roundToInt()
        if (n !in 1..maxRows || abs(height - n * pitch) > tolerance * pitch) return false
        val killer = if (t == FeedTeam.ALLY) PixelClass.ENEMY else PixelClass.ALLY
        for (k in 0 until n) {
            val a = start + height * k / n
            val b = start + height * (k + 1) / n
            if (killerShare(cls, a, b, x0, killer) >= minKillerShare) out += TeamRow(t, (a + b) / 2.0, ring(cls, a, b, x0))
        }
        return true
    }

    /** Part des colonnes à gauche de [x0] dont la moitié au moins des lignes [a, b[ est de la classe [other]. */
    private fun killerShare(cls: Array<Array<PixelClass>>, a: Int, b: Int, x0: Int, other: PixelClass): Double {
        if (x0 == 0 || b <= a) return 0.0
        var columns = 0
        for (x in 0 until x0) {
            var n = 0
            for (y in a until b) if (cls[y][x] == other || (other == PixelClass.ENEMY && cls[y][x] == PixelClass.DULL)) n++
            if (n * 2 >= b - a) columns++
        }
        return columns.toDouble() / x0
    }

    /**
     * Cercle de Phoenix sur la ligne [a, b[ : un arc blanc dépasse au-dessus et au-dessous d'elle, sur les mêmes
     * colonnes (au moins un quart de hauteur de ligne). Un décor clair derrière l'écart entre deux lignes (ciel) en allume bien
     * plus : au-delà de 2,5 hauteurs de ligne de colonnes blanches, ce n'en est pas un.
     */
    private fun ring(cls: Array<Array<PixelClass>>, a: Int, b: Int, x0: Int): Boolean {
        val height = b - a
        if (a - ringMargin < 0 || b + ringMargin > cls.size || x0 == 0) return false
        var top = 0
        var bottom = 0
        var both = 0
        for (x in 0 until x0) {
            val up = (a - ringMargin until a).any { cls[it][x] == PixelClass.WHITE }
            val down = (b until b + ringMargin).any { cls[it][x] == PixelClass.WHITE }
            if (up) top++
            if (down) bottom++
            if (up && down) both++
        }
        return top + bottom <= 2.5 * height && both >= 0.25 * height
    }
}

/** Mort lue : équipe de la victime, instant d'apparition de sa ligne, Phoenix revenu (« Run it Back »). */
internal data class TeamDeath(val team: FeedTeam, val at: Duration, val revived: Boolean = false)

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
    /** Images où le cercle de Phoenix doit être vu, et leur part parmi celles de la ligne. */
    private val ringSightings: Int = 4,
    private val ringShare: Double = 0.12,
    /** Ligne expirée jamais reprise (voir [KillfeedTeamsParams.lineLife]). */
    private val expiry: LineExpiry? = null,
) {
    private class Track(val team: FeedTeam, var center: Double, val first: Duration, var last: Duration, var sightings: Int, var rings: Int)

    private val tracks = mutableListOf<Track>()

    private fun Track.confirmed() = sightings >= minSightings && last - first >= minSeen

    fun add(at: Duration, rows: List<TeamRow>) {
        val used = HashSet<Track>()
        for (row in rows.sortedBy { it.center }) {
            // La plus ancienne ligne suivie qui peut être celle-ci : même équipe, pas plus haut qu'elle (à la tolérance près).
            val match = tracks.firstOrNull {
                it !in used && it.team == row.team && row.center <= it.center + tolerance &&
                    at - it.last <= (if (it.confirmed()) maxGap else pendingGap) &&
                    (expiry == null || !expiry.expired(it.first, at))
            }
            if (match == null) {
                tracks += Track(row.team, row.center, at, at, 1, if (row.ring) 1 else 0).also { used += it }
            } else {
                match.center = row.center
                match.last = at
                match.sightings++
                if (row.ring) match.rings++
                used += match
            }
        }
    }

    /** Morts vues, dans l'ordre : équipe de la victime, instant d'apparition, mort de Phoenix pendant son ultime. */
    fun deaths(): List<TeamDeath> = tracks.filter { it.confirmed() }.sortedBy { it.first }.map {
        TeamDeath(it.team, it.first, it.rings >= ringSightings && it.rings >= ringShare * it.sightings)
    }

    /** Lignes où le cercle de Phoenix a été vu au moins une fois : (instant, images avec cercle, images), pour le journal. */
    fun rings(): List<Triple<Duration, Int, Int>> = tracks.filter { it.confirmed() && it.rings > 0 }.sortedBy { it.first }.map { Triple(it.first, it.rings, it.sightings) }
}

/**
 * Morts des alliés et des adversaires lues dans le killfeed, à la couleur de la victime de chaque ligne. C'est ce qui
 * permet de compter les alliés en vie dans un round, et de reconnaître un vrai clutch (le joueur, dernier en vie,
 * gagne le round) sans Outplayed. Les morts du joueur y sont des morts alliées : le montage les retire grâce aux morts
 * du détecteur `killfeed` (ou d'Outplayed).
 */
class KillfeedTeamsDetector(override val id: String, private val params: KillfeedTeamsParams) : SignalDetector {
    private class Prepared(
        val subscription: FrameSampler.Subscription,
        val rows: MutableList<List<TeamRow>>,
        val tolerance: Int,
        /** Par image lue : barres de vie alliées pleines (début de round), si elles sont lues. */
        val bars: MutableMap<Int, Boolean>,
        /** Par image lue : bandeau « CLUTCH » reconnu, s'il est lu. */
        val banners: MutableMap<Int, Boolean>,
    )

    private fun kindOf(death: TeamDeath): String {
        val revived = if (death.team == FeedTeam.ALLY) params.allyRevivedKind else params.enemyRevivedKind
        return if (death.revived && revived.isNotEmpty()) revived else if (death.team == FeedTeam.ALLY) params.allyKind else params.enemyKind
    }

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
            KillfeedTeamsParams.classes(params.allyColor, params.enemyColor, params.enemyStrict),
        )
        // Pixels de référence → pixels de la zone lue.
        val k = zone.height.toDouble() / ch * video.height / params.referenceHeight
        fun px(v: Int) = (v * k).roundToInt().coerceAtLeast(1)
        if (zone.width < px(params.victimFrom) || zone.height < params.rowPitch * k) {
            throw ConfigException(Message("error.detector.zoneTooSmall", "id" to id, "size" to "${zone.width}x${zone.height}"))
        }
        val reader = TeamRowReader(
            px(params.victimFrom), px(params.victimTo), params.rowPitch * k, params.minShare, params.pitchTolerance, params.maxRows,
            params.minKillerShare, px(params.ringMargin), if (params.textRows == 0) 0 else px(params.textRows),
        )
        val rows = mutableListOf<List<TeamRow>>()
        val bar = params.roundBar
        val barZone = bar?.let { b ->
            val r = ScreenGeometry.rescale(b.region, b.referenceWidth.toDouble() / b.referenceHeight, video.width.toDouble() / video.height, b.anchor)
            val bx = (r.x * video.width).roundToInt().coerceIn(0, video.width - 1)
            val by = (r.y * video.height).roundToInt().coerceIn(0, video.height - 1)
            val bw = (r.width * video.width).roundToInt().coerceIn(1, video.width - bx)
            val bh = (r.height * video.height).roundToInt().coerceIn(1, video.height - by)
            // Lue à la définition de référence (480p) : une barre y fait 2 px de haut.
            val shrink = (b.referenceHeight.toDouble() / video.height).coerceAtMost(1.0)
            FrameZone(bx, by, bw, bh, (bw * shrink).roundToInt().coerceAtLeast(1), (bh * shrink).roundToInt().coerceAtLeast(1), "gt(min(min(r(X,Y),g(X,Y)),b(X,Y)),${b.white})*255")
        }
        val bars = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
        val banner = params.clutchBanner
        val bannerTemplate = banner?.let { PreparedTemplate(GrayImage.load(ctx.configDir.resolve(it.template)), it.template) }
        val bannerZone = banner?.let { b ->
            val r = ScreenGeometry.rescale(b.region, b.referenceWidth.toDouble() / b.referenceHeight, video.width.toDouble() / video.height, b.anchor)
            val bx = (r.x * video.width).roundToInt().coerceIn(0, video.width - 1)
            val by = (r.y * video.height).roundToInt().coerceIn(0, video.height - 1)
            val bw = (r.width * video.width).roundToInt().coerceIn(1, video.width - bx)
            val bh = (r.height * video.height).roundToInt().coerceIn(1, video.height - by)
            // Lue à l'échelle du gabarit (480p).
            val shrink = b.referenceHeight.toDouble() / video.height
            FrameZone(bx, by, bw, bh, (bw * shrink).roundToInt().coerceAtLeast(1), (bh * shrink).roundToInt().coerceAtLeast(1), "gt(min(min(r(X,Y),g(X,Y)),b(X,Y)),${b.white})*255")
        }
        val banners = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
        log.info { "$id : équipes du killfeed ${cw}x$ch à ($cx, $cy), lu en ${zone.width}x${zone.height}, ${params.fps} img/s" }
        val subscription = ctx.frames.subscribe(
            spec = FrameSpec.fps(params.fps, params.hwaccel),
            zones = listOfNotNull(zone, barZone, bannerZone),
            label = id,
            progress = ctx.progress,
            onReset = {
                rows.clear()
                bars.clear()
                banners.clear()
            },
            onFrame = { index, frames ->
                rows += reader.rows(frames[0])
                if (barZone != null && bar != null) bars[index] = fullBar(frames[1], bar.fullShare)
                if (bannerZone != null && banner != null && bannerTemplate != null) {
                    val f = frames[if (barZone != null) 2 else 1]
                    val roi = GrayImage(f.width, f.height, f.pixels)
                    banners[index] = roi.width >= bannerTemplate.width && roi.height >= bannerTemplate.height &&
                        TemplateMatcher.match(roi, bannerTemplate).score >= banner.minScore
                }
            },
        )
        prepared = Prepared(subscription, rows, px(params.matchTolerance), bars, banners)
    }

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        if (ctx.media.video == null) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        if (prepared == null) prepare(ctx)
        val state = prepared ?: return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        val times = state.subscription.await()
        val count = minOf(times.size, state.rows.size)
        if (count == 0) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noFrames"))
        val tracker = TeamTracker(
            state.tolerance, params.minSeen, params.maxGap, params.pendingGap,
            ringSightings = ceil(params.ringSeen.toDouble(DurationUnit.SECONDS) * params.fps).toInt().coerceAtLeast(1), ringShare = params.ringShare,
            expiry = params.lineLife?.let(::LineExpiry),
        )
        for (i in 0 until count) tracker.add(times[i], state.rows[i])
        val end = ctx.media.duration
        log.info { "$id : cercle de Phoenix (images avec / vues) : " + tracker.rings().joinToString { (at, r, n) -> "${Durations.format(at)} $r/$n" } }
        val deaths = tracker.deaths().map { SignalEvent((it.at + params.offset).coerceIn(Duration.ZERO, end), kindOf(it), 1.0) }
        val bar = params.roundBar
        val starts = if (bar == null || state.bars.isEmpty()) emptyList() else {
            roundStarts(times.take(count), (0 until count).map { state.bars[it] ?: false }, bar.held, bar.before).map { SignalEvent(it, bar.kind, 1.0) }
        }
        val bannerParams = params.clutchBanner
        val clutches = if (bannerParams == null || state.banners.isEmpty()) emptyList() else {
            bannerAppearances(times.take(count), (0 until count).map { state.banners[it] ?: false }, bannerParams.held, bannerParams.apart).map { SignalEvent(it, bannerParams.kind, 1.0) }
        }
        val events = (deaths + starts + clutches).sortedBy { it.at }
        log.info { "$id : ${events.groupingBy { it.kind }.eachCount()} : " + events.joinToString { "${it.kind} ${Durations.format(it.at)}" } }
        return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, events)
    }
}

/** Barres de vie alliées pleines : assez de colonnes de la zone où passe une barre (pixel allumé). */
internal fun fullBar(frame: ZoneFrame, share: Double): Boolean {
    var columns = 0
    for (x in 0 until frame.width) {
        if ((0 until frame.height).any { y -> (frame.pixels[y * frame.width + x].toInt() and 0xFF) >= 128 }) columns++
    }
    return columns >= share * frame.width
}

class KillfeedTeamsDetectorFactory : SignalDetectorFactory {
    override val type = "killfeed-teams"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        KillfeedTeamsDetector(id, params.decode(KillfeedTeamsParams.serializer()) { throw ConfigException(Message("error.detector.paramRequired", "id" to id, "params" to "region")) })
}
