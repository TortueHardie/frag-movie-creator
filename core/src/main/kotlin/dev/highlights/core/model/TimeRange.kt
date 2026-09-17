package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.serialization.toTimecode
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class TimeRange(val start: SerialDuration, val end: SerialDuration) {
    init {
        require(end >= start) { "TimeRange invalide : $start > $end" }
    }

    val length: Duration get() = end - start

    operator fun contains(t: Duration): Boolean = t in start..end

    /** Vrai si les deux intervalles se chevauchent ou sont séparés d'au plus [gap]. */
    fun isWithin(other: TimeRange, gap: Duration = Duration.ZERO): Boolean =
        start <= other.end + gap && other.start <= end + gap

    fun union(other: TimeRange) = TimeRange(minOf(start, other.start), maxOf(end, other.end))

    fun clampTo(bounds: TimeRange): TimeRange =
        TimeRange(start.coerceIn(bounds.start, bounds.end), end.coerceIn(bounds.start, bounds.end))

    override fun toString() = "[${start.toTimecode()} → ${end.toTimecode()}]"

    companion object {
        /** Intervalle de longueur [length] commençant idéalement à [desiredStart], décalé pour rester dans [bounds]. */
        fun placed(desiredStart: Duration, length: Duration, bounds: TimeRange): TimeRange {
            if (length >= bounds.length) return bounds
            val start = desiredStart.coerceIn(bounds.start, bounds.end - length)
            return TimeRange(start, start + length)
        }
    }
}
