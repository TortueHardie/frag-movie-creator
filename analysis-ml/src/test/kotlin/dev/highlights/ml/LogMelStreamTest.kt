package dev.highlights.ml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Le seuil de silence évite des FFT, il ne doit rien changer d'autre : les patchs analysés doivent être
 * exactement ceux que produit le calcul complet, valeur par valeur.
 */
class LogMelStreamTest : FunSpec({

    /** Signal alterné : 3 s de silence, 3 s de sinusoïde, etc. */
    fun signal(seconds: Int): FloatArray {
        val random = Random(11)
        return FloatArray(LogMelStream.SAMPLE_RATE * seconds) { i ->
            val loud = (i / (LogMelStream.SAMPLE_RATE * 3)) % 2 == 1
            if (loud) (0.4 * sin(2 * PI * 440 * i / LogMelStream.SAMPLE_RATE) + 0.01 * random.nextDouble()).toFloat() else 0f
        }
    }

    test("patchs identiques avec ou sans seuil de silence, seules les FFT inutiles disparaissent") {
        val wave = signal(20)
        val threshold = -50.0

        val all = linkedMapOf<Int, FloatArray>()
        val levels = mutableMapOf<Int, Double>()
        LogMelStream { index, patch, maxDb ->
            all[index] = patch
            levels[index] = maxDb
        }.push(wave)

        val kept = linkedMapOf<Int, FloatArray>()
        val skipped = mutableListOf<Int>()
        LogMelStream(silenceDb = threshold, onSkipped = { skipped += it }) { index, patch, _ -> kept[index] = patch }
            .push(wave)

        (kept.keys + skipped).sorted() shouldContainExactly all.keys.sorted()
        skipped shouldContainExactly all.keys.filter { levels.getValue(it) < threshold }
        kept.keys.forEach { index -> kept.getValue(index).toList() shouldContainExactly all.getValue(index).toList() }
        kept.size shouldBe all.size - skipped.size
    }

    test("découpage en morceaux irréguliers sans effet sur les patchs") {
        val wave = signal(12)
        val whole = mutableListOf<Pair<Int, FloatArray>>()
        LogMelStream { index, patch, _ -> whole += index to patch }.push(wave)

        val chunked = mutableListOf<Pair<Int, FloatArray>>()
        val stream = LogMelStream(silenceDb = -50.0) { index, patch, _ -> chunked += index to patch }
        var offset = 0
        var size = 1
        while (offset < wave.size) {
            val n = minOf(size, wave.size - offset)
            stream.push(wave.copyOfRange(offset, offset + n))
            offset += n
            size = if (size > 50_000) 1 else size * 3
        }
        val loud = whole.filter { (index, _) -> chunked.any { it.first == index } }
        chunked.map { it.first } shouldContainExactly loud.map { it.first }
        chunked.forEachIndexed { i, (_, patch) -> patch.toList() shouldContainExactly loud[i].second.toList() }
    }
})
