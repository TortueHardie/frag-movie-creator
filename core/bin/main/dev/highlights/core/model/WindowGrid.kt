package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration

/**
 * Fenêtres glissantes d'analyse : la fenêtre i couvre [i·hop, i·hop + size], tronquée à [total].
 */
@Serializable
data class WindowGrid(val size: SerialDuration, val hop: SerialDuration, val total: SerialDuration) {
    init {
        require(size.isPositive() && hop.isPositive()) { "size et hop doivent être > 0" }
        require(hop <= size) { "hop ($hop) doit être ≤ size ($size), sinon des instants ne sont couverts par aucune fenêtre" }
    }

    val count: Int get() = if (!total.isPositive()) 0 else ceil(total / hop - EPSILON).toInt()

    fun rangeOf(index: Int): TimeRange {
        val start = hop * index
        return TimeRange(start, minOf(start + size, total))
    }

    fun centerOf(index: Int): Duration = rangeOf(index).let { it.start + it.length / 2 }

    /** Indices des fenêtres qui contiennent l'instant [t]. */
    fun indicesCovering(t: Duration): IntRange {
        if (count == 0 || t.isNegative() || t > total) return IntRange.EMPTY
        val first = ceil((t - size) / hop - EPSILON).toInt().coerceAtLeast(0)
        val last = floor(t / hop + EPSILON).toInt().coerceAtMost(count - 1)
        return first..last
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
