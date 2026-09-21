package dev.highlights.editing.story

import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.EventLabels
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.JumpCutSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.session.Session
import dev.highlights.editing.EditPlan
import dev.highlights.editing.PlannedClip
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Rôle d'un plan dans le récit. */
enum class ShotRole {
    /** Extrait du meilleur moment placé en ouverture. */
    COLD_OPEN,

    /** Premier plan d'un moment : il reçoit le flash et le whoosh de la transition. */
    OPENING,

    /** Plan suivant un jump cut, au sein d'un même moment. */
    JUMP,
}

/**
 * Un plan du montage : un extrait continu de la source. Les instants des effets ([punchIns], [shakes], [drops],
 * [voice]) sont relatifs au début du plan, en sortie (le montage « story » ne change pas la vitesse).
 */
data class StoryShot(
    val media: MediaInfo,
    val range: TimeRange,
    val highlight: Highlight,
    val role: ShotRole,
    /** Cadrage fixe du plan (1 = plein cadre), alterné d'un jump cut à l'autre. */
    val zoom: Double = 1.0,
    val punchIns: List<TimeRange> = emptyList(),
    val shakes: List<Duration> = emptyList(),
    /** Pics du moment : la musique de fond s'y tait un instant. */
    val drops: List<Duration> = emptyList(),
    /** Voix et réactions : la musique de fond passe dessous. */
    val voice: List<TimeRange> = emptyList(),
    /** Sous-titres de la voix visibles dans le plan. */
    val captions: List<Caption> = emptyList(),
    /** Libellés de séries (« DOUBLÉ »…) : instant d'apparition dans le plan et texte. */
    val labels: List<Pair<Duration, String>> = emptyList(),
) {
    val length: Duration get() = range.length
}

data class StoryPlan(
    val shots: List<StoryShot>,
    /** Moments montés, dans l'ordre du récit (sans l'accroche). */
    val moments: List<PlannedClip>,
    val settings: EditSettings,
) {
    init {
        require(shots.isNotEmpty()) { "montage vide" }
    }

    val outputDuration: Duration get() = shots.fold(Duration.ZERO) { acc, s -> acc + s.length }

    /** Début de chaque plan dans le montage. */
    fun offsets(): List<Duration> = shots.runningFold(Duration.ZERO) { acc, s -> acc + s.length }.dropLast(1)

    /** Temps retiré par les jump cuts, sur l'ensemble des moments. */
    val removed: Duration
        get() = moments.fold(Duration.ZERO) { acc, m -> acc + m.range.length } -
            shots.filter { it.role != ShotRole.COLD_OPEN }.fold(Duration.ZERO) { acc, s -> acc + s.length }
}

/**
 * Découpe les moments retenus en plans, à la manière d'un monteur YouTube :
 * 1. accroche : quelques secondes du meilleur moment en ouverture ;
 * 2. jump cuts : dans chaque moment, les passages creux (ni voix, ni événement, score bas) sont retirés ;
 * 3. cadrage alterné d'un plan à l'autre, pour que le saut d'un jump cut se lise comme un changement de caméra ;
 * 4. punch-in sur les réactions, secousse sur les impacts, silence de la musique sur le pic.
 */
object StoryPlanner {
    /** Deux secousses plus proches que ça n'en font qu'une. */
    private val SHAKE_SPACING = 300.milliseconds

    fun plan(plan: EditPlan, sessions: List<Session>): StoryPlan {
        val settings = plan.settings
        val story = settings.story
        val timelines = sessions.associate { it.media.path to it.timeline }
        val moments = plan.clips

        val shots = mutableListOf<StoryShot>()
        val coldOpen = story.coldOpen
        if (coldOpen.enabled && moments.size >= coldOpen.minMoments) {
            val best = moments.maxBy { it.highlight.score }
            val range = teaser(best, coldOpen.length, coldOpen.beforePeak)
            shots += decorate(best, range, ShotRole.COLD_OPEN, 1.0, timelines[best.media.path], settings)
        }
        for (clip in moments) {
            val timeline = timelines[clip.media.path]
            val pieces = if (timeline == null) listOf(clip.range) else liveRanges(clip.range, clip.highlight, timeline, story.jumpCuts)
            pieces.forEachIndexed { k, range ->
                val role = if (k == 0) ShotRole.OPENING else ShotRole.JUMP
                val zoom = if (k % 2 == 1) story.jumpCuts.alternateZoom else 1.0
                shots += decorate(clip, range, role, zoom, timeline, settings)
            }
        }
        return StoryPlan(shots, moments, settings)
    }

    /**
     * Pose les sous-titres (instants dans la source, par capture) sur les plans qui les montrent. L'accroche en garde
     * aussi : c'est souvent la réplique qui accroche.
     */
    fun withCaptions(plan: StoryPlan, captions: Map<Path, List<Caption>>): StoryPlan =
        plan.copy(shots = plan.shots.map { shot -> shot.copy(captions = Captions.inShot(captions[shot.media.path].orEmpty(), shot.range)) })

    /** Extrait de l'accroche : [length] autour du pic, [beforePeak] de mise en place, borné au moment. */
    internal fun teaser(clip: PlannedClip, length: Duration, beforePeak: Double): TimeRange =
        TimeRange.placed(clip.highlight.peak - length * beforePeak, minOf(length, clip.range.length), clip.range)

    /**
     * Parties d'un moment qui méritent d'être montrées. Une fenêtre est creuse quand son score, rapporté au pic du
     * moment, passe sous [JumpCutSettings.deadScore] ; une voix, un rire ou un événement proche la retiennent quoi
     * qu'il arrive. Un creux d'au moins [JumpCutSettings.minGap] est retiré, à [JumpCutSettings.breath] près de
     * chaque côté ; au début et à la fin du moment, il est rogné. Un plan trop court est recollé à son voisin.
     */
    internal fun liveRanges(range: TimeRange, highlight: Highlight, timeline: ScoredTimeline, settings: JumpCutSettings): List<TimeRange> {
        if (!settings.enabled) return listOf(range)
        val grid = timeline.grid
        if (grid.count == 0) return listOf(range)
        val hop = grid.hop

        // Score de chaque tranche d'une période : la fenêtre la plus forte qui la couvre.
        val first = (range.start / hop).toInt().coerceAtLeast(0)
        val last = (range.end / hop).toInt()
        val slots = (first..last).map { j ->
            val slot = TimeRange(hop * j, hop * (j + 1))
            val mid = slot.start + hop / 2
            slot to (grid.indicesCovering(mid).maxOfOrNull { timeline.total[it] } ?: 0.0)
        }.filter { (slot, _) -> slot.end > range.start && slot.start < range.end }
        val peak = slots.maxOfOrNull { it.second } ?: 0.0
        if (peak <= 0.0) return listOf(range)

        // Ce qui retient une tranche : voix et réactions, événements et pic (avec une marge autour).
        val guard = settings.eventGuard
        val keepers = timeline.segments.map { it.range } +
            timeline.events.map { TimeRange(it.at - guard, it.at + guard) } +
            TimeRange(highlight.peak - guard, highlight.peak + guard)

        val dead = mutableListOf<TimeRange>()
        for ((slot, score) in slots) {
            if (score / peak >= settings.deadScore) continue
            val clipped = TimeRange(maxOf(slot.start, range.start), minOf(slot.end, range.end))
            val last = dead.lastOrNull()
            if (last != null && last.end >= clipped.start) dead[dead.lastIndex] = last.union(clipped) else dead += clipped
        }
        val removable = dead.flatMap { subtract(it, keepers) }.filter { it.length >= settings.minGap }

        val cuts = removable.mapNotNull { d ->
            val start = if (d.start <= range.start) range.start else d.start + settings.breath
            val end = if (d.end >= range.end) range.end else d.end - settings.breath
            if (end > start) TimeRange(start, end) else null
        }
        val pieces = subtract(range, cuts).toMutableList()
        if (pieces.isEmpty()) return listOf(range)

        // Plan trop court : recollé au voisin le plus proche (le creux qui les sépare revient).
        while (pieces.size > 1) {
            val i = pieces.indices.firstOrNull { pieces[it].length < settings.minShot } ?: break
            val gapBefore = if (i > 0) pieces[i].start - pieces[i - 1].end else Duration.INFINITE
            val gapAfter = if (i < pieces.lastIndex) pieces[i + 1].start - pieces[i].end else Duration.INFINITE
            if (gapBefore <= gapAfter) {
                pieces[i - 1] = pieces[i - 1].union(pieces[i])
                pieces.removeAt(i)
            } else {
                pieces[i] = pieces[i].union(pieces[i + 1])
                pieces.removeAt(i + 1)
            }
        }
        val kept = pieces.fold(Duration.ZERO) { acc, p -> acc + p.length }
        return if (kept < settings.minShot) listOf(range) else pieces
    }

    /** Effets du plan [range] : punch-in sur les réactions, secousses sur les impacts, voix et pics pour la musique. */
    private fun decorate(clip: PlannedClip, range: TimeRange, role: ShotRole, zoom: Double, timeline: ScoredTimeline?, settings: EditSettings): StoryShot {
        val story = settings.story
        fun relative(r: TimeRange): TimeRange? {
            val s = maxOf(r.start, range.start)
            val e = minOf(r.end, range.end)
            return if (e > s) TimeRange(s - range.start, e - range.start) else null
        }
        val segments = timeline?.segments.orEmpty()
        val punch = story.punchIn
        val punchIns = if (punch.enabled && punch.amount > 0) {
            merge(segments.filter { it.kind in punch.on && it.range.length >= punch.minLength }.mapNotNull { relative(it.range) })
        } else {
            emptyList()
        }

        val shake = story.shake
        val impacts = if (shake.enabled && shake.amplitude > 0) {
            val events = timeline?.events.orEmpty().filter { it.kind in shake.events }.map { it.at }
            (events + listOfNotNull(clip.highlight.peak.takeIf { shake.onPeak }))
                .filter { it >= range.start && it < range.end - shake.duration / 2 }
                .sorted()
        } else {
            emptyList()
        }
        val shakes = mutableListOf<Duration>()
        for (t in impacts) if (shakes.isEmpty() || t - range.start - shakes.last() >= SHAKE_SPACING) shakes += t - range.start

        val drops = listOf(clip.highlight.peak).filter { it in range }.map { it - range.start }
        val voice = merge(segments.mapNotNull { relative(it.range) })
        val labels = streakLabels(timeline, story.labels)
            .filter { (at, _) -> at >= range.start && at < range.end - story.labels.duration / 2 }
            .map { (at, text) -> at - range.start to text }
        return StoryShot(clip.media, range, clip.highlight, role, zoom, punchIns, shakes, drops, voice, labels = labels)
    }

    /**
     * Libellé de chaque kill qui prolonge une série (kills espacés de moins de [EventLabels.gap]) : « DOUBLÉ » au 2e,
     * « TRIPLÉ » au 3e… Compté sur toute la partie, pas seulement sur ce que le montage montre : un triplé reste un
     * triplé même si son premier kill a été coupé.
     */
    internal fun streakLabels(timeline: ScoredTimeline?, settings: EventLabels): List<Pair<Duration, String>> {
        if (!settings.enabled || settings.multiKill.isEmpty() || timeline == null) return emptyList()
        val kills = timeline.events.filter { it.kind == settings.event }.map { it.at }.sorted()
        val labels = mutableListOf<Pair<Duration, String>>()
        var streak = 0
        kills.forEachIndexed { i, at ->
            streak = if (i > 0 && at - kills[i - 1] <= settings.gap) streak + 1 else 1
            if (streak >= 2) labels += at to settings.multiKill[minOf(streak - 2, settings.multiKill.lastIndex)]
        }
        return labels
    }

    /** [range] privé des intervalles [holes], dans l'ordre. */
    internal fun subtract(range: TimeRange, holes: List<TimeRange>): List<TimeRange> {
        var pieces = listOf(range)
        for (h in holes.sortedBy { it.start }) {
            pieces = pieces.flatMap { p ->
                if (h.end <= p.start || h.start >= p.end) {
                    listOf(p)
                } else {
                    listOfNotNull(
                        if (h.start > p.start) TimeRange(p.start, h.start) else null,
                        if (h.end < p.end) TimeRange(h.end, p.end) else null,
                    )
                }
            }
        }
        return pieces
    }

    private fun merge(ranges: List<TimeRange>): List<TimeRange> {
        val out = mutableListOf<TimeRange>()
        for (r in ranges.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && r.start <= last.end) out[out.lastIndex] = last.union(r) else out += r
        }
        return out
    }
}
