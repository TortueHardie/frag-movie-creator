package dev.highlights.scoring

import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineSegment
import dev.highlights.core.model.WindowGrid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds

/** Un moment qui raconte quelque chose (action puis réaction) passe devant un moment muet, et garde sa chute. */
class ReactionSelectionTest : FunSpec({
    val source = Path("partie.mp4")
    val policy = SelectionPolicy(
        threshold = 0.5, mergeGap = 3.seconds, preRoll = 3.seconds, postRoll = 2.seconds,
        minClip = 4.seconds, maxClip = 20.seconds, target = SelectionTarget(topN = 1),
    )

    fun timeline(seconds: Int, segments: List<TimelineSegment>, vararg peaks: Pair<IntRange, Double>): ScoredTimeline {
        val scores = MutableList(seconds) { 0.1 }
        peaks.forEach { (range, score) -> range.forEach { scores[it] = score } }
        return ScoredTimeline(WindowGrid(1.seconds, 1.seconds, seconds.seconds), scores, mapOf("audio" to scores), segments = segments)
    }

    fun segment(start: Double, end: Double, kind: String) = TimelineSegment(TimeRange(start.seconds, end.seconds), kind, 0.9, "reactions")

    test("à score proche, le moment suivi d'un rire passe devant le moment muet") {
        // Moment muet un peu plus fort (0,8) à 30 s ; moment à 0,7 suivi d'un rire à 100 s.
        val t = timeline(200, listOf(segment(101.5, 104.0, "laughter")), 30..30 to 0.8, 100..100 to 0.7)
        ThresholdMomentSelector.select(t, policy, source).single().peak shouldBe 100.5.seconds
    }

    test("un rire compte plus qu'une phrase") {
        val t = timeline(
            200,
            listOf(segment(31.0, 32.0, "speech"), segment(101.0, 102.0, "laughter")),
            30..30 to 0.7, 100..100 to 0.7,
        )
        ThresholdMomentSelector.select(t, policy, source).single().peak shouldBe 100.5.seconds
    }

    test("une réaction trop tardive n'est pas attribuée au moment") {
        val t = timeline(200, listOf(segment(106.0, 108.0, "laughter")), 30..30 to 0.8, 100..100 to 0.7)
        ThresholdMomentSelector.select(t, policy, source).single().peak shouldBe 30.5.seconds
    }

    test("le rire qui suit le pic est gardé jusqu'au bout, même au-delà de la marge") {
        // Pic à 100,5 s, marge après 2 s → fin à 103 s ; le rire court jusqu'à 106 s.
        val t = timeline(200, listOf(segment(102.0, 106.0, "laughter")), 100..100 to 0.9)
        ThresholdMomentSelector.select(t, policy, source).single().range shouldBe TimeRange(97.seconds, 106.4.seconds)
    }

    test("règle désactivée : ni bonus, ni extension") {
        val t = timeline(200, listOf(segment(101.5, 104.0, "laughter")), 30..30 to 0.8, 100..100 to 0.7)
        val off = policy.copy(reactionBonus = 0.0)
        ThresholdMomentSelector.select(t, off, source).single().peak shouldBe 30.5.seconds
        val single = timeline(200, listOf(segment(102.0, 106.0, "laughter")), 100..100 to 0.9)
        ThresholdMomentSelector.select(single, off, source).single().range shouldBe TimeRange(97.seconds, 101.seconds + 2.seconds)
    }
})
