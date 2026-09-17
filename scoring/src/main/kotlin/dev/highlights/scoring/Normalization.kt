package dev.highlights.scoring

import dev.highlights.core.profile.NormalizationConfig

fun interface SignalNormalizer {
    /** Ramène des valeurs brutes vers [0, 1]. Les NaN restent NaN. */
    fun normalize(raw: DoubleArray, config: NormalizationConfig): DoubleArray
}

/**
 * Normalisation relative à la vidéo par percentiles (robuste aux valeurs extrêmes),
 * avec un écart minimal absolu pour ne pas transformer une partie calme en « meilleurs moments ».
 */
object PercentileNormalizer : SignalNormalizer {
    override fun normalize(raw: DoubleArray, config: NormalizationConfig): DoubleArray {
        val valid = raw.filterNot { it.isNaN() }.sorted()
        if (valid.isEmpty()) return raw.copyOf()
        val low = percentile(valid, config.lowPercentile)
        val high = percentile(valid, config.highPercentile)
        val spread = maxOf(high - low, config.minSpread)
        if (spread <= 1e-12) return DoubleArray(raw.size) { if (raw[it].isNaN()) Double.NaN else 0.0 }
        return DoubleArray(raw.size) { i ->
            val v = raw[i]
            if (v.isNaN()) Double.NaN else ((v - low) / spread).coerceIn(0.0, config.maxValue)
        }
    }

    /** Percentile à interpolation linéaire sur une liste triée. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.lastIndex)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
