package dev.highlights.assistant

import dev.highlights.core.model.CropRegion
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration

/** Kill marqué par l'utilisateur : l'instant où le repère apparaît, et la zone (normalisée) qui l'entoure. */
data class KillMark(val at: Duration, val box: CropRegion)

/** Méthodes de détection essayées, chacune portée par un détecteur existant. */
enum class CueMethod(val type: String) {
    /** Image du repère reconnue telle quelle (notification, icône) : `hud-template`. */
    TEMPLATE("hud-template"),

    /** Ligne du killfeed encadrée ou remplie de la couleur du joueur (CS2) : `killfeed` en mode `outline`. */
    COLOR_LINE("killfeed"),

    /** Compteur du HUD : `hud-counter` avec les chiffres appris sur la vidéo ([DigitLearner]), sinon `hud-change`. */
    COUNTER("hud-change"),

    /** Texte du repère lu par l'OCR de Windows (« ELIMINATED ») : `ocr-log`. */
    OCR("ocr-log"),
}

/**
 * Détecteur candidat : son bloc YAML (une entrée de `detectors:`, indentée de deux espaces) et les fichiers à poser dans
 * le dossier de configuration (chemin relatif → contenu). [minScore] : score minimal d'un événement (modèle d'image,
 * dont le seuil est choisi après le balayage d'après les scores des kills marqués).
 */
data class Candidate(
    val method: CueMethod,
    val detectorId: String,
    val yaml: String,
    val files: Map<String, ByteArray> = emptyMap(),
    val minScore: Double? = null,
    /** Ce que la méthode a trouvé sur les kills marqués (couleur, mot lu…), pour le rapport. */
    val detail: String = "",
) {
    /** Type du détecteur écrit dans [yaml] : le compteur est `hud-counter` quand ses chiffres ont été appris, sinon `hud-change`. */
    val detectorType: String get() = Regex("""type: (\S+)""").find(yaml)?.groupValues?.get(1) ?: method.type
}

internal fun Double.f(digits: Int = 6): String = String.format(Locale.ROOT, "%.${digits}f", this).trimEnd('0').trimEnd('.').ifEmpty { "0" }

internal fun region(r: CropRegion) = "{ x: ${r.x.f()}, y: ${r.y.f()}, width: ${r.width.f()}, height: ${r.height.f()} }"

/**
 * Couleur du joueur dans une ligne du killfeed : la teinte saturée qui apparaît dans la zone marquée au kill et n'y
 * était pas juste avant. Teinte = (rouge − vert, vert − bleu), indépendante de la luminosité : le contour rouge de CS2
 * vaut (71, −8) sur un mur clair et (73, 2) en fond plein sur du noir, le sable (45, 38).
 */
internal data class LineColor(val c1: Double, val c2: Double, val share: Double) {
    /** Expression `geq` : 0 loin de la teinte, jusqu'à 160 dessus (seuil 25 à 34 d'écart). */
    fun expression(): String {
        val d = "abs(r(X,Y)-g(X,Y)-(${c1.roundToInt()}))+abs(g(X,Y)-b(X,Y)-(${c2.roundToInt()}))"
        return "max(4*(40-($d)),0)"
    }

    fun matches(r: Int, g: Int, b: Int) = 4 * (40 - (abs(r - g - c1) + abs(g - b - c2))) >= THRESHOLD

    companion object {
        const val THRESHOLD = 25
        private const val MIN_SATURATION = 40
        private const val BIN = 8

        /** Teinte qui apparaît dans [box] entre chaque paire (avant, pendant) ; null si rien de net (< 1 % de la zone). */
        fun find(pairs: List<Pair<RgbFrame, RgbFrame>>, box: (RgbFrame) -> PixelBox): LineColor? {
            val gained = HashMap<Pair<Int, Int>, Int>()
            val sums = HashMap<Pair<Int, Int>, DoubleArray>()
            var area = 0
            for ((before, during) in pairs) {
                val b = box(during)
                area += b.width * b.height
                for (y in b.y until b.bottom) for (x in b.x until b.right) {
                    for ((frame, sign) in listOf(during to 1, before to -1)) {
                        val r = frame.r(x, y)
                        val g = frame.g(x, y)
                        val bl = frame.b(x, y)
                        if (max(r, max(g, bl)) - min(r, min(g, bl)) < MIN_SATURATION) continue
                        val key = Math.floorDiv(r - g, BIN) to Math.floorDiv(g - bl, BIN)
                        gained.merge(key, sign, Int::plus)
                        if (sign > 0) sums.getOrPut(key) { DoubleArray(3) }.let { it[0] += (r - g).toDouble(); it[1] += (g - bl).toDouble(); it[2] += 1.0 }
                    }
                }
            }
            // Bin voisin compris : une teinte à cheval sur deux cases n'est pas coupée en deux.
            val best = gained.keys.maxByOrNull { k -> (-1..1).sumOf { i -> (-1..1).sumOf { j -> gained[(k.first + i) to (k.second + j)] ?: 0 } } } ?: return null
            var c1 = 0.0
            var c2 = 0.0
            var n = 0.0
            var net = 0
            for (i in -1..1) for (j in -1..1) {
                val k = (best.first + i) to (best.second + j)
                net += gained[k] ?: 0
                sums[k]?.let { c1 += it[0]; c2 += it[1]; n += it[2] }
            }
            if (n == 0.0 || area == 0 || net < 0.01 * area) return null
            return LineColor(c1 / n, c2 / n, net.toDouble() / area)
        }
    }
}

/**
 * Hauteur et bord droit de la ligne marquée, mesurés sur ses traits de couleur : l'utilisateur trace un cadre à peu
 * près autour de la ligne, la hauteur exacte (16 px en 480p dans CS2) fixe toutes les tailles du mode `outline`.
 */
internal data class LineGeometry(val top: Int, val height: Int, val right: Int)

internal fun measureLine(frame: RgbFrame, box: PixelBox, color: LineColor): LineGeometry? {
    // Lignes de pixels dont une bonne part a la couleur : les traits du haut et du bas du contour, ou tout un fond plein.
    val rows = (box.y until box.bottom).filter { y -> (box.x until box.right).count { x -> color.matches(frame.r(x, y), frame.g(x, y), frame.b(x, y)) } >= box.width * 0.3 }
    if (rows.size < 2) return null
    val top = rows.first()
    val bottom = rows.last()
    val height = bottom - top + 1
    if (height < 6) return null
    val right = (box.x until box.right).last { x -> rows.any { y -> color.matches(frame.r(x, y), frame.g(x, y), frame.b(x, y)) } }
    return LineGeometry(top, height, right)
}
