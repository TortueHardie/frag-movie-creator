package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/**
 * Marges de l'image recouvertes par l'interface d'une application (parts de la hauteur et de la largeur) : pseudo,
 * légende et musique en bas, boutons à droite, onglets en haut. Un texte du montage n'y a pas sa place : il serait caché.
 */
@Serializable
data class SafeArea(val top: Double = 0.0, val bottom: Double = 0.0, val left: Double = 0.0, val right: Double = 0.0) {
    init {
        require(listOf(top, bottom, left, right).all { it in 0.0..0.45 }) { "safeArea : marges entre 0 et 0,45" }
    }

    /** Largeur utile (part de la largeur). */
    val width: Double get() = 1.0 - left - right

    /**
     * Centre vertical ([y], part de la hauteur) ramené dans la zone sûre, pour un texte haut de [textHeight] (part de
     * la hauteur) : il ne déborde ni sous les onglets du haut, ni sur la légende du bas.
     */
    fun centerY(y: Double, textHeight: Double): Double {
        val low = top + textHeight / 2
        val high = 1.0 - bottom - textHeight / 2
        return if (low > high) (top + 1.0 - bottom) / 2 else y.coerceIn(low, high)
    }

    companion object {
        val NONE = SafeArea()
    }
}

/**
 * Ce qu'une plateforme attend d'une vidéo : son format, sa durée maximale, son volume (les plateformes ramènent
 * toutes les vidéos à un même niveau : une vidéo plus forte est baissée, plus faible elle paraît terne), un plafond
 * de débit et d'images par seconde, et la zone de l'image que son interface laisse visible. Les valeurs par défaut
 * ([DEFAULTS]) sont celles relevées sur les applications ; elles changent avec elles, d'où leur surcharge dans
 * app.yaml (`platforms:`).
 */
@Serializable
data class PlatformProfile(
    val name: String,
    val format: OutputFormat,
    /** Durée maximale acceptée ; null : pas de limite utile. */
    val maxDuration: SerialDuration? = null,
    val loudnessLufs: Double = -14.0,
    /** Plafond du débit vidéo (ex. 16M) : la plateforme réencode de toute façon, un fichier plus lourd n'y gagne rien. */
    val maxBitrate: String? = null,
    /** Images par seconde au plus ; null : celles du montage. */
    val fps: Int? = null,
    val safeArea: SafeArea = SafeArea.NONE,
) {
    init {
        require(loudnessLufs in -40.0..-5.0) { "platforms.$name.loudnessLufs doit être entre -40 et -5" }
        require(fps == null || fps in 15..120) { "platforms.$name.fps doit être entre 15 et 120" }
        require(maxBitrate == null || parseBitrate(maxBitrate) != null) { "platforms.$name.maxBitrate illisible : $maxBitrate (ex. 16M, 8000k)" }
    }

    /** Réglages d'export pour cette plateforme : format, volume, images par seconde, zone sûre et débit. */
    fun applyTo(edit: EditSettings): EditSettings = edit.copy(
        formats = listOf(format),
        loudnessLufs = loudnessLufs,
        fps = fps?.let { minOf(it, edit.fps) } ?: edit.fps,
        safeArea = safeArea,
        maxBitrate = maxBitrate,
    )

    companion object {
        /**
         * Plateformes connues. Zones sûres relevées sur l'interface des applications en 9:16 (onglets en haut, légende
         * et musique en bas, boutons à droite) ; durées maximales de mise en ligne depuis l'application.
         */
        val DEFAULTS: Map<String, PlatformProfile> = mapOf(
            "tiktok" to PlatformProfile(
                "TikTok", OutputFormat.VERTICAL, maxDuration = 10.minutes, maxBitrate = "16M", fps = 60,
                safeArea = SafeArea(top = 0.10, bottom = 0.22, left = 0.05, right = 0.14),
            ),
            "shorts" to PlatformProfile(
                "YouTube Shorts", OutputFormat.VERTICAL, maxDuration = 3.minutes, maxBitrate = "16M", fps = 60,
                safeArea = SafeArea(top = 0.08, bottom = 0.20, left = 0.04, right = 0.12),
            ),
            "reels" to PlatformProfile(
                "Instagram Reels", OutputFormat.VERTICAL, maxDuration = 3.minutes, maxBitrate = "16M", fps = 60,
                safeArea = SafeArea(top = 0.10, bottom = 0.22, left = 0.05, right = 0.12),
            ),
            "youtube" to PlatformProfile("YouTube", OutputFormat.SOURCE),
        )

        /** Débit en bits par seconde : « 16M », « 8000k », « 12000000 » ; null s'il est illisible. */
        fun parseBitrate(text: String): Long? {
            val t = text.trim().lowercase()
            val (number, factor) = when {
                t.endsWith("m") -> t.dropLast(1) to 1_000_000.0
                t.endsWith("k") -> t.dropLast(1) to 1_000.0
                else -> t to 1.0
            }
            return number.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * factor).toLong() }
        }
    }
}
