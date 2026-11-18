package dev.highlights.pipeline

import dev.highlights.core.InputException
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.TimeRange
import dev.highlights.core.session.Session
import dev.highlights.editing.DefaultEditPlanner
import dev.highlights.editing.story.StoryPlanner
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Options du clip « meilleur moment » ([HighlightPipeline.clip]) ; null = réglage du profil ou choix automatique. */
data class ClipOptions(
    /** Identifiant du moment (h003…) ; null : le meilleur de la session ([MomentClips.best]). */
    val moment: String? = null,
    /** Plateforme visée : zone sûre, volume, débit, et sa durée maximale abaisse celle du clip. */
    val platform: String? = null,
    val outputDir: Path? = null,
    /** Miniature YouTube du clip, dans cet habillage ; null : aucune. */
    val thumbnail: dev.highlights.montage.ThumbnailStyle? = null,
)

/** Choix du moment d'un clip et de la partie de la capture qu'il montre (sans rendu : la planification ne coûte rien). */
object MomentClips {

    /**
     * Moment du clip : [id] s'il est donné, sinon le meilleur de la session, celui qui a le plus de kills puis le
     * meilleur score. Les moments décochés ne sont pris qu'à défaut : décocher, c'est déjà dire « pas celui-là ».
     */
    fun pick(session: Session, id: String?): Highlight {
        if (id != null) {
            return session.highlights.find { it.id == id }
                ?: throw InputException(Message("error.clip.unknownMoment", "id" to id, "known" to session.highlights.joinToString { it.id }))
        }
        val pool = session.highlights.filter { it.enabled }.ifEmpty { session.highlights }
        return pool.maxWithOrNull(compareBy<Highlight> { it.events["kill"] ?: 0 }.thenBy { it.score })
            ?: throw InputException(Message("error.clip.noMoment"))
    }

    /**
     * Session réduite au seul [moment], sa fenêtre élargie autour du pic jusqu'à ce que le montage story dure entre
     * [EditSettings.clip] `minDuration` et [maxDuration] (jump cuts retirés), au plus près de `target`. La durée du
     * montage croît avec la fenêtre (une fenêtre plus longue ne retire jamais un plan) : une recherche par dichotomie
     * sur la longueur de la fenêtre suffit, chaque essai n'étant qu'une planification. La fenêtre contient le moment
     * d'origine et reste dans la capture : une capture trop courte donne un clip plus court.
     *
     * Le clip final ne dépasse jamais le plafond ([fit]) : un moment long, ou une capture courte dont la fenêtre ne
     * peut plus grandir, avec l'accroche en plus, faisaient 31 s pour un plafond de 30 s (QA finale), et l'export
     * échouait. La fenêtre rétrécit alors autour du pic, quitte à couper le début ou la fin du moment d'origine.
     */
    fun window(session: Session, moment: Highlight, settings: EditSettings, maxDuration: Duration): Session {
        val clip = settings.clip
        val max = minOf(clip.maxDuration, maxDuration)
        // Marge sous le plafond : la vidéo rendue est arrondie à l'image et fondue en sortie, elle peut dépasser la
        // durée planifiée de quelques centièmes.
        val limit = (max - MARGIN).coerceAtLeast(max / 2)
        val target = clip.target.coerceAtMost(limit)
        val media = session.media.duration
        fun window(length: Duration, keepMoment: Boolean): Session {
            val before = length - (length * (1 - clip.lead)).coerceAtMost(clip.after)
            var start = (moment.peak - before).coerceAtLeast(Duration.ZERO)
            val end = (start + length).coerceAtMost(media)
            start = (end - length).coerceAtLeast(Duration.ZERO)
            val range = if (keepMoment) TimeRange(minOf(start, moment.range.start), maxOf(end, moment.range.end)) else TimeRange(start, end)
            return session.copy(highlights = listOf(moment.copy(range = range, enabled = true, order = null)))
        }
        fun at(length: Duration) = window(length, keepMoment = true)
        fun duration(s: Session): Duration = StoryPlanner.plan(DefaultEditPlanner.plan(s, settings), listOf(s)).outputDuration

        /**
         * [chosen] s'il tient sous la limite ; sinon la plus longue fenêtre autour du pic qui y tient (le moment
         * d'origine peut alors être rogné). Si même la plus courte ne tient pas, la plus courte : l'export le dira.
         */
        fun fit(chosen: Session): Session {
            if (duration(chosen) <= limit) return chosen
            var lo = MIN_WINDOW
            var hi = chosen.highlights.single().range.length
            var fitted = window(lo, keepMoment = false)
            repeat(12) {
                if (hi - lo < 50.milliseconds) return@repeat
                val mid = (lo + hi) / 2
                val s = window(mid, keepMoment = false)
                if (duration(s) <= limit) {
                    fitted = s
                    lo = mid
                } else {
                    hi = mid
                }
            }
            return fitted
        }

        // Bornes de la fenêtre : du moment seul à dix fois le plafond. Avant un kill WARDOGS, l'attente coupée par les
        // jump cuts fait presque tout : 2 minutes de partie ne donnaient que 11,6 s de clip.
        var low = moment.range.length.coerceAtMost(target)
        var high = (max * 10).coerceAtMost(media)
        if (low >= high) return fit(at(high))
        var best = at(low)
        repeat(12) {
            val mid = (low + high) / 2
            val s = at(mid)
            val d = duration(s)
            if (d > limit) high = mid else {
                best = s
                if (d >= target) return s
                low = mid
            }
        }
        return fit(best)
    }

    /** Marge sous le plafond pour les arrondis du rendu (voir [window]). */
    private val MARGIN = 250.milliseconds

    /** Plus courte fenêtre essayée autour du pic : de quoi voir le kill. */
    private val MIN_WINDOW = 2.seconds

    /** Réglages du clip : style story sur un seul moment (accroche comprise), vertical seul. */
    fun settings(edit: EditSettings): EditSettings = edit.copy(
        style = EditStyle.STORY,
        formats = listOf(OutputFormat.VERTICAL),
        story = edit.story.copy(coldOpen = edit.story.coldOpen.copy(minMoments = 1)),
    )
}
