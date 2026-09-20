package dev.highlights.core

import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.WindowGrid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WindowGridTest : FunSpec({
    val grid = WindowGrid(size = 2.seconds, hop = 1.seconds, total = 10.seconds)

    test("nombre de fenêtres et bornes") {
        grid.count shouldBe 10
        grid.rangeOf(0) shouldBe TimeRange(0.seconds, 2.seconds)
        grid.rangeOf(9) shouldBe TimeRange(9.seconds, 10.seconds)
        grid.centerOf(3) shouldBe 4.seconds
    }

    test("une durée non multiple du pas ajoute une fenêtre partielle") {
        WindowGrid(2.seconds, 1.seconds, 10_500.milliseconds).count shouldBe 11
    }

    test("fenêtres couvrant un instant") {
        grid.indicesCovering(0.seconds) shouldBe 0..0
        grid.indicesCovering(4.5.seconds) shouldBe 3..4
        grid.indicesCovering(4.seconds) shouldBe 2..4
        grid.indicesCovering(10.seconds) shouldBe 8..9
        grid.indicesCovering(11.seconds) shouldBe IntRange.EMPTY
    }

    test("hop > size est refusé") {
        shouldThrow<IllegalArgumentException> { WindowGrid(1.seconds, 2.seconds, 10.seconds) }
    }

    test("placement d'un intervalle dans des bornes") {
        val bounds = TimeRange(0.seconds, 10.seconds)
        TimeRange.placed((-2).seconds, 4.seconds, bounds) shouldBe TimeRange(0.seconds, 4.seconds)
        TimeRange.placed(8.seconds, 4.seconds, bounds) shouldBe TimeRange(6.seconds, 10.seconds)
        TimeRange.placed(3.seconds, 20.seconds, bounds) shouldBe bounds
    }
})
