package dev.highlights.vision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

class KillfeedPlayerTest : FunSpec({
    /** Mots en « police » maison : chaque lettre est un motif de 3x6 px, un pixel entre deux lettres. */
    val glyphs = mapOf(
        'K' to listOf("101", "110", "100", "110", "101", "101"),
        'r' to listOf("000", "110", "101", "100", "100", "100"),
        'y' to listOf("101", "101", "011", "001", "110", "000"),
        'A' to listOf("010", "101", "111", "101", "101", "101"),
        'd' to listOf("001", "001", "111", "101", "111", "000"),
        'm' to listOf("000", "111", "111", "101", "101", "000"),
        'C' to listOf("111", "100", "100", "100", "111", "000"),
        'u' to listOf("000", "101", "101", "101", "111", "000"),
    )

    /** Ligne de 16 px de haut (+2 de marge) : fond sombre, premier mot à [start], « + » et un second mot si [second]. */
    fun line(first: String, start: Int = 8, second: String? = null): GrayImage {
        val w = 140
        val h = 20
        val px = ByteArray(w * h) { 40 }
        fun word(text: String, x0: Int): Int {
            var x = x0
            for (c in text) {
                val g = glyphs.getValue(c)
                for (y in 0 until 6) for (i in 0 until 3) if (g[y][i] == '1') px[(7 + y) * w + x + i] = 210.toByte()
                x += 4
            }
            return x
        }
        val end = word(first, start)
        if (second != null) {
            for (i in 0 until 3) px[9 * w + end + 3 + i] = 210.toByte()
            for (y in 8..10) px[y * w + end + 4] = 210.toByte()
            word(second, end + 10)
        }
        return GrayImage(w, h, px)
    }

    val name = PlayerName(PlayerParams(), scale = 1.0, margin = 2, inner = 3)

    test("le pseudo appris est le premier mot qui revient le plus souvent en tête de ligne") {
        val kills = List(6) { listOf(LineSample(4, line("Kryy")), LineSample(4, line("Kryy", second = "Cum"))) }
        val assists = listOf(listOf(LineSample(4, line("Adm", second = "Kryy"))))
        val learned = name.learn(kills + assists).shouldNotBeNull()
        learned.width shouldBe 15
    }

    test("une ligne qui commence par le pseudo ressemble, une assistance (« coéquipier + joueur ») non") {
        val kills = List(6) { listOf(LineSample(4, line("Kryy"))) }
        val learned = name.learn(kills).shouldNotBeNull()
        name.score(listOf(LineSample(4, line("Kryy", second = "Cum"))), learned) shouldBeGreaterThan 0.9
        name.score(listOf(LineSample(4, line("Adm", second = "Kryy"))), learned) shouldBeLessThan 0.8
        // Bord gauche estimé quelques pixels trop à droite (trait du contour effacé) : le mot est retrouvé quand même.
        name.score(listOf(LineSample(1, line("Kryy", start = 8))), learned) shouldBeGreaterThan 0.9
    }

    test("une récompense du chat est une ligne verte de plus qui tient trois images") {
        val reader = RewardLines(threshold = 128, minPixels = 2, minBand = 2, maxBand = 10)
        fun chat(lines: Int): GrayImage {
            val px = ByteArray(20 * 60)
            repeat(lines) { l -> for (y in 0 until 5) for (x in 2..8) px[(50 - l * 11 + y) * 20 + x] = 255.toByte() }
            return GrayImage(20, 60, px)
        }
        val counts = listOf(0, 0, 0, 1, 1, 1, 1, 0, 1, 1, 1, 2, 2, 2).map { reader.count(chat(it)) }
        counts shouldBe listOf(0, 0, 0, 1, 1, 1, 1, 0, 1, 1, 1, 2, 2, 2)
        val times = counts.indices.map { (it * 100).milliseconds }
        // La ligne revue après un trou d'une image n'est pas nouvelle ; la seconde l'est.
        RewardLines.appearances(times, counts) shouldBe listOf(300.milliseconds, 1100.milliseconds)
    }
})
