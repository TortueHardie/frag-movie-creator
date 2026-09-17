package dev.highlights.vision

import dev.highlights.core.ConfigException
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile
import kotlin.math.sqrt

/** Image 8 bits en niveaux de gris, ligne par ligne. */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(pixels.size >= width * height) { "GrayImage : ${pixels.size} octets pour ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    companion object {
        fun load(file: Path): GrayImage {
            if (!file.isRegularFile()) throw ConfigException("Modèle d'image introuvable : $file")
            val image: BufferedImage = ImageIO.read(file.toFile()) ?: throw ConfigException("Image illisible : $file")
            val pixels = ByteArray(image.width * image.height)
            // Niveaux de gris : échantillons bruts. getRGB appliquerait une conversion gamma qui éclaircit les gris moyens
            // (un modèle de traits fins passait de 165 à 724 pixels « clairs »).
            if (image.raster.numBands == 1) {
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    pixels[y * image.width + x] = image.raster.getSample(x, y, 0).coerceIn(0, 255).toByte()
                }
                return GrayImage(image.width, image.height, pixels)
            }
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val rgb = image.getRGB(x, y)
                    val luma = (0.299 * (rgb shr 16 and 0xFF) + 0.587 * (rgb shr 8 and 0xFF) + 0.114 * (rgb and 0xFF)).toInt()
                    pixels[y * image.width + x] = luma.coerceIn(0, 255).toByte()
                }
            }
            return GrayImage(image.width, image.height, pixels)
        }
    }
}

/** Modèle centré (moyenne soustraite) et sa norme, calculés une fois. */
class PreparedTemplate(image: GrayImage, val name: String) {
    val width = image.width
    val height = image.height
    val zeroMean = FloatArray(width * height)
    val norm: Double

    init {
        val mean = image.pixels.take(width * height).sumOf { it.toInt() and 0xFF }.toDouble() / (width * height)
        var sq = 0.0
        for (i in zeroMean.indices) {
            val v = ((image.pixels[i].toInt() and 0xFF) - mean).toFloat()
            zeroMean[i] = v
            sq += v * v
        }
        norm = sqrt(sq)
        if (norm < 1e-6) throw ConfigException("Modèle '$name' uniforme : impossible à reconnaître")
    }
}

data class Match(val score: Double, val x: Int, val y: Int)

/** Redimensionnement bilinéaire (préparation des modèles à une autre échelle). */
fun GrayImage.resized(factor: Double): GrayImage {
    if (factor == 1.0) return this
    val nw = (width * factor).toInt().coerceAtLeast(1)
    val nh = (height * factor).toInt().coerceAtLeast(1)
    val out = ByteArray(nw * nh)
    for (y in 0 until nh) {
        val sy = ((y + 0.5) / factor - 0.5).coerceIn(0.0, (height - 1).toDouble())
        val y0 = sy.toInt()
        val y1 = minOf(y0 + 1, height - 1)
        val fy = sy - y0
        for (x in 0 until nw) {
            val sx = ((x + 0.5) / factor - 0.5).coerceIn(0.0, (width - 1).toDouble())
            val x0 = sx.toInt()
            val x1 = minOf(x0 + 1, width - 1)
            val fx = sx - x0
            val v = (this[x0, y0] * (1 - fx) + this[x1, y0] * fx) * (1 - fy) + (this[x0, y1] * (1 - fx) + this[x1, y1] * fx) * fy
            out[y * nw + x] = v.toInt().coerceIn(0, 255).toByte()
        }
    }
    return GrayImage(nw, nh, out)
}

/**
 * Modèle binaire : seuls les pixels clairs (≥ [brightness]) comptent. Les icônes et textes de HUD sont blancs ;
 * le décor derrière change sans cesse et fausse une corrélation classique.
 */
class BrightTemplate(image: GrayImage, val brightness: Int, val name: String, val tolerance: Int = 0) {
    val width = image.width
    val height = image.height
    /** Positions (dx, dy) des pixels allumés, à plat. */
    val on: IntArray

    init {
        val points = mutableListOf<Int>()
        for (y in 0 until height) for (x in 0 until width) if (image[x, y] >= brightness) points += x or (y shl 16)
        if (points.size < 8) throw ConfigException("Modèle '$name' : trop peu de pixels ≥ $brightness pour une détection fiable")
        on = points.toIntArray()
    }
}

object BrightMatcher {
    /** Score de Dice entre pixels clairs du modèle et de la zone : 1 = formes identiques, pénalise le décor clair. */
    fun match(roi: GrayImage, tpl: BrightTemplate): Match {
        val w = tpl.width
        val h = tpl.height
        if (roi.width < w || roi.height < h) return Match(0.0, 0, 0)
        val bin = BooleanArray(roi.width * roi.height) { (roi.pixels[it].toInt() and 0xFF) >= tpl.brightness }
        // Tolérance : un pixel du modèle compte s'il y a un pixel clair à moins de `tolerance` pixels (traits fins, décalage d'arrondi).
        val near = if (tpl.tolerance <= 0) bin else dilate(bin, roi.width, roi.height, tpl.tolerance)
        val iw = roi.width + 1
        val integral = IntArray(iw * (roi.height + 1))
        for (y in 0 until roi.height) {
            var row = 0
            for (x in 0 until roi.width) {
                if (bin[y * roi.width + x]) row++
                integral[(y + 1) * iw + x + 1] = integral[y * iw + x + 1] + row
            }
        }
        val fg = tpl.on.size
        var best = Match(0.0, 0, 0)
        for (oy in 0..roi.height - h) {
            for (ox in 0..roi.width - w) {
                val lit = integral[(oy + h) * iw + ox + w] - integral[oy * iw + ox + w] - integral[(oy + h) * iw + ox] + integral[oy * iw + ox]
                // Borne supérieure du Dice : inutile de compter l'intersection si elle ne peut pas battre le meilleur.
                if (2.0 * minOf(lit, fg) / (fg + lit) <= best.score) continue
                var inter = 0
                for (p in tpl.on) {
                    if (near[(oy + (p shr 16)) * roi.width + ox + (p and 0xFFFF)]) inter++
                }
                val dice = (2.0 * inter / (fg + lit)).coerceAtMost(1.0)
                if (dice > best.score) best = Match(dice, ox, oy)
            }
        }
        return best
    }

    private fun dilate(bin: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val horizontal = BooleanArray(bin.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!bin[y * w + x]) continue
                for (dx in maxOf(0, x - r)..minOf(w - 1, x + r)) horizontal[y * w + dx] = true
            }
        }
        val out = BooleanArray(bin.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!horizontal[y * w + x]) continue
                for (dy in maxOf(0, y - r)..minOf(h - 1, y + r)) out[dy * w + x] = true
            }
        }
        return out
    }
}

/**
 * Corrélation croisée normalisée (équivalent de TM_CCOEFF_NORMED d'OpenCV) sur une petite zone de recherche.
 * Les éléments de HUD ont une position fixe : la zone dépasse le modèle de quelques pixels seulement, ce qui garde
 * le calcul rapide en Kotlin pur (pas de dépendance native).
 */
object TemplateMatcher {
    fun match(roi: GrayImage, tpl: PreparedTemplate): Match {
        val w = tpl.width
        val h = tpl.height
        require(roi.width >= w && roi.height >= h) { "zone ${roi.width}x${roi.height} plus petite que le modèle ${w}x$h" }
        val iw = roi.width + 1
        val sum = DoubleArray(iw * (roi.height + 1))
        val sq = DoubleArray(iw * (roi.height + 1))
        for (y in 0 until roi.height) {
            var rowSum = 0.0
            var rowSq = 0.0
            for (x in 0 until roi.width) {
                val v = roi[x, y].toDouble()
                rowSum += v
                rowSq += v * v
                sum[(y + 1) * iw + x + 1] = sum[y * iw + x + 1] + rowSum
                sq[(y + 1) * iw + x + 1] = sq[y * iw + x + 1] + rowSq
            }
        }
        val n = (w * h).toDouble()
        val pixels = roi.pixels
        val zm = tpl.zeroMean
        var best = Match(-1.0, 0, 0)
        for (oy in 0..roi.height - h) {
            for (ox in 0..roi.width - w) {
                val a = oy * iw + ox
                val b = oy * iw + ox + w
                val c = (oy + h) * iw + ox
                val d = (oy + h) * iw + ox + w
                val s = sum[d] - sum[b] - sum[c] + sum[a]
                val s2 = sq[d] - sq[b] - sq[c] + sq[a]
                val variance = s2 - s * s / n
                if (variance <= 1e-6) continue
                var cross = 0.0
                for (ty in 0 until h) {
                    val rowStart = (oy + ty) * roi.width + ox
                    val tRow = ty * w
                    for (tx in 0 until w) {
                        cross += (pixels[rowStart + tx].toInt() and 0xFF) * zm[tRow + tx]
                    }
                }
                val score = cross / (sqrt(variance) * tpl.norm)
                if (score > best.score) best = Match(score, ox, oy)
            }
        }
        return best
    }

    private fun dilate(bin: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val horizontal = BooleanArray(bin.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!bin[y * w + x]) continue
                for (dx in maxOf(0, x - r)..minOf(w - 1, x + r)) horizontal[y * w + dx] = true
            }
        }
        val out = BooleanArray(bin.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!horizontal[y * w + x]) continue
                for (dy in maxOf(0, y - r)..minOf(h - 1, y + r)) out[dy * w + x] = true
            }
        }
        return out
    }
}
