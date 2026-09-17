package dev.highlights.scoring

import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalSegment
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.TimelineSegment
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.serialization.roundTo

class NormalizedSignal(
    val id: String,
    val weight: Double,
    val eventBoost: Double,
    /** Valeurs normalisées (≥ 0) ou NaN. Pour une gate : 1 = en jeu, 0 = hors jeu. */
    val values: DoubleArray,
    val events: List<SignalEvent> = emptyList(),
    val gate: Boolean = false,
    val eventBoosts: Map<String, Double> = emptyMap(),
    val segments: List<SignalSegment> = emptyList(),
) {
    fun boostFor(kind: String): Double = eventBoosts[kind] ?: eventBoost
}

fun interface ScoreFusion {
    fun fuse(signals: List<NormalizedSignal>, grid: WindowGrid): ScoredTimeline
}

/**
 * 1. moyenne pondérée des signaux disponibles dans chaque fenêtre (un signal absent ne tire pas le score vers le bas) ;
 * 2. bonus des événements (kills…) ;
 * 3. multiplication par les gates (menus, chargement → 0).
 * Le total n'est pas plafonné à 1 : un kill pendant un combat intense doit rester devant un combat intense seul.
 */
object WeightedSumFusion : ScoreFusion {
    override fun fuse(signals: List<NormalizedSignal>, grid: WindowGrid): ScoredTimeline {
        val n = grid.count
        signals.forEach { require(it.values.size == n) { "signal ${it.id} : ${it.values.size} valeurs pour $n fenêtres" } }
        val (gates, scores) = signals.partition { it.gate }

        val total = DoubleArray(n)
        val contributions = scores.associate { it.id to DoubleArray(n) }
        for (i in 0 until n) {
            val weightSum = scores.sumOf { if (it.values[i].isNaN()) 0.0 else it.weight }
            if (weightSum <= 0) continue
            for (s in scores) {
                val v = s.values[i]
                if (v.isNaN()) continue
                val part = s.weight * v / weightSum
                contributions.getValue(s.id)[i] = part
                total[i] += part
            }
        }

        val events = scores.flatMap { s -> s.events.map { e -> s to e } }
        for ((s, event) in events) {
            val boost = s.boostFor(event.kind)
            if (boost == 0.0) continue
            for (i in grid.indicesCovering(event.at)) total[i] += boost * event.confidence
        }

        val inGame = BooleanArray(n) { true }
        for (g in gates) {
            for (i in 0 until n) {
                val v = g.values[i]
                if (v.isNaN()) continue
                total[i] *= v.coerceIn(0.0, 1.0)
                if (v < 0.5) inGame[i] = false
            }
        }

        return ScoredTimeline(
            grid = grid,
            total = total.map { it.coerceAtLeast(0.0).roundTo(4) },
            contributions = contributions.mapValues { (_, arr) -> arr.map { it.roundTo(4) } },
            events = events
                .filter { (_, e) -> grid.indicesCovering(e.at).none { !inGame[it] } }
                .map { (s, e) -> TimelineEvent(e.at, e.kind, e.confidence.roundTo(3), s.id) }
                .sortedBy { it.at },
            excluded = excludedRanges(inGame, grid),
            segments = signals.flatMap { s -> s.segments.map { TimelineSegment(it.range, it.kind, it.confidence.roundTo(3), s.id) } }
                .sortedBy { it.range.start },
        )
    }

    private fun excludedRanges(inGame: BooleanArray, grid: WindowGrid): List<TimeRange> {
        val ranges = mutableListOf<TimeRange>()
        var start = -1
        for (i in 0..inGame.size) {
            val out = i < inGame.size && !inGame[i]
            if (out && start < 0) start = i
            if (!out && start >= 0) {
                ranges += TimeRange(grid.rangeOf(start).start, grid.rangeOf(i - 1).end)
                start = -1
            }
        }
        return ranges
    }
}
