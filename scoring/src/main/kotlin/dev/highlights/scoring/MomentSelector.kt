package dev.highlights.scoring

import dev.highlights.core.model.Highlight
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.TimeRange
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun interface MomentSelector {
    /** Retourne les moments retenus, dans l'ordre chronologique. */
    fun select(timeline: ScoredTimeline, policy: SelectionPolicy, source: Path): List<Highlight>

    /**
     * Sélection sur plusieurs captures à la fois : la cible (top N, durée) vaut pour l'ensemble, pas pour chacune.
     * Retourne les moments de chaque capture, dans l'ordre de [inputs].
     */
    fun selectAcross(inputs: List<Pair<ScoredTimeline, Path>>, policy: SelectionPolicy): List<List<Highlight>> =
        inputs.map { (timeline, source) -> select(timeline, policy, source) }
}

/**
 * 1. candidats : fenêtres au-dessus du seuil, ou événements du type demandé (mode « tous les kills ») ;
 *    regroupés quand ils sont séparés de moins de mergeGap ;
 * 2. marges avant/après, durée bornée entre minClip et maxClip autour du pic ;
 * 3. fusion des clips qui se chevauchent après ajout des marges (pas de doublon) ;
 * 4. classement (score + événements) puis cible : top N, durée totale ou tout.
 */
object ThresholdMomentSelector : MomentSelector {

    /** [input] : capture d'origine, quand la sélection porte sur plusieurs. */
    private data class Candidate(val range: TimeRange, val peakIndex: Int, val peak: Duration, val score: Double, val input: Int = 0)

    /** Bonus de classement par événement supplémentaire dans un même moment (multi-kill). */
    private const val EXTRA_EVENT_RANK_BONUS = 0.25

    /** Poids d'une réaction dans le classement : un rire raconte plus qu'un cri, un cri plus qu'une phrase. */
    private val REACTION_WEIGHTS = mapOf("laughter" to 1.0, "shout" to 0.7, "speech" to 0.3)

    /** Une réaction peut démarrer juste avant le pic (le cri part avec le tir). */
    private val REACTION_LEAD = 500.milliseconds

    /** Marge gardée après la fin d'une réaction, pour ne pas couper le dernier souffle d'un rire. */
    private val REACTION_TAIL = 400.milliseconds

    override fun select(timeline: ScoredTimeline, policy: SelectionPolicy, source: Path): List<Highlight> =
        selectAcross(listOf(timeline to source), policy).single()

    /**
     * Les candidats de toutes les captures sont classés ensemble : « les 8 meilleurs moments » sont les 8 meilleurs de
     * la soirée, pas 8 par partie. Les scores sont normalisés capture par capture ; ils restent comparables tant que
     * les parties viennent du même jeu, avec le même profil.
     */
    override fun selectAcross(inputs: List<Pair<ScoredTimeline, Path>>, policy: SelectionPolicy): List<List<Highlight>> {
        val candidates = inputs.flatMapIndexed { i, (timeline, _) -> candidates(timeline, policy).map { it.copy(input = i) } }
        val chosen = applyTarget(candidates, policy) { inputs[it.input].first }
        return inputs.mapIndexed { i, (timeline, source) ->
            chosen.filter { it.input == i }
                .sortedBy { it.range.start }
                .mapIndexed { n, c ->
                    Highlight(
                        id = "h%03d".format(n + 1),
                        source = source,
                        range = c.range,
                        peak = c.peak,
                        score = c.score,
                        contributions = timeline.contributions.mapValues { (_, values) -> values[c.peakIndex] },
                        events = eventsIn(timeline, c.range),
                    )
                }
        }
    }

    /** Étapes 1 à 3 : les moments possibles d'une capture, avant la cible. */
    private fun candidates(timeline: ScoredTimeline, policy: SelectionPolicy): List<Candidate> {
        val grid = timeline.grid
        val scores = timeline.total
        val bounds = TimeRange(Duration.ZERO, grid.total)

        val groups = if (policy.requiredEvent != null) {
            eventGroups(timeline, policy)
        } else {
            thresholdGroups(timeline, policy)
        }

        val candidates = groups.map { (range, peakIndex) ->
            val peak = if (policy.requiredEvent != null) range.start.coerceIn(bounds.start, bounds.end) else grid.centerOf(peakIndex)
            val padded = TimeRange(range.start - policy.preRoll, range.end + policy.postRoll).clampTo(bounds)
            Candidate(fitLength(padded, peak, policy, bounds), peakIndex, peak, scores[peakIndex])
        }

        val extended = candidates.map { c ->
            val range = SegmentExtension.extend(c.range, timeline, policy, bounds)
            // La réaction qui suit le pic (rire, cri) est la chute du moment : elle est gardée jusqu'au bout.
            val payoff = reaction(timeline, c.peak, policy)?.takeIf { it.first != "speech" }?.second
            c.copy(range = if (payoff != null && payoff.end > range.end) {
                TimeRange(range.start, minOf(payoff.end + REACTION_TAIL, range.end + policy.maxExtension)).clampTo(bounds)
            } else {
                range
            })
        }

        val merged = mutableListOf<Candidate>()
        for (c in extended.sortedBy { it.range.start }) {
            val last = merged.lastOrNull()
            if (last != null && c.range.start < last.range.end) {
                val best = if (c.score > last.score) c else last
                val union = last.range.union(c.range)
                // Pas de rognage à maxClip si l'union protège une phrase ou un rire.
                val range = if (policy.keepWhole.isEmpty()) fitLength(union, best.peak, policy, bounds) else union
                merged[merged.lastIndex] = best.copy(range = range)
            } else {
                merged += c
            }
        }
        return merged
    }

    private fun thresholdGroups(timeline: ScoredTimeline, policy: SelectionPolicy): List<Pair<TimeRange, Int>> {
        val grid = timeline.grid
        val scores = timeline.total
        val groups = mutableListOf<Pair<TimeRange, Int>>()
        for (i in scores.indices) {
            if (scores[i] < policy.threshold) continue
            val r = grid.rangeOf(i)
            val last = groups.lastOrNull()
            if (last != null && r.start <= last.first.end + policy.mergeGap) {
                val peak = if (scores[i] > scores[last.second]) i else last.second
                groups[groups.lastIndex] = last.first.union(r) to peak
            } else {
                groups += r to i
            }
        }
        return groups
    }

    /** Un groupe par série d'événements rapprochés (multi-kill) ; le pic est la fenêtre la plus forte autour. */
    private fun eventGroups(timeline: ScoredTimeline, policy: SelectionPolicy): List<Pair<TimeRange, Int>> {
        val grid = timeline.grid
        if (grid.count == 0) return emptyList()
        val events = timeline.events.filter { it.kind == policy.requiredEvent }.sortedBy { it.at }
        val groups = mutableListOf<TimeRange>()
        for (e in events) {
            val last = groups.lastOrNull()
            if (last != null && e.at <= last.end + policy.mergeGap) {
                groups[groups.lastIndex] = TimeRange(last.start, e.at)
            } else {
                groups += TimeRange(e.at, e.at)
            }
        }
        return groups.map { r ->
            val indices = grid.indicesCovering(r.start).first..grid.indicesCovering(r.end).last
            r to (indices.maxByOrNull { timeline.total[it] } ?: grid.indicesCovering(r.start).first)
        }
    }

    private fun applyTarget(candidates: List<Candidate>, policy: SelectionPolicy, timelineOf: (Candidate) -> ScoredTimeline): List<Candidate> {
        fun rank(c: Candidate): Double {
            val timeline = timelineOf(c)
            val events = EXTRA_EVENT_RANK_BONUS * (eventsIn(timeline, c.range).values.sum() - 1).coerceAtLeast(0)
            val reaction = reaction(timeline, c.peak, policy)?.let { (kind, _) -> policy.reactionBonus * (REACTION_WEIGHTS[kind] ?: 0.0) } ?: 0.0
            return c.score + events + reaction
        }
        val byRank = candidates.sortedWith(compareByDescending<Candidate> { rank(it) }.thenBy { it.input }.thenBy { it.range.start })
        if (policy.target.all) return byRank
        policy.target.topN?.let { return byRank.take(it) }

        val budget = policy.target.totalDuration ?: return byRank
        val chosen = mutableListOf<Candidate>()
        var used = Duration.ZERO
        for (c in byRank) {
            val remaining = budget - used
            if (remaining < policy.minClip) break
            val clip = if (c.range.length <= remaining) {
                c
            } else {
                val trimmed = c.copy(range = placeAroundPeak(remaining, c.peak, policy, c.range))
                // Un moment rogné au point de couper une phrase ou un rire est écarté : on essaie les suivants.
                if (SegmentExtension.cutsSegment(trimmed.range, timelineOf(c), policy)) continue
                trimmed
            }
            chosen += clip
            used += clip.range.length
        }
        return chosen
    }

    /**
     * Réaction attribuée au pic : le segment de voix le plus parlant (rire, puis cri, puis phrase) qui commence entre
     * un peu avant le pic et [SelectionPolicy.reactionWindow] après. null si aucune, ou si la règle est désactivée.
     */
    internal fun reaction(timeline: ScoredTimeline, peak: Duration, policy: SelectionPolicy): Pair<String, TimeRange>? {
        if (policy.reactionBonus <= 0.0) return null
        return timeline.segments
            .filter { it.kind in REACTION_WEIGHTS && it.range.start >= peak - REACTION_LEAD && it.range.start <= peak + policy.reactionWindow }
            .maxByOrNull { REACTION_WEIGHTS.getValue(it.kind) }
            ?.let { it.kind to it.range }
    }

    private fun eventsIn(timeline: ScoredTimeline, range: TimeRange): Map<String, Int> =
        timeline.events.filter { it.at in range }.groupingBy { it.kind }.eachCount()

    /** Borne la longueur entre minClip et maxClip, en gardant le pic à la même position relative que les marges (3 s / 2 s → 60 %). */
    private fun fitLength(range: TimeRange, peak: Duration, policy: SelectionPolicy, bounds: TimeRange): TimeRange = when {
        range.length > policy.maxClip -> placeAroundPeak(policy.maxClip, peak, policy, range)
        range.length < policy.minClip -> {
            val missing = policy.minClip - range.length
            TimeRange.placed(range.start - missing * preRatio(policy), policy.minClip, bounds)
        }
        else -> range
    }

    private fun placeAroundPeak(length: Duration, peak: Duration, policy: SelectionPolicy, within: TimeRange): TimeRange =
        TimeRange.placed(peak - length * preRatio(policy), length, within)

    private fun preRatio(policy: SelectionPolicy): Double {
        val sum = policy.preRoll + policy.postRoll
        return if (sum.isPositive()) policy.preRoll / sum else 0.5
    }
}
