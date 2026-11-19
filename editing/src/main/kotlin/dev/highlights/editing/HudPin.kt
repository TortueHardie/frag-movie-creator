package dev.highlights.editing

/**
 * HUD recollé (`vertical.hud` : bandeau, bloc vie/capacités/munitions VALORANT, killfeed WARDOGS…) gardé fixe par-dessus
 * les effets qui bougent l'image (punch-in, zoom d'approche, secousse, flou de mouvement).
 *
 * [RenderCommandBuilder.videoChain] (`pinHud`) rend une trame empilée : l'image 9:16 sans HUD ([width] × [height]),
 * puis dessous chaque morceau du HUD à sa taille finale, calé à gauche, l'un sous l'autre. Ralentis, gels et coupes du
 * plan s'appliquent à la trame entière : le HUD suit la même chronologie que l'image, par construction. Ici, l'image
 * est découpée et reçoit les effets, puis les morceaux du HUD sont recollés à leur place.
 *
 * Premier essai (18/11) : HUD collé dans l'image avant le zoom, puis recopié fixe par-dessus. Le bandeau du haut
 * semblait fixe, mais sa copie zoomée sortait juste par le haut ; le bloc du bas, lui, apparaissait en double sous sa
 * copie fixe. Le HUD ne doit donc jamais être dans l'image qui zoome.
 */
object HudPin {
    /**
     * Instructions du graphe : de la trame empilée [input], [pre] (sans effet de position, ex. `tpad`), puis [motion]
     * sur l'image ([width] × [height], en haut de la trame), avec les [targets] recollés fixes par-dessus. Renvoie les
     * instructions et le label de sortie (image [width] × [height]), ou null sans HUD ([targets] vide) : la trame n'est
     * alors pas empilée et l'appelant enchaîne ses filtres comme avant.
     */
    fun statements(
        input: String,
        pre: List<String>,
        motion: List<String>,
        targets: List<RenderCommandBuilder.CropBox>,
        width: Int,
        height: Int,
        prefix: String,
    ): Pair<List<String>, String>? {
        if (targets.isEmpty()) return null
        val lines = mutableListOf<String>()
        lines += "[$input]" + (pre + "split=${targets.size + 1}").joinToString(",") + "[${prefix}m]" + targets.indices.joinToString("") { "[${prefix}f$it]" }
        lines += "[${prefix}m]" + (listOf("crop=$width:$height:0:0") + motion).joinToString(",") + "[${prefix}z]"
        var label = "${prefix}z"
        var y = height
        targets.forEachIndexed { k, box ->
            lines += "[${prefix}f$k]crop=${box.w}:${box.h}:0:$y[${prefix}c$k]"
            y += box.h
            val out = "${prefix}o$k"
            lines += "[$label][${prefix}c$k]overlay=x=${box.x}:y=${box.y}:shortest=1[$out]"
            label = out
        }
        return lines to label
    }
}
