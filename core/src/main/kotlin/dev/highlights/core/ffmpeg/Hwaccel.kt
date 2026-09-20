package dev.highlights.core.ffmpeg

/**
 * Décodage matériel demandé à FFmpeg (`-hwaccel`). La valeur par défaut « auto » laisse FFmpeg choisir ce que la
 * machine sait faire (d3d11va ou dxva2 sous Windows, videotoolbox sur Mac, vaapi sous Linux) et retomber en logiciel
 * sans erreur : elle marche sur n'importe quelle carte graphique, là où un nom imposé échoue sur les autres.
 */
object Hwaccel {
    const val AUTO = "auto"

    /** Valeur à passer à `-hwaccel`, ou null pour du décodage logiciel (« none », « software », vide). */
    fun resolve(configured: String?): String? = when (configured?.trim()?.lowercase()) {
        null, "", "none", "no", "software", "logiciel", "off", "false" -> null
        else -> configured.trim()
    }
}
