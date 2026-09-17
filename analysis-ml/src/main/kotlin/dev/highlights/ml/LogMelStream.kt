package dev.highlights.ml

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Spectrogramme log-mel de YAMNet, calculé en flux (mémoire constante) : mêmes formules que
 * `features.waveform_to_log_mel_spectrogram_patches` (TensorFlow) — STFT 400/160/512 à fenêtre de Hann périodique,
 * 64 bandes mel HTK de 125 à 7500 Hz, log(mel + 0.001), patchs de 96 trames tous les 48.
 *
 * [onPatch] reçoit l'indice du patch, ses 96×64 valeurs et le niveau RMS maximal (dBFS) de ses trames.
 */
class LogMelStream(private val onPatch: (index: Int, patch: FloatArray, maxRmsDb: Double) -> Unit) {
    private val samples = FloatArray(WINDOW + HOP * 64)
    private var buffered = 0
    private val window = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / WINDOW) }
    private val re = DoubleArray(FFT)
    private val im = DoubleArray(FFT)

    private val frames = ArrayDeque<FloatArray>()
    private val frameRms = ArrayDeque<Double>()
    private var firstFrameIndex = 0
    private var frameCount = 0
    private var nextPatch = 0

    fun push(data: FloatArray, length: Int = data.size) {
        var offset = 0
        while (offset < length) {
            val n = minOf(length - offset, samples.size - buffered)
            System.arraycopy(data, offset, samples, buffered, n)
            buffered += n
            offset += n
            drainFrames()
        }
    }

    private fun drainFrames() {
        var start = 0
        while (start + WINDOW <= buffered) {
            computeFrame(start)
            start += HOP
        }
        if (start > 0) {
            System.arraycopy(samples, start, samples, 0, buffered - start)
            buffered -= start
        }
    }

    private fun computeFrame(start: Int) {
        var energy = 0.0
        for (i in 0 until FFT) {
            if (i < WINDOW) {
                val s = samples[start + i].toDouble()
                energy += s * s
                re[i] = s * window[i]
            } else {
                re[i] = 0.0
            }
            im[i] = 0.0
        }
        fft(re, im)
        val mel = FloatArray(MEL_BANDS)
        for (b in 0 until MEL_BANDS) {
            var sum = 0.0
            val weights = MEL_MATRIX[b]
            for (k in 0 until BINS) {
                val w = weights[k]
                if (w != 0.0) sum += w * sqrt(re[k] * re[k] + im[k] * im[k])
            }
            mel[b] = ln(sum + LOG_OFFSET).toFloat()
        }
        val rms = sqrt(energy / WINDOW)
        frames.addLast(mel)
        frameRms.addLast(if (rms > 0) 20 * log10(rms) else -200.0)
        frameCount++

        while (frameCount >= nextPatch * PATCH_HOP + PATCH_FRAMES) {
            val from = nextPatch * PATCH_HOP - firstFrameIndex
            val patch = FloatArray(PATCH_FRAMES * MEL_BANDS)
            var maxDb = -200.0
            for (f in 0 until PATCH_FRAMES) {
                System.arraycopy(frames[from + f], 0, patch, f * MEL_BANDS, MEL_BANDS)
                maxDb = maxOf(maxDb, frameRms[from + f])
            }
            onPatch(nextPatch, patch, maxDb)
            nextPatch++
            val keepFrom = nextPatch * PATCH_HOP
            while (firstFrameIndex < keepFrom && frames.isNotEmpty()) {
                frames.removeFirst()
                frameRms.removeFirst()
                firstFrameIndex++
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val WINDOW = 400
        const val HOP = 160
        const val FFT = 512
        const val BINS = FFT / 2 + 1
        const val MEL_BANDS = 64
        const val PATCH_FRAMES = 96
        const val PATCH_HOP = 48
        const val LOG_OFFSET = 0.001

        /** Début du patch [index] en secondes. */
        fun patchStartSeconds(index: Int) = index * PATCH_HOP * HOP / SAMPLE_RATE.toDouble()

        /** Durée couverte par un patch (96 trames + fenêtre). */
        val PATCH_SECONDS = ((PATCH_FRAMES - 1) * HOP + WINDOW) / SAMPLE_RATE.toDouble()

        /** Matrice mel de tf.signal.linear_to_mel_weight_matrix : [bande][bin], ligne DC nulle. */
        val MEL_MATRIX: Array<DoubleArray> = run {
            fun hzToMel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
            val nyquist = SAMPLE_RATE / 2.0
            val binsMel = DoubleArray(BINS) { k -> hzToMel(nyquist * k / (BINS - 1)) }
            val lo = hzToMel(125.0)
            val hi = hzToMel(7500.0)
            val edges = DoubleArray(MEL_BANDS + 2) { lo + (hi - lo) * it / (MEL_BANDS + 1) }
            Array(MEL_BANDS) { b ->
                DoubleArray(BINS) { k ->
                    if (k == 0) {
                        0.0
                    } else {
                        val lower = (binsMel[k] - edges[b]) / (edges[b + 1] - edges[b])
                        val upper = (edges[b + 2] - binsMel[k]) / (edges[b + 2] - edges[b + 1])
                        maxOf(0.0, minOf(lower, upper))
                    }
                }
            }
        }

        /** FFT complexe itérative en place (taille puissance de 2). */
        internal fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
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
                val angle = -2 * PI / len
                val wr = cos(angle)
                val wi = kotlin.math.sin(angle)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]
                        val ui = im[i + k]
                        val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ur + vr
                        im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr
                        im[i + k + len / 2] = ui - vi
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
}
