package dev.highlights.vision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

class HudChangeTest : FunSpec({
    /** Image 8x8 : un « chiffre » [n] (barres verticales à des places différentes) en blanc sur gris. */
    fun digit(n: Int): GrayImage {
        val px = ByteArray(64) { 60 }
        for (y in 1..6) {
            px[y * 8 + (n % 6) + 1] = 230.toByte()
            px[y * 8 + ((n * 3) % 6) + 1] = 230.toByte()
        }
        px[1 * 8 + (n % 5) + 1] = 230.toByte()
        return GrayImage(8, 8, px)
    }

    fun run(values: List<Int?>): List<Long> {
        val tracker = ChangeTracker(sameScore = 0.95, stableFrames = 3, minContrast = 10.0)
        values.forEachIndexed { i, v -> tracker.add((i * 500).milliseconds, v?.let(::digit) ?: GrayImage(8, 8, ByteArray(64) { 40 })) }
        return tracker.events.map { it.inWholeMilliseconds }
    }

    test("chaque nouvelle valeur stable est un kill, une valeur déjà vue ou passagère ne l'est pas") {
        // 0 0 0 1 1 1 (2 un instant) 1 1 2 2 2 1 1 1 : un kill à 1,5 s, un à 5 s ; le retour à 1 ne compte pas.
        run(listOf(0, 0, 0, 1, 1, 1, 2, 1, 1, 1, 2, 2, 2, 1, 1, 1)) shouldBe listOf(1500L, 5000L)
    }

    test("revoir la première valeur, c'est une nouvelle partie ; une zone vide est ignorée") {
        run(listOf(0, 0, 0, 1, 1, 1, null, null, 0, 0, 0, 1, 1, 1)) shouldBe listOf(1500L, 5500L)
    }

    test("les unités sont la colonne claire la plus à droite qui change, pas l'icône toujours allumée") {
        val frames = (0 until 40).map { i ->
            val px = ByteArray(20 * 10) { 50 }
            // Icône fixe en colonnes 14..17, chiffre variable en colonnes 6..9.
            for (y in 2..7) for (x in 14..17) px[y * 20 + x] = 240.toByte()
            for (y in 2..7) for (x in 6..9) if ((x + y + i) % 3 == 0) px[y * 20 + x] = 240.toByte()
            GrayImage(20, 10, px)
        }
        val box = UnitsFinder.find(frames, 170).shouldNotBeNull()
        box.x shouldBe 5
        box.width shouldBe 6
        box.y shouldBe 2
        box.height shouldBe 6
    }
})
