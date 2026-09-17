package dev.highlights.core

import dev.highlights.core.dsp.Fft
import dev.highlights.core.dsp.RealFft
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FftTest : FunSpec({

    /** Spectre de référence : FFT complexe sur le même signal, partie imaginaire nulle. */
    fun reference(signal: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val re = signal.copyOf()
        val im = DoubleArray(signal.size)
        Fft.transform(re, im)
        return re to im
    }

    test("FFT réelle : mêmes bins que la FFT complexe, à l'erreur d'arrondi près") {
        val random = Random(7)
        for (n in listOf(8, 64, 512, 1024)) {
            val signal = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
            val (re, im) = reference(signal)
            val fft = RealFft(n)
            val power = DoubleArray(fft.bins)
            fft.power(signal, power)
            val scale = (0 until n).maxOf { re[it] * re[it] + im[it] * im[it] }
            for (k in 0 until fft.bins) {
                abs(power[k] - (re[k] * re[k] + im[k] * im[k])) / scale shouldBeLessThan 1e-12
            }
        }
    }

    test("FFT réelle : sinusoïde pure, énergie sur son seul bin") {
        val n = 256
        val bin = 17
        val signal = DoubleArray(n) { cos(2 * PI * bin * it / n) + 0.5 * sin(2 * PI * 40 * it / n) }
        val fft = RealFft(n)
        val magnitude = DoubleArray(fft.bins)
        fft.magnitude(signal, magnitude)
        magnitude.indices.maxBy { magnitude[it] } shouldBe bin
        abs(magnitude[bin] - n / 2.0) shouldBeLessThan 1e-9
        abs(magnitude[40] - n / 4.0) shouldBeLessThan 1e-9
    }

    test("FFT réelle : lecture à partir d'un décalage dans le tampon") {
        val n = 64
        val random = Random(3)
        val signal = DoubleArray(n) { random.nextDouble() }
        val padded = DoubleArray(n * 3) { if (it in n until 2 * n) signal[it - n] else 99.0 }
        val direct = DoubleArray(RealFft(n).bins)
        val shifted = DoubleArray(RealFft(n).bins)
        RealFft(n).power(signal, direct)
        RealFft(n).power(padded, shifted, offset = n)
        direct.indices.forEach { abs(direct[it] - shifted[it]) shouldBeLessThan 1e-9 }
    }
})
