package dev.highlights.core.progress

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class ProgressSnapshot(
    /** Avancement global dans [0, 1]. */
    val fraction: Double,
    /** Chemin de l'étape en cours, ex. "Analyse > game-audio". */
    val stage: String,
    val detail: String?,
    val elapsed: Duration,
    val eta: Duration?,
)

/**
 * Progression hiérarchique : chaque étape reçoit une part (weight) de son parent, les poids d'un même parent sommant à 1.
 * Seules les feuilles appellent [update]. Thread-safe.
 */
interface ProgressReporter {
    fun update(fraction: Double, detail: String? = null)

    fun child(label: String, weight: Double): ProgressReporter

    fun complete() = update(1.0)

    companion object {
        val NONE: ProgressReporter = object : ProgressReporter {
            override fun update(fraction: Double, detail: String?) = Unit
            override fun child(label: String, weight: Double): ProgressReporter = this
        }
    }
}

class ProgressTracker(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val listener: (ProgressSnapshot) -> Unit,
) {
    private val lock = Any()
    private val start = timeSource.markNow()
    private val eta = EtaEstimator()
    private val rootNode = Node(null, "", 1.0)

    val root: ProgressReporter get() = rootNode

    private inner class Node(val parent: Node?, val label: String, val weight: Double) : ProgressReporter {
        private var own = 0.0
        private val children = mutableListOf<Node>()

        fun fraction(): Double = maxOf(own, children.sumOf { it.weight * it.fraction() }).coerceIn(0.0, 1.0)

        val path: String
            get() = generateSequence(this) { it.parent }.map { it.label }.filter { it.isNotEmpty() }
                .toList().asReversed().joinToString(" > ")

        override fun update(fraction: Double, detail: String?) {
            val snapshot = synchronized(lock) {
                own = maxOf(own, fraction.coerceIn(0.0, 1.0))
                val global = rootNode.fraction()
                val elapsed = start.elapsedNow()
                ProgressSnapshot(global, path, detail, elapsed, eta.record(elapsed, global))
            }
            listener(snapshot)
        }

        override fun child(label: String, weight: Double): ProgressReporter = synchronized(lock) {
            Node(this, label, weight.coerceIn(0.0, 1.0)).also { children += it }
        }
    }
}

/**
 * Estime le temps restant à partir du débit observé sur une fenêtre glissante récente (réactif aux étapes de vitesses différentes),
 * avec repli sur le débit moyen depuis le début.
 */
class EtaEstimator(private val window: Duration = 20.seconds, private val minObservation: Duration = 2.seconds) {
    private val samples = ArrayDeque<Pair<Duration, Double>>()

    fun record(elapsed: Duration, fraction: Double): Duration? {
        samples.addLast(elapsed to fraction)
        while (samples.size > 2 && elapsed - samples.first().first > window) samples.removeFirst()
        if (fraction >= 1.0) return Duration.ZERO
        if (fraction <= 0.0 || elapsed < minObservation) return null

        val (t0, f0) = samples.first()
        val dt = elapsed - t0
        val df = fraction - f0
        val rate = if (dt >= minObservation && df > 0) df / dt.inWholeMilliseconds else fraction / elapsed.inWholeMilliseconds
        if (rate <= 0) return null
        return ((1.0 - fraction) / rate).toLong().coerceAtLeast(0).milliseconds
    }
}
