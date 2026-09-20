package dev.highlights.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** « 21:9 », « 16:9 », « 9:16 »… ou « 3440x1440 » si le ratio n'est pas courant. */
fun aspectLabel(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "?"
    val ratio = width.toDouble() / height
    val known = listOf("32:9" to 32.0 / 9, "21:9" to 21.0 / 9, "16:10" to 1.6, "16:9" to 16.0 / 9, "4:3" to 4.0 / 3, "9:16" to 9.0 / 16)
    return known.minBy { abs(it.second - ratio) }.takeIf { abs(it.second - ratio) < 0.06 }?.first ?: "${width}x$height"
}

/** Bord de l'écran auquel un élément d'interface est accroché. */
@Serializable
enum class RegionAnchor {
    /** Déduit de la position de la zone : bord le plus proche, ou centre si elle est centrée. */
    @SerialName("auto") AUTO,

    @SerialName("left") LEFT,

    @SerialName("center") CENTER,

    @SerialName("right") RIGHT,
}

/**
 * Conversion des zones d'interface d'un format d'écran à un autre.
 *
 * L'interface d'un jeu suit la hauteur de l'image : à HUD identique, un élément garde sa taille en pixels et sa
 * distance au bord auquel il est accroché, quelle que soit la largeur. Une zone mesurée sur un écran 21:9 ne peut donc
 * pas être appliquée telle quelle à du 16:9 : en coordonnées normalisées, ses largeurs et ses distances horizontales
 * représentent une autre part de l'image. Seule l'horizontale change ; la verticale est déjà proportionnelle à la hauteur.
 */
object ScreenGeometry {
    /** En deçà, la zone est tenue pour centrée. */
    private const val CENTER_TOLERANCE = 0.06

    /**
     * Ramène [region], mesurée sur une capture de ratio [referenceAspect], à une capture de ratio [targetAspect].
     * Les deux ratios égaux (ou un ancrage impossible à tenir) rendent la zone inchangée.
     */
    fun rescale(region: CropRegion, referenceAspect: Double, targetAspect: Double, anchor: RegionAnchor = RegionAnchor.AUTO): CropRegion {
        require(referenceAspect > 0 && targetAspect > 0) { "ratios attendus > 0 : $referenceAspect, $targetAspect" }
        val k = referenceAspect / targetAspect
        if (k in 0.999..1.001) return region

        val width = (region.width * k).coerceAtMost(1.0)
        val x = when (resolveAnchor(anchor, region)) {
            RegionAnchor.LEFT -> region.x * k
            RegionAnchor.RIGHT -> 1.0 - (1.0 - region.x - region.width) * k - width
            else -> 0.5 + (region.x + region.width / 2 - 0.5) * k - width / 2
        }
        return CropRegion(x.coerceIn(0.0, 1.0 - width), region.y, width, region.height)
    }

    /** Ancrage explicite, ou bord le plus proche du centre de la zone. */
    fun resolveAnchor(anchor: RegionAnchor, region: CropRegion): RegionAnchor {
        if (anchor != RegionAnchor.AUTO) return anchor
        val center = region.x + region.width / 2
        return when {
            abs(center - 0.5) <= CENTER_TOLERANCE -> RegionAnchor.CENTER
            center < 0.5 -> RegionAnchor.LEFT
            else -> RegionAnchor.RIGHT
        }
    }

    /**
     * Zone à utiliser sur une capture [width]x[height] pour une [region] mesurée sur une capture de [reference]
     * pixels. [reference] null (ou de même ratio) rend la zone inchangée.
     */
    fun forVideo(region: CropRegion, reference: FrameSize?, width: Int, height: Int, anchor: RegionAnchor = RegionAnchor.AUTO): CropRegion {
        if (reference == null || width <= 0 || height <= 0) return region
        return rescale(region, reference.width.toDouble() / reference.height, width.toDouble() / height, anchor)
    }
}
