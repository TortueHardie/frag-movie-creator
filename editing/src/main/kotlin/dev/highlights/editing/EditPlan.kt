package dev.highlights.editing

import dev.highlights.core.HighlightsException
import dev.highlights.core.model.ClipOrder
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.TransitionType
import dev.highlights.core.session.Session
import kotlin.math.ceil
import kotlin.time.Duration

data class PlannedClip(val highlight: Highlight, val media: MediaInfo) {
    val range get() = highlight.range
}

data class EditPlan(
    val clips: List<PlannedClip>,
    val settings: EditSettings,
    /** Durée de fondu effectivement appliquée (0 = coupe franche). */
    val fade: Duration,
) {
    val outputDuration: Duration
        get() = clips.fold(Duration.ZERO) { acc, c -> acc + c.range.length } - fade * (clips.size - 1).coerceAtLeast(0)
}

fun interface EditPlanner {
    /** Montage des segments cochés de [sessions], une session par capture. */
    fun plan(sessions: List<Session>, settings: EditSettings): EditPlan

    fun plan(session: Session, settings: EditSettings): EditPlan = plan(listOf(session), settings)
}

object DefaultEditPlanner : EditPlanner {
    override fun plan(sessions: List<Session>, settings: EditSettings): EditPlan {
        val enabled = sessions.flatMap { s -> s.highlights.filter { it.enabled }.map { PlannedClip(it, s.media) } }
        if (enabled.isEmpty()) throw HighlightsException("Aucun segment activé à exporter")

        // Chronologique : les parties dans l'ordre où elles ont été jouées, puis les moments dans l'ordre de chaque
        // partie. Le montage raconte la soirée telle qu'elle s'est déroulée, quel que soit l'ordre des fichiers.
        val ordered = when (settings.order) {
            ClipOrder.CHRONOLOGICAL -> enabled.sortedWith(compareBy(MediaInfo.RECORDING_ORDER) { c: PlannedClip -> c.media }.thenBy { it.range.start })
            ClipOrder.SCORE -> enabled.sortedByDescending { it.highlight.score }
        }
        // Une capture à 30 img/s n'a rien à gagner à sortir en 60, ni un enregistrement 720p à sortir en 1080p :
        // le montage ne dépasse ni la cadence ni la hauteur de la plus grande des sources (fichier plus lourd, rendu
        // plus long, pour des images dupliquées ou agrandies).
        val videos = ordered.map { it.media }.distinctBy { it.path }.mapNotNull { it.video }
        val fps = videos.mapNotNull { v -> v.fps.takeIf { it > 0 }?.let { ceil(it).toInt() } }.maxOrNull()?.let { minOf(settings.fps, it) } ?: settings.fps
        val height = videos.mapNotNull { v -> v.height.takeIf { it > 0 }?.let { it / 2 * 2 } }.maxOrNull()?.let { minOf(settings.sourceHeight, it) } ?: settings.sourceHeight
        val capped = settings.copy(fps = fps, sourceHeight = height.coerceAtLeast(2))

        val shortest = ordered.minOf { it.range.length }
        // Un fondu ne peut pas dépasser un tiers du clip le plus court, sinon xfade chevauche plusieurs clips.
        val fade = when {
            ordered.size < 2 || settings.transition.type == TransitionType.CUT -> Duration.ZERO
            else -> minOf(settings.transition.duration, shortest / 3)
        }
        return EditPlan(ordered, capped, fade)
    }
}
