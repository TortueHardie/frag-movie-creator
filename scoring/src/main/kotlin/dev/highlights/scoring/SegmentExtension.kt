package dev.highlights.scoring

import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.TimeRange
import kotlin.time.Duration.Companion.milliseconds

/**
 * Étend un moment pour ne pas couper une phrase ou un rire en cours : si le début ou la fin tombe dans un segment
 * protégé (ou juste avant qu'il commence), la borne est repoussée jusqu'au bord du segment, dans la limite de maxExtension.
 */
object SegmentExtension {
    private val LEAD_IN = 200.milliseconds
    private val TAIL = 400.milliseconds
    /** Une phrase qui démarre juste après la fin du moment est une réaction : on la garde. */
    private val CONTINUATION = 700.milliseconds

    fun extend(range: TimeRange, timeline: ScoredTimeline, policy: SelectionPolicy, bounds: TimeRange): TimeRange {
        val protected = protectedSegments(timeline, policy)
        if (protected.isEmpty()) return range
        val minStart = range.start - policy.maxExtension
        val maxEnd = range.end + policy.maxExtension
        var start = range.start
        var end = range.end
        var changed = true
        while (changed) {
            changed = false
            for (s in protected) {
                if (s.start < start && s.end > start - LEAD_IN) {
                    val newStart = maxOf(s.start - LEAD_IN, minStart)
                    if (newStart < start) { start = newStart; changed = true }
                }
                if (s.end > end && s.start < end + CONTINUATION) {
                    val newEnd = minOf(s.end + TAIL, maxEnd)
                    if (newEnd > end) { end = newEnd; changed = true }
                }
            }
        }
        return TimeRange(start, end).clampTo(bounds)
    }

    /** Vrai si une borne du moment tombe au milieu d'un segment protégé. */
    fun cutsSegment(range: TimeRange, timeline: ScoredTimeline, policy: SelectionPolicy): Boolean =
        protectedSegments(timeline, policy).any { s ->
            (range.start > s.start && range.start < s.end) || (range.end > s.start && range.end < s.end)
        }

    private fun protectedSegments(timeline: ScoredTimeline, policy: SelectionPolicy): List<TimeRange> =
        if (policy.keepWhole.isEmpty()) emptyList()
        else timeline.segments.filter { it.kind in policy.keepWhole }.map { it.range }

}
