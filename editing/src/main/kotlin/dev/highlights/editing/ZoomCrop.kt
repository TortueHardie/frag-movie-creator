package dev.highlights.editing

import java.util.Locale

/**
 * Zoom animé (punch-in, approche, secousse, flou radial) : `scale` à la taille [w]·z × [h]·z recalculée à chaque image
 * (`eval=frame`), puis recadrage à [w]×[h] autour d'un point fixe (le réticule, au centre par défaut).
 *
 * Le recadrage ne peut pas s'écrire `(iw-w)/2` : dans `crop`, `iw` et `ih` gardent la taille de la première image
 * reçue, alors que celle-ci change à chaque image. Mesuré (FFmpeg 9, carré au centre d'une image de 200 px zoomée
 * ×1,5) : le carré passait de x = 100 à 150 ; dans les montages, le zoom s'ancrait en haut à gauche et l'image glissait
 * vers la droite et le bas pendant le punch (chrono de VALORANT à x = 760 sur 1080 au lieu de 540 en 9:16). Le décalage
 * est donc recalculé à partir de la même expression de zoom que la taille.
 */
object ZoomCrop {
    /** Taille zoomée, comme dans le `scale` : paire, arrondie vers le bas. */
    fun scaled(size: Int, zoom: String) = "trunc($size*$zoom/2)*2"

    /**
     * `scale` animé suivi du recadrage qui garde fixe le point ([focusX], [focusY]) (fractions du cadre), décalé de
     * [dx]/[dy] pixels (expressions) dans la marge du zoom.
     */
    fun filters(
        w: Int,
        h: Int,
        zoom: String,
        flags: String,
        dx: String? = null,
        dy: String? = null,
        focusX: Double = 0.5,
        focusY: Double = 0.5,
    ): List<String> =
        listOf("scale=w='${scaled(w, zoom)}':h='${scaled(h, zoom)}':eval=frame:flags=$flags", crop(w, h, zoom, dx, dy, focusX, focusY))

    /**
     * Recadrage d'une image zoomée par [zoom] (même expression que son `scale`) : le point en ([focusX], [focusY]) du
     * cadre reste en place (un point à la fraction f du cadre passe à f × taille zoomée ; le recadrage retire la marge
     * f × (zoomée − cadre) avant lui), borné à l'image.
     */
    fun crop(w: Int, h: Int, zoom: String, dx: String? = null, dy: String? = null, focusX: Double = 0.5, focusY: Double = 0.5): String {
        val mx = "(${scaled(w, zoom)}-$w)"
        val my = "(${scaled(h, zoom)}-$h)"
        fun offset(margin: String, focus: Double, shift: String?): String {
            val base = if (focus == 0.5) "$margin/2" else "$margin*${String.format(Locale.ROOT, "%.4f", focus)}"
            return if (shift == null && focus == 0.5) base else "clip($base${shift?.let { "+$it" } ?: ""}\\,0\\,$margin)"
        }
        return "crop=$w:$h:x='${offset(mx, focusX, dx)}':y='${offset(my, focusY, dy)}'"
    }
}
