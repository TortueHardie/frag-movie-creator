package dev.highlights.vision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class AmmoCounterTest : FunSpec({
    val w = 12
    val h = 6

    /** Image du compteur : chaque valeur allume d'autres colonnes, sur un fond [background]. */
    fun frame(value: Int, background: Int = 40): ByteArray = ByteArray(w * h) { i ->
        (if ((i % w + value) % 3 == 0) 250 else background).toByte()
    }

    test("une balle par changement du compteur") {
        val frames = List(10) { frame(25) } + List(7) { frame(24) } + List(5) { frame(23) }
        AmmoCounterDetector.changes(frames, 200, 0.15, gap = 3) shouldBe listOf(10, 17)
    }

    test("un fond qui bouge sans passer au blanc ne compte pas, un compteur éteint non plus") {
        AmmoCounterDetector.changes(List(20) { frame(12, background = 40 + it * 5) }, 200, 0.15, 3) shouldBe emptyList()
        AmmoCounterDetector.changes(List(20) { ByteArray(w * h) { 30 } }, 200, 0.15, 3) shouldBe emptyList()
    }

    test("combat à mort : la recharge animée après le kill ne compte pas, la balle qui tue si") {
        // 25 → 24 (balle qui tue), recharge à 25 (chiffres agrandis, ici une autre forme), turquoise qui efface le
        // blanc, puis fondu vers le 25 blanc : une seule balle.
        val dark = ByteArray(w * h) { 30 }
        val green = ByteArray(w * h) { 255.toByte() }
        val none = ByteArray(w * h)
        val frames = List(10) { frame(25) } + List(6) { frame(24) } + List(3) { frame(26) } + List(12) { dark } + List(10) { frame(25) }
        val teal = List(19) { none } + List(12) { green } + List(10) { none }
        val masked = AmmoCounterDetector.refills(frames, teal, 200, margin = 4)
        AmmoCounterDetector.changes(frames, 200, 0.15, gap = 3, masked = masked) shouldBe listOf(10)
        // Sans le turquoise, la recharge et le retour au blanc passaient pour des balles.
        AmmoCounterDetector.changes(frames, 200, 0.15, gap = 3) shouldBe listOf(10, 16, 31)
    }

    test("paliers : un décor qui scintille derrière les chiffres ne compte pas, les balles si") {
        // 25 stable, puis un flash qui allume des pixels blancs une image sur deux (le décor), puis 24 et 23 stables.
        fun flicker(i: Int) = frame(25).also { f -> if (i % 2 == 0) for (p in 0 until w) f[p] = 250.toByte() }
        val frames = List(8) { frame(25) } + List(10) { flicker(it) } + List(6) { frame(24) } + List(6) { frame(23) }
        // Image par image (paliers d'une image) : chaque scintillement passait pour une balle.
        AmmoCounterDetector.changes(frames, 200, 0.15, gap = 1).size shouldBe 7
        AmmoCounterDetector.changes(frames, 200, 0.15, gap = 1, plateau = 3, minDigits = 0.017) shouldBe listOf(18, 24)
    }

    test("paliers : un compteur absent puis revenu n'est pas une balle, la lecture repart du palier suivant") {
        val empty = ByteArray(w * h) { 30 }
        // 12, caché (décor sombre, puis quelques pixels clairs épars), revenu à 12, puis 11.
        val sparse = ByteArray(w * h) { if (it == 7) 250.toByte() else 30 }
        val frames = List(6) { frame(12) } + List(6) { empty } + List(6) { sparse } + List(6) { frame(12) } + List(6) { frame(11) }
        AmmoCounterDetector.changes(frames, 200, 0.15, gap = 3, plateau = 3, minDigits = 0.1) shouldBe listOf(24)
    }

    test("paliers : la recharge animée reste écartée") {
        val dark = ByteArray(w * h) { 30 }
        val green = ByteArray(w * h) { 255.toByte() }
        val none = ByteArray(w * h)
        val frames = List(10) { frame(25) } + List(6) { frame(24) } + List(3) { frame(26) } + List(12) { dark } + List(10) { frame(25) }
        val teal = List(19) { none } + List(12) { green } + List(10) { none }
        val masked = AmmoCounterDetector.refills(frames, teal, 200, margin = 4)
        AmmoCounterDetector.changes(frames, 200, 0.15, gap = 3, masked = masked, plateau = 3, minDigits = 0.1) shouldBe listOf(10)
    }

    test("un décor turquoise derrière des chiffres blancs visibles n'est pas une recharge") {
        val frames = List(10) { frame(25) }
        val teal = List(10) { ByteArray(w * h) { 255.toByte() } }
        AmmoCounterDetector.refills(frames, teal, 200, margin = 4) shouldBe emptySet()
    }

    test("fenêtres autour des kills, fusionnées quand elles se chevauchent, dans la capture") {
        val windows = AmmoCounterDetector.windows(listOf(1.seconds, 100.seconds, 101.seconds), 1.8.seconds, 0.45.seconds, 101.2.seconds)
        windows shouldBe listOf(0.seconds to 1.45.seconds, 98.2.seconds to 101.2.seconds)
    }
})
