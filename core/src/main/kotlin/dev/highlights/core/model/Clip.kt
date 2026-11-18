package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

/**
 * Clip « meilleur moment » : un seul moment en vidéo verticale 9:16 de [minDuration] à [maxDuration], prête pour
 * TikTok ou Shorts, sans musique imposée (action d'origine, son du jeu, voix), montée en style story (accroche, jump
 * cuts dans l'attente, punch-in et secousse sur le kill, sous-titres si le modèle est là). Un moment détecté ne dure que
 * 5 à 7 s (WARDOGS) : le clip prend la partie autour, [lead] de la fenêtre avant le pic, et l'élargit ou la resserre
 * jusqu'à ce que le montage, jump cuts retirés, dure entre les deux bornes ; [target] d'abord. La plateforme visée peut
 * abaisser [maxDuration].
 */
@Serializable
data class ClipSettings(
    val minDuration: SerialDuration = 15.seconds,
    val maxDuration: SerialDuration = 30.seconds,
    val target: SerialDuration = 22.seconds,
    /** Part de la fenêtre avant le pic du moment : la mise en place compte plus que la suite. */
    val lead: Double = 0.65,
    /**
     * Au plus ça après le pic, le reste de la fenêtre est pris avant : sur une fenêtre de 90 s (beaucoup d'attente
     * coupée), 35 % après le kill faisaient 32 s, et le clip WARDOGS finissait sur l'écran d'inventaire (corps fouillé).
     */
    val after: SerialDuration = 6.seconds,
) {
    init {
        require(minDuration.isPositive() && minDuration <= target && target <= maxDuration) { "edit.clip : minDuration ≤ target ≤ maxDuration attendus" }
        require(lead in 0.0..1.0) { "edit.clip.lead doit être entre 0 et 1" }
        require(after.isPositive()) { "edit.clip.after doit être positif" }
    }
}
