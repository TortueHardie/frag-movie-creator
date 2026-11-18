package dev.highlights.vision

import dev.highlights.core.video.ZoneFrame
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Killfeed où le joueur est marqué par sa ligne entière, pas par un portrait (Counter-Strike 2) : une ligne où il tue
 * (ou aide) est entourée d'un contour fin de sa couleur, celle où il meurt a un fond plein de cette couleur. Tailles en
 * pixels de la capture de référence ([KillfeedParams.referenceHeight]).
 *
 * Mesuré sur « CS2- 33 Kills On Dust 2 » (854x480, 30 img/s) : ligne de 16 px de haut, 40 à 290 de large, alignée à
 * droite. Le contour fait 2 px en haut et en bas, un seul sur les côtés, et la couleur sous-échantillonnée (4:2:0) en
 * efface des morceaux sur un ciel ou un mur clair (côtés, moitié gauche d'un trait, trous de 8 à 14 px sous les
 * lettres). Une ligne est donc une paire de traits horizontaux qui finissent au même bord droit, l'un 13 à 16 px sous
 * l'autre, l'intérieur presque vide ; un fond plein de la hauteur d'une ligne est une mort.
 */
@Serializable
data class OutlineParams(
    /** Écart entre le haut d'un trait et le bas de l'autre, plus un (16 mesurés ; 18 entre deux lignes empilées). */
    val minHeight: Int = 13,
    val maxHeight: Int = 17,
    /** Hauteur d'une ligne : pas d'une ligne à la suivante. */
    val lineHeight: Int = 16,
    /** Largeur d'un trait : la plus courte des lignes (deux pseudos courts, un pistolet) à la plus longue (assistance). */
    val minWidth: Int = 40,
    val maxWidth: Int = 290,
    /** Part des pixels d'un trait qui ont la couleur, trous compris ([KillfeedParams.bridge] : 16 px en 480p). */
    val barDensity: Double = 0.5,
    /** Recouvrement minimal des deux traits, en part du plus long : la moitié gauche de l'un s'efface souvent. */
    val minOverlap: Double = 0.5,
    /** Écart toléré entre les bords droits des deux traits. */
    val edgeTolerance: Int = 6,
    /** Part maximale de l'intérieur colorée : au-delà, c'est un fond plein (mort), pas un contour. */
    val maxInnerFill: Double = 0.35,
    /** Fond plein d'une mort : part minimale de la ligne colorée (50 à 76 % mesurés, le texte blanc fait le reste). */
    val deathFill: Double = 0.5,
    /**
     * Distance entre le bord droit d'une ligne et celui de la zone (donc de l'écran) : 2 à 10 px mesurés. Une tache
     * collée au bord, ou qui s'arrête loin de lui, est du décor (voile rouge des dégâts, panneau, toit).
     */
    val minEdgeGap: Int = 2,
    val maxEdgeGap: Int = 20,
    /**
     * Une ligne suivie qui s'allonge d'autant vers la gauche est une autre ligne : quand la plus ancienne disparaît, la
     * suivante prend sa place en moins d'une seconde. Un trait ne fait que perdre des pixels (bord gauche mesuré plus à
     * droite que le vrai, jusqu'à 60 px), jamais en gagner. Une ligne plus courte qui remplace une plus longue n'est
     * pas vue (1 kill sur 33 mesurés).
     */
    val newLineGrowth: Int = 45,
) {
    init {
        require(minHeight in 2..maxHeight && minWidth in 1..maxWidth && lineHeight >= minHeight) { "tailles de la ligne : min ≤ max" }
        require(barDensity in 0.0..1.0 && minOverlap in 0.0..1.0 && maxInnerFill in 0.0..1.0 && deathFill in 0.0..1.0) { "parts entre 0 et 1" }
        require(minEdgeGap in 0..maxEdgeGap) { "minEdgeGap ≤ maxEdgeGap" }
    }
}

/** Lecture d'une image du killfeed. */
internal fun interface RowReader {
    fun rows(frame: ZoneFrame): List<FeedRow>
}

/**
 * Lecteur des lignes encadrées ([OutlineParams], tailles déjà en pixels de la zone lue) : contour = [KillfeedRole.KILL],
 * fond plein = [KillfeedRole.DEATH].
 */
internal class OutlineReader(
    private val threshold: Int,
    /** Trous comblés dans un trait (pixels). */
    private val bridge: Int,
    private val shape: OutlineParams,
) : RowReader {

    private class Run(val start: Int, val end: Int) {
        val width get() = end - start + 1
    }

    private class Band(val top: Int, var bottom: Int, var left: Int, var right: Int)

    /** Paire de traits : haut du trait supérieur, ligne du trait inférieur, bord gauche du trait supérieur, bord droit. */
    private class Bars(val top: Int, val bottom: Int, val left: Int, val right: Int)

    override fun rows(frame: ZoneFrame): List<FeedRow> {
        val w = frame.width
        val h = frame.height
        val pixels = frame.pixels
        val on = BooleanArray(w * h) { (pixels[it].toInt() and 0xFF) >= threshold }
        val runs = Array(h) { runs(on, it * w, w) }
        val kills = kills(on, w, h, runs)
        val deaths = deaths(on, w, h, runs, kills)
        return (kills + deaths).filter { it.right in (w - 1 - shape.maxEdgeGap)..(w - 1 - shape.minEdgeGap) && it.left > shape.minEdgeGap }
    }

    /** Contours : deux traits qui finissent au même bord droit, à une hauteur de ligne l'un de l'autre, l'intérieur vide. */
    private fun kills(on: BooleanArray, w: Int, h: Int, runs: Array<List<Run>>): List<FeedRow> {
        val pairs = mutableListOf<Bars>()
        for (y in 0 until h) for (a in runs[y]) {
            for (dy in shape.minHeight - 1 until shape.maxHeight) {
                val y2 = y + dy
                if (y2 >= h) break
                for (b in runs[y2]) {
                    if (abs(a.end - b.end) > shape.edgeTolerance) continue
                    val overlap = minOf(a.end, b.end) - maxOf(a.start, b.start) + 1
                    if (overlap < maxOf(shape.minWidth.toDouble(), shape.minOverlap * maxOf(a.width, b.width))) continue
                    val right = maxOf(a.end, b.end)
                    if (right - minOf(a.start, b.start) + 1 !in shape.minWidth..shape.maxWidth) continue
                    if (fill(on, w, y + 2, y2 - 2, maxOf(a.start, b.start), minOf(a.end, b.end)) > shape.maxInnerFill) continue
                    pairs += Bars(y, y2, a.start, right)
                }
            }
        }
        // Les paires d'une même ligne (trait de 2 px, lignes empilées dont les traits se touchent) commencent à moins
        // d'une ligne l'une de l'autre : on garde celle dont la hauteur est la plus proche d'une ligne.
        val out = mutableListOf<FeedRow>()
        var group = mutableListOf<Bars>()
        fun close() {
            if (group.isEmpty()) return
            val best = group.minWith(compareBy({ abs(it.bottom - it.top - (shape.lineHeight - 1)) }, { it.left }))
            out += FeedRow(KillfeedRole.KILL, group[0].top, group[0].top + shape.lineHeight, best.left, group.maxOf { it.right })
            group = mutableListOf()
        }
        for (p in pairs.sortedBy { it.top }) {
            if (group.isNotEmpty() && p.top - group[0].top >= shape.minHeight - 3) close()
            group += p
        }
        close()
        return out
    }

    /**
     * Fonds pleins : le texte blanc coupe les lignes de pixels du milieu, seules celles du haut et du bas restent des
     * traits continus. Une bande de traits sous laquelle une hauteur de ligne est colorée en grande partie est une mort.
     * Une mort juste sous un kill touche son trait du bas : elle commence après lui.
     */
    private fun deaths(on: BooleanArray, w: Int, h: Int, runs: Array<List<Run>>, kills: List<FeedRow>): List<FeedRow> {
        val bands = mutableListOf<Band>()
        var open = listOf<Band>()
        for (y in 0 until h) {
            val next = mutableListOf<Band>()
            for (r in runs[y]) {
                val band = open.firstOrNull { b -> b !in next && minOf(b.right, r.end) - maxOf(b.left, r.start) > 0.5 * minOf(r.end - r.start, b.right - b.left) }
                if (band != null) {
                    band.bottom = y
                    band.left = minOf(band.left, r.start)
                    band.right = maxOf(band.right, r.end)
                    next += band
                } else {
                    next += Band(y, y, r.start, r.end).also { bands += it }
                }
            }
            open = next
        }
        val height = (shape.minHeight + shape.maxHeight) / 2
        val out = mutableListOf<FeedRow>()
        for (b in bands.sortedBy { it.top }) {
            var top = b.top
            for (k in kills) if (top in k.top..k.bottom && minOf(k.right, b.right) > maxOf(k.left, b.left)) top = maxOf(top, k.bottom)
            // Plus haute qu'une ligne : un aplat du décor (panneau, voile rouge des dégâts), pas un fond de ligne.
            if (b.bottom < top || b.bottom - top + 1 > shape.maxHeight || top + height > h) continue
            if (out.any { top < it.bottom && minOf(it.right, b.right) > maxOf(it.left, b.left) }) continue
            val left = (top until minOf(h, top + 4)).flatMap { runs[it] }.filter { minOf(it.end, b.right) > maxOf(it.start, b.left) }.minOfOrNull { it.start } ?: b.left
            if (fill(on, w, top, top + height - 1, left, b.right) >= shape.deathFill) out += FeedRow(KillfeedRole.DEATH, top, top + height, left, b.right)
        }
        return out
    }

    /** Traits d'une ligne de pixels : suites de pixels colorés (trous de [bridge] au plus), assez longues et denses. */
    private fun runs(on: BooleanArray, offset: Int, w: Int): List<Run> {
        val out = mutableListOf<Run>()
        var start = -1
        var end = -1
        var count = 0
        fun close() {
            if (start >= 0 && end - start + 1 >= shape.minWidth && count >= shape.barDensity * (end - start + 1)) out += Run(start, end)
        }
        for (x in 0 until w) {
            if (!on[offset + x]) continue
            if (start >= 0 && x - end <= bridge + 1) {
                end = x
                count++
            } else {
                close()
                start = x
                end = x
                count = 1
            }
        }
        close()
        return out
    }

    private fun fill(on: BooleanArray, w: Int, top: Int, bottom: Int, left: Int, right: Int): Double {
        if (bottom < top || right < left) return 0.0
        var count = 0
        for (y in top..bottom) for (x in left..right) if (on[y * w + x]) count++
        return count.toDouble() / ((bottom - top + 1) * (right - left + 1))
    }
}
