package dev.highlights.scoring

import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.profile.NormalizationConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class NormalizationTest : FunSpec({
    test("percentile bas → 0, percentile haut → 1, NaN conservé") {
        val raw = DoubleArray(100) { it.toDouble() }.also { it[3] = Double.NaN }
        val n = PercentileNormalizer.normalize(raw, NormalizationConfig(0.5, 0.9))
        n[10] shouldBe 0.0
        n[99] shouldBe 1.0
        n[3].isNaN() shouldBe true
        n[70] shouldBe (0.5 plusOrMinus 0.02)
    }

    test("minSpread : un signal quasi plat reste bas") {
        val raw = DoubleArray(100) { if (it == 50) 2.0 else 0.0 }
        PercentileNormalizer.normalize(raw, NormalizationConfig(0.5, 0.98, minSpread = 10.0))[50] shouldBe (0.2 plusOrMinus 1e-9)
        PercentileNormalizer.normalize(raw, NormalizationConfig(0.5, 1.0, minSpread = 0.0))[50] shouldBe 1.0
    }
})

class FusionTest : FunSpec({
    val grid = WindowGrid(1.seconds, 1.seconds, 4.seconds)

    test("un signal absent redistribue son poids") {
        val game = NormalizedSignal("game", 0.7, 0.0, doubleArrayOf(1.0, 0.5, 0.0, 1.0))
        val mic = NormalizedSignal("mic", 0.3, 0.0, doubleArrayOf(Double.NaN, Double.NaN, 1.0, 0.0))
        val t = WeightedSumFusion.fuse(listOf(game, mic), grid)
        t.total shouldBe listOf(1.0, 0.5, 0.3, 0.7)
        t.contributions.getValue("mic") shouldBe listOf(0.0, 0.0, 0.3, 0.0)
    }

    test("les événements ajoutent un bonus, sans plafond à 1 (classement des moments très forts)") {
        val s = NormalizedSignal("tpl", 1.0, 0.5, doubleArrayOf(0.2, 0.2, 0.8, 0.2), listOf(SignalEvent(2.5.seconds, "kill", 1.0)))
        WeightedSumFusion.fuse(listOf(s), grid).total shouldBe listOf(0.2, 0.2, 1.3, 0.2)
    }
})

class MomentSelectorTest : FunSpec({
    val source = Path("game.mp4")

    fun timeline(total: Int, vararg peaks: Pair<IntRange, Double>): ScoredTimeline {
        val grid = WindowGrid(1.seconds, 1.seconds, total.seconds)
        val scores = MutableList(total) { 0.1 }
        peaks.forEach { (range, score) -> range.forEach { scores[it] = score } }
        return ScoredTimeline(grid, scores, mapOf("audio" to scores))
    }

    val base = SelectionPolicy(
        threshold = 0.5, mergeGap = 3.seconds, preRoll = 3.seconds, postRoll = 2.seconds,
        minClip = 4.seconds, maxClip = 20.seconds, target = SelectionTarget(topN = 10),
    )

    test("marges ajoutées et fenêtres proches fusionnées") {
        val t = timeline(120, 30..31 to 0.9, 34..35 to 0.8, 80..80 to 0.7)
        val h = ThresholdMomentSelector.select(t, base, source)
        h shouldHaveSize 2
        h[0].range shouldBe TimeRange(27.seconds, 38.seconds)
        h[0].score shouldBe 0.9
        h[0].peak shouldBe 30.5.seconds
        h[1].range shouldBe TimeRange(77.seconds, 83.seconds)
        h.map { it.id } shouldBe listOf("h001", "h002")
    }

    test("les marges qui se chevauchent ne créent pas de doublon") {
        // Groupes séparés de 4 s (> mergeGap) mais les marges 3 s + 2 s se recouvrent.
        val t = timeline(60, 10..10 to 0.9, 15..15 to 0.6)
        val h = ThresholdMomentSelector.select(t, base, source)
        h shouldHaveSize 1
        h[0].range shouldBe TimeRange(7.seconds, 18.seconds)
    }

    test("durée max : clip recentré autour du pic avec le ratio des marges") {
        val t = timeline(200, 50..89 to 0.6, 70..70 to 0.95)
        val h = ThresholdMomentSelector.select(t, base, source).single()
        h.range.length shouldBe 20.seconds
        // Pic à 70.5 s, 60 % du clip avant le pic.
        h.range shouldBe TimeRange(58.5.seconds, 78.5.seconds)
    }

    test("durée min et bornes de la vidéo") {
        val t = timeline(10, 0..0 to 0.9)
        val policy = base.copy(preRoll = Duration.ZERO, postRoll = Duration.ZERO, minClip = 6.seconds)
        ThresholdMomentSelector.select(t, policy, source).single().range shouldBe TimeRange(0.seconds, 6.seconds)
    }

    test("top N garde les meilleurs scores, rendus dans l'ordre chronologique") {
        val t = timeline(300, 20..20 to 0.6, 100..100 to 0.9, 200..200 to 0.8)
        val h = ThresholdMomentSelector.select(t, base.copy(target = SelectionTarget(topN = 2)), source)
        h.map { it.score } shouldBe listOf(0.9, 0.8)
        h[0].range.start shouldBe 97.seconds
    }

    test("durée cible : remplit par score puis rogne le dernier clip autour de son pic") {
        val t = timeline(300, 20..29 to 0.6, 100..109 to 0.9, 200..209 to 0.8)
        // Chaque clip fait 15 s (10 + 3 + 2) ; budget 35 s → 15 + 15 + 5.
        val h = ThresholdMomentSelector.select(t, base.copy(target = SelectionTarget(totalDuration = 35.seconds)), source)
        h shouldHaveSize 3
        h.fold(Duration.ZERO) { acc, x -> acc + x.range.length } shouldBe 35.seconds
        h[0].range.length shouldBe 5.seconds
        h[0].score shouldBe 0.6
    }

    test("rien au-dessus du seuil") {
        ThresholdMomentSelector.select(timeline(60), base, source).shouldBeEmpty()
    }

    test("contributions relevées au pic") {
        val h = ThresholdMomentSelector.select(timeline(60, 20..20 to 0.9), base, source).single()
        h.contributions.getValue("audio") shouldBe 0.9
        h.score shouldBeLessThan 1.0
    }
})
