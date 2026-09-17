package dev.highlights.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT complexe itérative en place (taille puissance de 2). */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 0 && n and (n - 1) == 0) { "taille FFT non puissance de 2 : $n" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val angle = -2 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * FFT d'un signal réel de taille [n] : les [n]/2+1 bins utiles sont obtenus par une FFT complexe de taille n/2
 * (les échantillons pairs et impairs forment parties réelle et imaginaire), soit deux fois moins de calcul que
 * de transformer un signal complexe dont la moitié serait nulle.
 *
 * Les facteurs de rotation sont tabulés une fois : plus rapide, et plus juste qu'une récurrence qui dérive le
 * long des papillons. Instance réutilisable (tampons internes), à ne pas partager entre threads.
 */
class RealFft(val n: Int) {
    init {
        require(n >= 4 && n and (n - 1) == 0) { "taille FFT non puissance de 2 : $n" }
    }

    /** Nombre de bins produits : 0 (continu) à n/2 (Nyquist). */
    val bins = n / 2 + 1

    private val half = n / 2
    private val re = DoubleArray(half)
    private val im = DoubleArray(half)
    private val reversed = IntArray(half).also { table ->
        var j = 0
        for (i in 1 until half) {
            var bit = half shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            table[i] = j
        }
    }
    private val twiddleRe = DoubleArray(half / 2)
    private val twiddleIm = DoubleArray(half / 2)
    private val splitRe = DoubleArray(half / 2 + 1)
    private val splitIm = DoubleArray(half / 2 + 1)

    init {
        for (k in 0 until half / 2) {
            val angle = -2 * PI * k / half
            twiddleRe[k] = cos(angle)
            twiddleIm[k] = sin(angle)
        }
        for (k in 0..half / 2) {
            val angle = -2 * PI * k / n
            splitRe[k] = cos(angle)
            splitIm[k] = sin(angle)
        }
    }

    /**
     * Module au carré de chaque bin de [signal] (fenêtre déjà appliquée, [n] valeurs à partir de [offset]),
     * écrit dans [power] (au moins [bins] valeurs).
     */
    fun power(signal: DoubleArray, power: DoubleArray, offset: Int = 0) {
        transformHalf(signal, offset)
        untangle { k, real, imaginary -> power[k] = real * real + imaginary * imaginary }
    }

    /** Module de chaque bin, écrit dans [magnitude] (au moins [bins] valeurs). */
    fun magnitude(signal: DoubleArray, magnitude: DoubleArray, offset: Int = 0) {
        transformHalf(signal, offset)
        untangle { k, real, imaginary -> magnitude[k] = kotlin.math.sqrt(real * real + imaginary * imaginary) }
    }

    /** FFT complexe de taille n/2 sur les échantillons pairs (réel) et impairs (imaginaire). */
    private fun transformHalf(signal: DoubleArray, offset: Int) {
        for (i in 0 until half) {
            val j = reversed[i]
            re[j] = signal[offset + 2 * i]
            im[j] = signal[offset + 2 * i + 1]
        }
        var len = 2
        while (len <= half) {
            val step = half / len
            val halfLen = len / 2
            var i = 0
            while (i < half) {
                var t = 0
                for (k in 0 until halfLen) {
                    val a = i + k
                    val b = a + halfLen
                    val cr = twiddleRe[t]
                    val ci = twiddleIm[t]
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    t += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Sépare le spectre du signal réel à partir de la FFT de taille n/2. */
    private inline fun untangle(emit: (Int, Double, Double) -> Unit) {
        // Bins extrêmes : purement réels.
        emit(0, re[0] + im[0], 0.0)
        emit(half, re[0] - im[0], 0.0)
        for (k in 1 until half) {
            val mirror = half - k
            // Parties paire et impaire du spectre, puis recombinaison par le facteur de rotation e^(-2iπk/n).
            val evenRe = 0.5 * (re[k] + re[mirror])
            val evenIm = 0.5 * (im[k] - im[mirror])
            val oddRe = 0.5 * (im[k] + im[mirror])
            val oddIm = -0.5 * (re[k] - re[mirror])
            val wr: Double
            val wi: Double
            if (k <= half / 2) {
                wr = splitRe[k]
                wi = splitIm[k]
            } else {
                // e^(-2iπk/n) = -conj(e^(-2iπ(half-k)/n)) pour k > n/4.
                wr = -splitRe[mirror]
                wi = splitIm[mirror]
            }
            emit(k, evenRe + wr * oddRe - wi * oddIm, evenIm + wr * oddIm + wi * oddRe)
        }
    }
}
