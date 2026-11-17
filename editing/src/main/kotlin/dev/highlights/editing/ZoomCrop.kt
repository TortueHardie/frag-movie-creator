package dev.highlights.editing

/**
 * Zoom animé (punch-in, approche, secousse, flou radial) : `scale` à la taille [w]·z × [h]·z recalculée à chaque image
 * (`eval=frame`), puis recadrage à [w]×[h] au centre.
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

    /** `scale` animé suivi du recadrage centré, décalé de [dx]/[dy] pixels (expressions) dans la marge du zoom. */
    fun filters(w: Int, h: Int, zoom: String, flags: String, dx: String? = null, dy: String? = null): List<String> =
        listOf("scale=w='${scaled(w, zoom)}':h='${scaled(h, zoom)}':eval=frame:flags=$flags", crop(w, h, zoom, dx, dy))

    /** Recadrage centré d'une image zoomée par [zoom] (même expression que son `scale`). */
    fun crop(w: Int, h: Int, zoom: String, dx: String? = null, dy: String? = null): String {
        val mx = "(${scaled(w, zoom)}-$w)"
        val my = "(${scaled(h, zoom)}-$h)"
        val x = if (dx == null) "$mx/2" else "clip($mx/2+$dx\\,0\\,$mx)"
        val y = if (dy == null) "$my/2" else "clip($my/2+$dy\\,0\\,$my)"
        return "crop=$w:$h:x='$x':y='$y'"
    }
}
