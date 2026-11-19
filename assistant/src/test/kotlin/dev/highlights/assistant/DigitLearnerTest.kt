package dev.highlights.assistant

import dev.highlights.vision.DigitPair
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DigitLearnerTest : FunSpec({
    test("zone lue : cadre agrandi de 2 px, sur la grille paire de FFmpeg") {
        val a = DigitLearner.area(PixelBox(698, 12, 37, 16), 854, 480)
        (a.x % 2) shouldBe 0
        (a.y % 2) shouldBe 0
        (a.width % 2) shouldBe 0
        (a.height % 2) shouldBe 0
        (a.x <= 696 && a.right >= 737 && a.y <= 10 && a.bottom >= 30) shouldBe true
    }

    test("valeurs tenues : les lectures brèves sont oubliées, les illisibles ne coupent pas une valeur") {
        fun p(u: Int, t: Int? = null) = DigitPair(t, u, 1.0)
        val raw = List(12) { p(100) } + listOf(null, null) + List(3) { p(105) } + List(12) { p(100) } + List(15) { p(101) } + List(11) { p(101, 102) }
        DigitLearner.steadyValues(raw) shouldBe listOf(null to 100, null to 101, 102 to 101)
    }

    test("noms des groupes : l'ordre du compteur, ancré par la ressemblance aux polices") {
        // Groupes 0..9 = chiffres vrais 3,7,0,9,1,5,8,2,6,4 (dans le désordre). Deux parties : 0 → 13, puis 0 → 7.
        val truth = intArrayOf(3, 7, 0, 9, 1, 5, 8, 2, 6, 4)
        val groupOf = IntArray(10).also { g -> truth.forEachIndexed { k, d -> g[d] = k } }
        fun value(v: Int): Pair<Int?, Int> = (if (v >= 10) 100 + groupOf[v / 10] else null) to 100 + groupOf[v % 10]
        val sequence = (0..13).map(::value) + (0..7).map(::value)
        // Polices : sans elles, l'ordre seul se décale (tout + k) ; avec, chaque groupe ressemble un peu plus à son chiffre.
        val fonts = Array(10) { k -> DoubleArray(10) { d -> if (d == truth[k]) 0.6 else 0.3 } }
        val best = DigitLearner.bestLabelings(10, sequence, fonts, 1).single()
        best.toList() shouldBe truth.toList()
    }
})
