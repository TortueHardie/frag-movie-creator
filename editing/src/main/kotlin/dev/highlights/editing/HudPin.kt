package dev.highlights.editing

/**
 * HUD recollé (`vertical.hud` : bandeau VALORANT, killfeed WARDOGS…) gardé fixe par-dessus les effets qui bougent
 * l'image (punch-in, zoom d'approche, secousse, flou de mouvement).
 *
 * Le HUD est collé dans l'image 9:16 par [RenderCommandBuilder.videoChain], avant ralentis et effets : un zoom
 * l'agrandissait avec le reste, et le bandeau sortait du cadre à chaque punch (QA du 18/11, VALORANT). Plutôt que de
 * refaire la chronologie du plan (ralentis, gels) pour un calque à part, l'image d'avant les effets est dédoublée :
 * une copie reçoit les effets, l'autre fournit les rectangles du HUD, recollés aux mêmes places. Même chronologie, par
 * construction. Ce qui vient ensuite (couleurs du montage, flash, textes, compteur de kills) s'applique par-dessus.
 */
object HudPin {
    /**
     * Instructions du graphe : de [input], [pre] (sans effet de position, ex. `tpad`), puis [motion] sur l'image avec
     * les [targets] recollés fixes par-dessus. Renvoie les instructions et le label de sortie, ou null s'il n'y a rien
     * à garder fixe ([targets] ou [motion] vides) : l'appelant enchaîne alors ses filtres comme avant.
     */
    fun statements(input: String, pre: List<String>, motion: List<String>, targets: List<RenderCommandBuilder.CropBox>, prefix: String): Pair<List<String>, String>? {
        if (targets.isEmpty() || motion.isEmpty()) return null
        val lines = mutableListOf<String>()
        lines += "[$input]" + (pre + "split=2").joinToString(",") + "[${prefix}m][${prefix}f]"
        lines += "[${prefix}m]${motion.joinToString(",")}[${prefix}z]"
        val fixed = if (targets.size == 1) {
            listOf("${prefix}f")
        } else {
            lines += "[${prefix}f]split=${targets.size}" + targets.indices.joinToString("") { "[${prefix}f$it]" }
            targets.indices.map { "${prefix}f$it" }
        }
        var label = "${prefix}z"
        targets.forEachIndexed { k, box ->
            lines += "[${fixed[k]}]crop=${box.w}:${box.h}:${box.x}:${box.y}[${prefix}c$k]"
            val out = "${prefix}o$k"
            lines += "[$label][${prefix}c$k]overlay=x=${box.x}:y=${box.y}:shortest=1[$out]"
            label = out
        }
        return lines to label
    }
}
