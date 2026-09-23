package dev.highlights.montage

import dev.highlights.core.serialization.roundTo
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Note d'un montage, calculée sur son plan. Elle ne dit pas si un montage est beau : elle mesure ce que le moteur
 * prétend faire, pour qu'on puisse comparer deux versions sans les regarder l'une après l'autre. Chaque critère va de
 * 0 à 1, et [details] garde les mesures brutes pour comprendre d'où vient une baisse.
 */
@Serializable
data class MontageScore(
    val total: Double,
    /** Les kills tombent-ils sur un temps ? */
    val sync: Double,
    /** Sur un temps accentué ou un premier temps de mesure, plutôt que n'importe lequel ? */
    val accent: Double,
    /** Sobriété : un plan ne cumule pas ralenti et zoom, et le flash reste rare. */
    val restraint: Double,
    /** Deux plans voisins ne viennent pas du même moment de la même partie. Null s'il n'y a qu'un plan. */
    val variety: Double?,
    /** Durée demandée réellement utilisée. */
    val fill: Double,
    /**
     * Les plans sont-ils plus courts dans les sections intenses ? Null quand le montage n'a pas deux plans ordinaires
     * dans des sections d'intensités différentes : la question ne se pose alors pas.
     */
    val pacing: Double?,
    /** Absence d'image gelée faute de source. */
    val coverage: Double,
    val details: Map<String, Double> = emptyMap(),
)

/**
 * Mesure un [MontagePlan]. Les critères sont ceux que le moteur vise explicitement ; les poids disent lesquels comptent
 * le plus : un kill à côté du temps se voit immédiatement, une durée un peu courte non.
 */
object MontageScorer {
    /** En deçà, un kill est sur le temps : une image dure 16,7 ms à 60 i/s. */
    private val ON_BEAT = 20.milliseconds

    /** Au-delà, il est franchement à côté. */
    private val OFF_BEAT = 200.milliseconds

    /** Part des coupes qui peuvent porter un flash sans que l'effet se banalise. */
    private const val FLASH_BUDGET = 0.4

    private val WEIGHTS = mapOf(
        "sync" to 0.25, "restraint" to 0.20, "variety" to 0.15,
        "accent" to 0.10, "fill" to 0.10, "pacing" to 0.10, "coverage" to 0.10,
    )

    fun score(plan: MontagePlan): MontageScore {
        val music = plan.music
        val offsets = plan.clipOffsets()

        /** Écart d'un instant du montage au temps de la musique le plus proche. */
        fun toBeat(at: Duration): Duration {
            val absolute = plan.musicStart + at
            return music.beats.minOf { (it - absolute).absoluteValue }
        }

        fun onBeat(gap: Duration): Double =
            1.0 - ((gap - ON_BEAT) / (OFF_BEAT - ON_BEAT)).coerceIn(0.0, 1.0)

        // --- synchronisation : les kills d'ancrage d'abord, ce sont eux que le moteur promet sur un temps.
        val anchorGaps = plan.clips.mapIndexed { i, c -> toBeat(offsets[i] + c.toOutput(c.anchor)) }
        val allGaps = plan.clips.flatMapIndexed { i, c -> c.outputKills().map { toBeat(offsets[i] + it) } }
        val sync = anchorGaps.map(::onBeat).average()
        // Les kills intermédiaires d'un multi-kill peuvent aussi viser un contretemps marqué : une frappe compte autant.
        val hits = music.hits(plan.startBeat, plan.endBeat).map { it.at }
        val hitGaps = plan.clips.flatMapIndexed { i, c ->
            c.outputKills().map { k -> hits.minOf { (it - (plan.musicStart + offsets[i] + k)).absoluteValue } }
        }

        // --- accentuation : tomber sur une attaque forte, mieux encore sur un premier temps de mesure.
        val accent = plan.clips.map { c ->
            val strength = music.beatAccent.getOrElse(c.anchorBeat) { 0.0 }.coerceIn(0.0, 1.0)
            0.7 * strength + 0.3 * (if (music.isDownbeat(c.anchorBeat)) 1.0 else 0.0)
        }.average()

        // --- sobriété : un plan porte une emphase, pas deux, et le flash reste une exception.
        val zooms = MontageRenderBuilder.zooms(plan)
        val flashes = MontageRenderBuilder.flashes(plan)
        val emphases = plan.clips.mapIndexed { i, c -> (if (c.slow != null) 1 else 0) + (if (zooms[i]) 1 else 0) }
        val stacked = emphases.count { it > 1 }.toDouble() / plan.clips.size
        val cuts = (plan.clips.size - 1).coerceAtLeast(1)
        val flashRate = flashes.count { it }.toDouble() / cuts
        val restraint = (1.0 - 0.6 * stacked - 0.4 * ((flashRate - FLASH_BUDGET) / (1 - FLASH_BUDGET)).coerceIn(0.0, 1.0))
            .coerceIn(0.0, 1.0)

        // --- variété : des voisins tirés du même moment de la même partie se ressemblent.
        val gap = plan.settings.varietyGap
        val pairs = plan.clips.zipWithNext()
        val similar = pairs.count { (a, b) ->
            gap.isPositive() && a.group.media.path == b.group.media.path &&
                (a.group.kills.first() - b.group.kills.first()).absoluteValue < gap
        }
        val variety = if (pairs.isEmpty()) null else 1.0 - similar.toDouble() / pairs.size

        // --- remplissage de la durée demandée.
        val fill = (plan.duration / plan.settings.maxDuration).coerceIn(0.0, 1.0)

        // --- rythme : à intensité différente, le plan de la section la plus intense doit être le plus court.
        // Le plan de la drop et ceux étendus pour tenir un multi-kill sont longs par conception : les comparer
        // reviendrait à reprocher au moteur ce qu'on lui demande. Seuls les plans dont la longueur vient de la grille
        // entrent dans la mesure.
        val ordinary = plan.clips.filter { it.slot.dropBeat == null && it.kills.size <= 1 }
        var comparable = 0
        var concordant = 0
        for (i in ordinary.indices) {
            for (j in i + 1 until ordinary.size) {
                val a = ordinary[i]
                val b = ordinary[j]
                val ia = music.sections[a.slot.section].intensity
                val ib = music.sections[b.slot.section].intensity
                if (abs(ia - ib) < 0.1 || a.beats == b.beats) continue
                comparable++
                if ((ia > ib) == (a.beats < b.beats)) concordant++
            }
        }
        val pacing = if (comparable == 0) null else concordant.toDouble() / comparable

        // --- couverture : une image gelée est un plan que la source ne remplissait pas.
        val frozen = plan.clips.fold(Duration.ZERO) { acc, c -> acc + c.padBefore + c.padAfter }
        val coverage = (1.0 - frozen / plan.duration).coerceIn(0.0, 1.0)

        // Un critère qu'on ne peut pas mesurer sort de la moyenne au lieu d'y entrer à 1 : sinon le total grimperait
        // justement sur les montages où l'on en sait le moins.
        val parts = mapOf(
            "sync" to sync, "accent" to accent, "restraint" to restraint,
            "variety" to variety, "fill" to fill, "pacing" to pacing, "coverage" to coverage,
        ).filterValues { it != null }.mapValues { it.value!! }
        val weight = WEIGHTS.filterKeys { it in parts }.values.sum()
        val total = parts.entries.sumOf { (k, v) -> WEIGHTS.getValue(k) * v } / weight
        return MontageScore(
            total = total.roundTo(3),
            sync = sync.roundTo(3),
            accent = accent.roundTo(3),
            restraint = restraint.roundTo(3),
            variety = variety?.roundTo(3),
            fill = fill.roundTo(3),
            pacing = pacing?.roundTo(3),
            coverage = coverage.roundTo(3),
            details = mapOf(
                "clips" to plan.clips.size.toDouble(),
                "ancreEcartMoyenMs" to anchorGaps.map { it.inWholeMicroseconds / 1000.0 }.average().roundTo(1),
                "ancreEcartMaxMs" to (anchorGaps.maxOfOrNull { it.inWholeMicroseconds / 1000.0 } ?: 0.0).roundTo(1),
                "killsSurLeTemps" to (allGaps.count { it <= ON_BEAT }.toDouble() / allGaps.size.coerceAtLeast(1)).roundTo(3),
                "killsSurUneFrappe" to (hitGaps.count { it <= ON_BEAT }.toDouble() / hitGaps.size.coerceAtLeast(1)).roundTo(3),
                "emphasesParPlan" to emphases.average().roundTo(3),
                "flashParCoupe" to flashRate.roundTo(3),
                "voisinsSemblables" to similar.toDouble(),
                "gelSecondes" to (frozen.inWholeMilliseconds / 1000.0).roundTo(2),
            ),
        )
    }
}
