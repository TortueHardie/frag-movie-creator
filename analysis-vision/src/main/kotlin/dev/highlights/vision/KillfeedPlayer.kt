package dev.highlights.vision

import dev.highlights.core.model.CropRegion
import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Kill ou assistance, pour les lignes encadrées ([OutlineParams]) : dans CS2 le contour rouge marque toute ligne où le
 * joueur figure, qu'il ait tué (« joueur [+ coéquipier] arme victime ») ou seulement aidé (« coéquipier + joueur arme
 * victime »). Ce qui les sépare : le premier nom de la ligne. Le pseudo du joueur n'est pas donné, il est appris sur la
 * vidéo : c'est le premier mot qui revient le plus souvent en tête des lignes encadrées (les kills sont bien plus nombreux
 * que les assistances : 33 contre 7 mesurés). Tailles en pixels de la capture de référence, comme [OutlineParams].
 *
 * Mesuré sur « CS2- 33 Kills On Dust 2 » (854x480) : pseudo de 21 px sur 10 ; ressemblance en tête de ligne 0,86 à 0,97
 * sur les kills, 0,07 à 0,85 sur les assistances (« Adamant », « Cukrileo » ressemblent à « Kryptek » à cette taille).
 * Les kills mal lus (ligne rognée sur fond clair, ligne empilée) sont rattrapés par la récompense du chat ([reward]).
 */
@Serializable
data class PlayerParams(
    /** Ressemblance avec le pseudo appris, en tête de ligne, au-dessus de laquelle la ligne est un kill. */
    val minScore: Double = 0.86,
    /** En dessous de [minScore] mais au-dessus de ça, la ligne est un kill si le chat affiche une récompense ([reward]). */
    val weakScore: Double = 0.3,
    /** Écart de niveau au-dessus du fond de la ligne qui fait un pixel de texte (lettres claires sur fond sombre). */
    val textContrast: Int = 45,
    /** Colonnes vides qui séparent deux mots (les lettres sont espacées d'un pixel, les mots de 4 à 5). */
    val wordGap: Int = 3,
    /** Glissement cherché autour du bord gauche estimé de la ligne : le texte commence 5 à 7 px après lui. */
    val searchBefore: Int = 4,
    val searchAfter: Int = 12,
    /** Images de chaque ligne gardées pour l'apprendre et la juger. */
    val samples: Int = 25,
    /** Récompense affichée dans le chat (« +$300 for neutralizing an enemy ») ; null = pseudo seul. */
    val reward: RewardParams? = null,
)

/**
 * Lignes de récompense du chat : dans CS2, chaque kill du joueur y ajoute « +$300 for neutralizing an enemy », jamais une
 * assistance. Seul le début de ligne est lu (« +$ » vert), en couleur ([color]) ; une nouvelle ligne verte est une
 * récompense. Mesuré seul : 27 kills sur 33 confirmés, 1 assistance sur 7 (le texte vert se délave sur le sable).
 */
@Serializable
data class RewardParams(
    /** Zone des débuts de ligne du chat (normalisée, même capture de référence que le killfeed). */
    val region: CropRegion,
    /** Expression `geq` du vert du « +$ » : vert au moins égal au rouge, nettement au-dessus du bleu, assez clair. */
    val color: String = GREEN,
    val threshold: Int = 128,
    /** Pixels verts dans une ligne de pixels pour qu'elle compte. */
    val minPixels: Int = 2,
    /** Une récompense est cherchée de [before] avant l'apparition de la ligne à [after] après. */
    val before: SerialDuration = 1.seconds,
    val after: SerialDuration = 3.seconds,
) {
    companion object {
        const val GREEN = "if(gte(g(X,Y),r(X,Y)-2)*gt(g(X,Y)-b(X,Y),35)*gt(g(X,Y),110),255,0)"
    }
}

/** Lignes de récompense du chat : comptées image par image, puis les instants où une ligne de plus apparaît. */
internal class RewardLines(private val threshold: Int, private val minPixels: Int, private val minBand: Int, private val maxBand: Int) {
    /** Lignes vertes de l'image : suites de lignes de pixels assez vertes, de la hauteur d'une ligne de texte. */
    fun count(frame: GrayImage): Int {
        var bands = 0
        var run = 0
        var holes = 0
        for (y in 0 until frame.height) {
            var lit = 0
            for (x in 0 until frame.width) if (frame[x, y] >= threshold) lit++
            if (lit >= minPixels) {
                run += holes + 1
                holes = 0
            } else if (run > 0 && ++holes > 2) {
                if (run in minBand..maxBand) bands++
                run = 0
                holes = 0
            }
        }
        if (run in minBand..maxBand) bands++
        return bands
    }

    companion object {
        /** Une ligne de plus que tout ce qui était là dans la seconde d'avant, et pendant trois images d'affilée. */
        fun appearances(times: List<Duration>, counts: List<Int>): List<Duration> {
            val out = mutableListOf<Duration>()
            for (i in counts.indices) {
                val back = (i - 1 downTo 0).takeWhile { times[i] - times[it] <= 1.seconds }
                if (back.isEmpty() || i + 2 >= counts.size) continue
                if (minOf(counts[i], counts[i + 1], counts[i + 2]) > back.maxOf { counts[it] }) out += times[i]
            }
            return out
        }
    }
}

/** Image d'une ligne du killfeed (canal le plus clair) et bord gauche mesuré sur cette image. */
internal class LineSample(val left: Int, val image: GrayImage)

/**
 * Pseudo du joueur appris en tête des lignes encadrées, puis ressemblance de chaque ligne avec lui. [margin] : lignes de
 * pixels ajoutées au-dessus et au-dessous de la ligne dans les images (2 px en 480p), [inner] : celles du contour et du
 * fond à sauter pour ne garder que le texte (3 px).
 */
internal class PlayerName(private val params: PlayerParams, private val scale: Double, private val margin: Int, private val inner: Int) {
    private fun px(v: Int) = (v * scale).roundToInt().coerceAtLeast(1)

    /** Bord gauche d'une ligne : la médiane de ceux mesurés (un trait s'efface par morceaux, rarement tout le temps). */
    fun leftOf(samples: List<LineSample>): Int = samples.map { it.left }.sorted()[samples.size / 2]

    /** Premier mot d'une image de ligne (colonne où il commence, son image) : texte plus clair que le fond, jusqu'au premier blanc d'un mot. */
    private fun firstWord(sample: LineSample, left: Int): Pair<Int, GrayImage>? {
        val img = sample.image
        val y0 = margin + inner
        val y1 = img.height - margin - inner
        val x0 = (left + px(3)).coerceAtLeast(0)
        if (y1 - y0 < 3 || x0 >= img.width) return null
        val values = (y0 until y1).flatMap { y -> (x0 until img.width).map { x -> img[x, y] } }.sorted()
        val limit = values[values.size / 2] + params.textContrast
        fun text(x: Int) = (y0 until y1).any { img[x, it] > limit }
        var x = x0
        while (x < img.width && !text(x)) x++
        if (x >= img.width) return null
        val start = x
        var end = x
        var gap = 0
        while (x < img.width) {
            if (text(x)) {
                gap = 0
                end = x
            } else if (++gap >= params.wordGap) {
                break
            }
            x++
        }
        return start to img.crop(start, y0, end - start + 1, y1 - y0)
    }

    /** Le pseudo : le premier mot de largeur la plus fréquente parmi les lignes, moyenné. Null si rien de net. */
    fun learn(lines: List<List<LineSample>>): GrayImage? {
        val words = lines.map { samples -> val left = leftOf(samples); samples.mapNotNull { firstWord(it, left)?.second }.filter { it.width >= px(4) } }
        val widths = words.mapNotNull { w -> w.map { it.width }.sorted().getOrNull(w.size / 2) }
        if (widths.isEmpty()) return null
        val counts = widths.groupingBy { it }.eachCount()
        val width = counts.keys.maxBy { w -> (w - 1..w + 1).sumOf { counts[it] ?: 0 } }
        val pool = words.filter { w -> w.isNotEmpty() && abs(w.map { it.width }.sorted()[w.size / 2] - width) <= 1 }.flatten().filter { it.width == width }
        if (pool.size < 3) return null
        val h = pool.minOf { it.height }
        val sum = DoubleArray(width * h)
        for (img in pool) for (y in 0 until h) for (x in 0 until width) sum[y * width + x] += img[x, y].toDouble()
        return GrayImage(width, h, ByteArray(width * h) { (sum[it] / pool.size).roundToInt().coerceIn(0, 255).toByte() })
    }

    /**
     * Ressemblance de la ligne avec le pseudo, posé sur le début de son premier mot (à 1 px près) : médiane sur ses
     * images. Posé n'importe où en tête de ligne, il ressemblait aussi au milieu d'autres pseudos (« Adamant » : 0,87) ;
     * calé sur le début du mot, une assistance tombe à 0,61-0,83, un kill reste à 0,86-0,97.
     */
    fun score(samples: List<LineSample>, name: GrayImage): Double {
        val left = leftOf(samples)
        val scores = samples.mapNotNull { s -> firstWord(s, left)?.let { (start, _) -> best(s.image, name, start - px(1), start + px(1)) } }.sorted()
        return scores.getOrNull(scores.size / 2) ?: -1.0
    }

    private fun best(img: GrayImage, tpl: GrayImage, x0: Int, x1: Int): Double {
        var best = -1.0
        for (y in 0..img.height - tpl.height) for (x in x0.coerceAtLeast(0)..minOf(x1, img.width - tpl.width)) {
            best = maxOf(best, ncc(img, x, y, tpl))
        }
        return best
    }

    private fun ncc(img: GrayImage, x0: Int, y0: Int, tpl: GrayImage): Double {
        val n = tpl.width * tpl.height
        var sa = 0.0
        var sb = 0.0
        for (y in 0 until tpl.height) for (x in 0 until tpl.width) {
            sa += img[x0 + x, y0 + y]
            sb += tpl[x, y]
        }
        val ma = sa / n
        val mb = sb / n
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (y in 0 until tpl.height) for (x in 0 until tpl.width) {
            val a = img[x0 + x, y0 + y] - ma
            val b = tpl[x, y] - mb
            ab += a * b
            aa += a * a
            bb += b * b
        }
        val d = sqrt(aa * bb)
        return if (d > 0) ab / d else -1.0
    }
}

internal fun GrayImage.crop(x: Int, y: Int, w: Int, h: Int): GrayImage {
    val out = ByteArray(w * h)
    for (j in 0 until h) System.arraycopy(pixels, (y + j) * width + x, out, j * w, w)
    return GrayImage(w, h, out)
}
