package dev.highlights.scoring

import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.WindowGrid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds

class GateAndBoostFusionTest : FunSpec({
    val grid = WindowGrid(1.seconds, 1.seconds, 6.seconds)

    test("une gate annule le score hors jeu et y supprime les événements") {
        val audio = NormalizedSignal("audio", 1.0, 0.0, doubleArrayOf(0.9, 0.9, 0.9, 0.9, 0.9, 0.9))
        val gate = NormalizedSignal("in-game", 0.0, 0.0, doubleArrayOf(1.0, 1.0, 0.0, 0.0, 1.0, Double.NaN), gate = true)
        val events = NormalizedSignal(
            "notifications", 0.0, 0.0, DoubleArray(6) { Double.NaN },
            events = listOf(SignalEvent(0.5.seconds, "kill", 1.0), SignalEvent(2.5.seconds, "kill", 1.0)),
            eventBoosts = mapOf("kill" to 0.8),
        )
        val t = WeightedSumFusion.fuse(listOf(audio, gate, events), grid)
        t.total shouldBe listOf(1.7, 0.9, 0.0, 0.0, 0.9, 0.9)
        t.excluded shouldBe listOf(TimeRange(2.seconds, 4.seconds))
        t.events.map { it.at } shouldBe listOf(0.5.seconds)
    }

    test("bonus par type d'événement, avec repli sur eventBoost") {
        val s = NormalizedSignal(
            "notifications", 0.0, 0.1, DoubleArray(6) { Double.NaN },
            events = listOf(SignalEvent(0.5.seconds, "kill", 1.0), SignalEvent(1.5.seconds, "assist", 1.0), SignalEvent(2.5.seconds, "other", 1.0)),
            eventBoosts = mapOf("kill" to 0.8, "assist" to 0.3),
        )
        WeightedSumFusion.fuse(listOf(s), grid).total.take(3) shouldBe listOf(0.8, 0.3, 0.1)
    }
})

class EventSelectionTest : FunSpec({
    val source = Path("game.mp4")

    fun timeline(total: Int, events: List<Pair<Int, String>>, scores: List<Double> = List(total) { 0.2 }): ScoredTimeline {
        val grid = WindowGrid(1.seconds, 1.seconds, total.seconds)
        return ScoredTimeline(grid, scores, mapOf("audio" to scores), events.map { (t, k) -> TimelineEvent(t.seconds, k, 1.0, "n") })
    }

    val policy = SelectionPolicy(
        threshold = 0.9, mergeGap = 4.seconds, preRoll = 4.seconds, postRoll = 2.seconds,
        minClip = 4.seconds, maxClip = 20.seconds, target = SelectionTarget(all = true), requiredEvent = "kill",
    )

    test("mode kills : un moment par kill, seuil ignoré, multi-kill regroupé") {
        val t = timeline(300, listOf(50 to "kill", 53 to "kill", 120 to "kill", 200 to "assist"))
        val h = ThresholdMomentSelector.select(t, policy, source)
        h shouldHaveSize 2
        h[0].range shouldBe TimeRange(46.seconds, 55.seconds)
        h[0].events shouldBe mapOf("kill" to 2)
        h[1].range shouldBe TimeRange(116.seconds, 122.seconds)
        h[1].peak shouldBe 120.seconds
    }

    test("tout garder vs top N, les multi-kills passent devant") {
        val t = timeline(300, listOf(50 to "kill", 53 to "kill", 120 to "kill", 250 to "kill"))
        ThresholdMomentSelector.select(t, policy, source) shouldHaveSize 3
        val top1 = ThresholdMomentSelector.select(t, policy.copy(target = SelectionTarget(topN = 1)), source).single()
        top1.events shouldBe mapOf("kill" to 2)
    }

    test("les moments au seuil comptent aussi leurs événements") {
        val scores = List(100) { if (it in 40..42) 1.0 else 0.1 }
        val h = ThresholdMomentSelector.select(timeline(100, listOf(41 to "kill"), scores), policy.copy(requiredEvent = null), source).single()
        h.events shouldBe mapOf("kill" to 1)
    }

    test("cible : exactement un de topN, totalDuration ou all") {
        shouldThrow<IllegalArgumentException> { SelectionTarget(topN = 3, all = true) }
        shouldThrow<IllegalArgumentException> { SelectionTarget() }
    }
})
