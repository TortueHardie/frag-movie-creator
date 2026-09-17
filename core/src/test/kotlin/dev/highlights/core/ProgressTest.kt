package dev.highlights.core

import dev.highlights.core.progress.ProgressSnapshot
import dev.highlights.core.progress.ProgressTracker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ProgressTest : FunSpec({
    test("progression pondérée et chemin d'étape") {
        val snapshots = mutableListOf<ProgressSnapshot>()
        val tracker = ProgressTracker(TestTimeSource()) { snapshots += it }
        val analysis = tracker.root.child("Analyse", 0.8)
        val audio = analysis.child("audio", 0.5)
        val mic = analysis.child("mic", 0.5)
        val export = tracker.root.child("Export", 0.2)

        audio.update(1.0)
        snapshots.last().fraction shouldBe (0.4 plusOrMinus 1e-9)
        snapshots.last().stage shouldBe "Analyse > audio"

        mic.update(0.5)
        snapshots.last().fraction shouldBe (0.6 plusOrMinus 1e-9)

        export.complete()
        snapshots.last().fraction shouldBe (0.8 plusOrMinus 1e-9)
    }

    test("la progression ne recule jamais") {
        val snapshots = mutableListOf<ProgressSnapshot>()
        val leaf = ProgressTracker(TestTimeSource()) { snapshots += it }.root.child("x", 1.0)
        leaf.update(0.7)
        leaf.update(0.3)
        snapshots.last().fraction shouldBe (0.7 plusOrMinus 1e-9)
    }

    test("ETA calculée sur le débit observé") {
        val time = TestTimeSource()
        val snapshots = mutableListOf<ProgressSnapshot>()
        val leaf = ProgressTracker(time) { snapshots += it }.root.child("x", 1.0)

        leaf.update(0.0)
        snapshots.last().eta.shouldBeNull()

        repeat(10) { i ->
            time += 1.seconds
            leaf.update((i + 1) * 0.05)
        }
        // 50 % en 10 s → environ 10 s restantes
        val eta = snapshots.last().eta.shouldNotBeNull()
        eta.inWholeMilliseconds.toDouble() shouldBe (10_000.0 plusOrMinus 500.0)
    }
})
