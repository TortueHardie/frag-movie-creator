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
    fun plan(session: Session, settings: EditSettings): EditPlan
}

object DefaultEditPlanner : EditPlanner {
    override fun plan(session: Session, settings: EditSettings): EditPlan {
        val enabled = session.highlights.filter { it.enabled }
        if (enabled.isEmpty()) throw HighlightsException("Aucun segment activé à exporter")

        val ordered = when (settings.order) {
            ClipOrder.CHRONOLOGICAL -> enabled.sortedBy { it.range.start }
            ClipOrder.SCORE -> enabled.sortedByDescending { it.score }
        }
        // Une capture à 30 img/s n'a rien à gagner à sortir en 60, ni un enregistrement 720p à sortir en 1080p :
        // le montage ne dépasse ni la cadence ni la hauteur de la source (fichier plus lourd, rendu plus long, pour
        // des images dupliquées ou agrandies).
        val video = session.media.video
        val fps = video?.fps?.takeIf { it > 0 }?.let { minOf(settings.fps, ceil(it).toInt()) } ?: settings.fps
        val height = video?.height?.takeIf { it > 0 }?.let { minOf(settings.sourceHeight, it / 2 * 2) } ?: settings.sourceHeight
        val capped = settings.copy(fps = fps, sourceHeight = height.coerceAtLeast(2))

        val shortest = ordered.minOf { it.range.length }
        // Un fondu ne peut pas dépasser un tiers du clip le plus court, sinon xfade chevauche plusieurs clips.
        val fade = when {
            ordered.size < 2 || settings.transition.type == TransitionType.CUT -> Duration.ZERO
            else -> minOf(settings.transition.duration, shortest / 3)
        }
        return EditPlan(ordered.map { PlannedClip(it, session.media) }, capped, fade)
    }
}
