package dev.highlights.ml

import dev.highlights.core.dsp.RealFft
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Spectrogramme log-mel de YAMNet, calculé en flux (mémoire constante) : mêmes formules que
 * `features.waveform_to_log_mel_spectrogram_patches` (TensorFlow) — STFT 400/160/512 à fenêtre de Hann périodique,
 * 64 bandes mel HTK de 125 à 7500 Hz, log(mel + 0.001), patchs de 96 trames tous les 48.
 *
 * Le niveau d'une trame ne demande pas de spectre : quand tout un patch est sous [silenceDb] (micro coupé, le cas
 * le plus fréquent), il part directement dans [onSkipped] et aucune FFT n'est calculée pour lui.
 *
 * [onPatch] reçoit l'indice du patch, ses 96×64 valeurs et le niveau RMS maximal (dBFS) de ses trames.
 */
class LogMelStream(
    private val silenceDb: Double = Double.NEGATIVE_INFINITY,
    private val onSkipped: (index: Int) -> Unit = {},
    private val onPatch: (index: Int, patch: FloatArray, maxRmsDb: Double) -> Unit,
) {
    private val samples = FloatArray(1 shl 16)

    /** Indice absolu (dans le flux) du premier échantillon conservé. */
    private var bufferStart = 0L
    private var buffered = 0

    private val window = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / WINDOW) }
    private val fft = RealFft(FFT)
    // Seules les WINDOW premieres valeurs changent d'une trame a l'autre : le bourrage de zeros reste acquis.
    private val windowed = DoubleArray(FFT)
    private val magnitude = DoubleArray(fft.bins)

    /** Niveaux des trames déjà mesurées, à partir de [firstMeasured]. */
    private val frameRms = ArrayDeque<Double>()
    private var firstMeasured = 0
    private var nextFrame = 0
    private var nextPatch = 0

    /** Mels des trames communes au patch précédent et au suivant : calculées une seule fois. */
    private var carry: Array<FloatArray>? = null
    private var carryFirstFrame = -1

    fun push(data: FloatArray, length: Int = data.size) {
        var offset = 0
        while (offset < length) {
            val n = minOf(length - offset, samples.size - buffered)
            System.arraycopy(data, offset, samples, buffered, n)
            buffered += n
            offset += n
            drain()
        }
    }

    private fun drain() {
        // Niveau des trames complètes : quelques centaines de multiplications, sans FFT.
        while (absolute(nextFrame) + WINDOW <= bufferStart + buffered) {
            val start = (absolute(nextFrame) - bufferStart).toInt()
            var energy = 0.0
            for (i in 0 until WINDOW) {
                val s = samples[start + i].toDouble()
                energy += s * s
            }
            val rms = sqrt(energy / WINDOW)
            frameRms.addLast(if (rms > 0) 20 * log10(rms) else -200.0)
            nextFrame++
            while (nextFrame >= nextPatch * PATCH_HOP + PATCH_FRAMES) emitPatch()
        }
        // Les échantillons antérieurs au prochain patch ne resserviront plus.
        val keepFrom = minOf(absolute(nextPatch * PATCH_HOP), absolute(nextFrame))
        val drop = (keepFrom - bufferStart).toInt()
        if (drop > 0) {
            System.arraycopy(samples, drop, samples, 0, buffered - drop)
            buffered -= drop
            bufferStart = keepFrom
        }
    }

    private fun emitPatch() {
        val first = nextPatch * PATCH_HOP
        var maxDb = -200.0
        for (f in first until first + PATCH_FRAMES) maxDb = maxOf(maxDb, frameRms[f - firstMeasured])

        if (maxDb < silenceDb) {
            onSkipped(nextPatch)
            carry = null
        } else {
            val reused = carry.takeIf { carryFirstFrame == first }
            val mels = Array(PATCH_FRAMES) { f ->
                reused?.getOrNull(f) ?: mel(first + f)
            }
            val patch = FloatArray(PATCH_FRAMES * MEL_BANDS)
            for (f in 0 until PATCH_FRAMES) System.arraycopy(mels[f], 0, patch, f * MEL_BANDS, MEL_BANDS)
            carry = Array(PATCH_FRAMES - PATCH_HOP) { mels[PATCH_HOP + it] }
            carryFirstFrame = first + PATCH_HOP
            onPatch(nextPatch, patch, maxDb)
        }

        nextPatch++
        // Niveaux devenus inutiles (les patchs suivants commencent plus loin).
        val keep = nextPatch * PATCH_HOP
        while (firstMeasured < keep && frameRms.isNotEmpty()) {
            frameRms.removeFirst()
            firstMeasured++
        }
    }

    /** Log-mel d'une trame : la seule étape qui demande une FFT. */
    private fun mel(frame: Int): FloatArray {
        val start = (absolute(frame) - bufferStart).toInt()
        for (i in 0 until WINDOW) windowed[i] = samples[start + i] * window[i]
        fft.magnitude(windowed, magnitude)
        val out = FloatArray(MEL_BANDS)
        for (b in 0 until MEL_BANDS) {
            val weights = MEL_WEIGHTS[b]
            val from = MEL_FIRST_BIN[b]
            var sum = 0.0
            for (k in weights.indices) sum += weights[k] * magnitude[from + k]
            out[b] = ln(sum + LOG_OFFSET).toFloat()
        }
        return out
    }

    private fun absolute(frame: Int): Long = frame.toLong() * HOP

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

        /**
         * Même matrice, réduite à ses poids non nuls : une bande mel ne couvre qu'une poignée de bins sur 257,
         * inutile de parcourir toute la ligne à chaque trame.
         */
        private val MEL_FIRST_BIN = IntArray(MEL_BANDS) { b ->
            MEL_MATRIX[b].indexOfFirst { it != 0.0 }.coerceAtLeast(0)
        }
        private val MEL_WEIGHTS: Array<DoubleArray> = Array(MEL_BANDS) { b ->
            val row = MEL_MATRIX[b]
            val last = row.indexOfLast { it != 0.0 }
            if (last < MEL_FIRST_BIN[b]) DoubleArray(0) else row.copyOfRange(MEL_FIRST_BIN[b], last + 1)
        }
    }
}
