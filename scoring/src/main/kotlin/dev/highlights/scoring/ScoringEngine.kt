package dev.highlights.scoring

import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.profile.DetectorConfig
import dev.highlights.core.profile.DetectorRole
import java.nio.file.Path

/** Assemble normalisation, fusion et sélection ; chaque étape est remplaçable. */
class ScoringEngine(
    private val normalizer: SignalNormalizer = PercentileNormalizer,
    private val fusion: ScoreFusion = WeightedSumFusion,
    private val selector: MomentSelector = ThresholdMomentSelector,
) {
    fun score(tracks: List<Pair<DetectorConfig, SignalTrack>>, grid: WindowGrid): ScoredTimeline =
        fusion.fuse(
            tracks.map { (config, track) ->
                val gate = config.role == DetectorRole.GATE
                NormalizedSignal(
                    id = config.id,
                    weight = config.weight,
                    eventBoost = config.eventBoost,
                    values = if (gate) track.raw.copyOf() else normalizer.normalize(track.raw, config.normalization),
                    events = track.events,
                    gate = gate,
                    eventBoosts = config.eventBoosts,
                    segments = track.segments,
                )
            },
            grid,
        )

    fun select(timeline: ScoredTimeline, policy: SelectionPolicy, source: Path): List<Highlight> =
        selector.select(timeline, policy, source)

    fun selectAcross(inputs: List<Pair<ScoredTimeline, Path>>, policy: SelectionPolicy): List<List<Highlight>> =
        selector.selectAcross(inputs, policy)
}
