package dev.highlights.analysis.audio

import dev.highlights.core.dsp.RealFft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Attaques du son du jeu, en flux : énergie des aigus (préaccentuation) sur 5,3 ms, un point toutes les 1,3 ms, comme
 * `ShotLocator` du montage. Une attaque est une montée brusque de cette énergie sur son passé récent ; un tir en est
 * une, un son qui enfle non.
 */
class OnsetStream(val rate: Int) {
    private val frame = (rate * FRAME_SECONDS).toInt().coerceAtLeast(16)
    val hop = (frame / 4).coerceAtLeast(1)
    private val squares = DoubleArray(frame)
    private var sum = 0.0
    private var previous = 0f
    private var count = 0L

    /** Niveau (dB) de chaque trame, dans l'ordre. */
    var levels = DoubleArray(1 shl 16)
        private set
    var size = 0
        private set

    fun push(samples: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = samples[i]
            val y = (x - 0.97f * previous).toDouble()
            previous = x
            val slot = (count % frame).toInt()
            sum += y * y - squares[slot]
            squares[slot] = y * y
            count++
            if (count >= frame && (count - frame) % hop == 0L) {
                if (size == levels.size) levels = levels.copyOf(size * 2)
                levels[size++] = 10 * log10(sum.coerceAtLeast(0.0) / frame + 1e-10)
            }
        }
    }

    /** Instant (s) du milieu de la trame [index]. */
    fun time(index: Int): Double = (index.toLong() * hop + frame / 2).toDouble() / rate

    /** Montée (dB) de chaque trame sur le maximum de ses 5 à 16 ms passées. */
    fun rises(): DoubleArray {
        val from = (PAST_FROM_SECONDS * rate / hop).toInt().coerceAtLeast(2)
        val to = (PAST_TO_SECONDS * rate / hop).toInt().coerceIn(1, from - 1)
        return DoubleArray(size) { i ->
            if (i < from) 0.0 else levels[i] - (i - from..i - to).maxOf { levels[it] }
        }
    }

    /**
     * Attaques d'au moins [minRiseDb] : un maximum local de la montée par [minGap] secondes (une détonation et son écho
     * ne font qu'un tir). Rend l'index de chaque attaque et son niveau (dB) au plus fort, juste après.
     */
    fun onsets(minRiseDb: Double, minGap: Double): List<Onset> {
        val rise = rises()
        val radius = (minGap * rate / hop).toInt().coerceAtLeast(1)
        val peakSpan = (PEAK_SECONDS * rate / hop).toInt().coerceAtLeast(1)
        val result = mutableListOf<Onset>()
        for (i in rise.indices) {
            if (rise[i] < minRiseDb) continue
            val lo = maxOf(0, i - radius)
            val hi = minOf(rise.lastIndex, i + radius)
            if ((lo..hi).any { rise[it] > rise[i] || (rise[it] == rise[i] && it < i) }) continue
            val level = (i..minOf(size - 1, i + peakSpan)).maxOf { levels[it] }
            result += Onset(i, time(i), rise[i], level)
        }
        return result
    }

    data class Onset(val index: Int, val seconds: Double, val riseDb: Double, val levelDb: Double)

    private companion object {
        const val FRAME_SECONDS = 256.0 / 48_000
        const val PAST_FROM_SECONDS = 12 * 64.0 / 48_000
        const val PAST_TO_SECONDS = 4 * 64.0 / 48_000

        /** Le niveau d'une attaque est pris au plus fort de ses 20 premières millisecondes. */
        const val PEAK_SECONDS = 0.02
    }
}

/**
 * Tirs du joueur parmi les attaques : son arme est ce qu'on entend le plus fort, les tirs adverses, les pas et les
 * capacités arrivent atténués par la distance. Référence : le [percentile] des niveaux d'attaque de la partie ; une
 * attaque plus faible de plus de [belowDb] n'est pas un tir du joueur.
 */
object ShotFilter {
    fun keep(onsets: List<OnsetStream.Onset>, percentile: Double, belowDb: Double): List<OnsetStream.Onset> {
        if (onsets.isEmpty()) return onsets
        val sorted = onsets.map { it.levelDb }.sorted()
        val reference = sorted[((sorted.size - 1) * percentile.coerceIn(0.0, 1.0)).toInt()]
        return onsets.filter { it.levelDb >= reference - belowDb }
    }
}

/**
 * Spectre en bandes, en flux : trame de 21 ms, un point toutes les 5,3 ms, énergie (dB) de [bands] bandes réparties en
 * échelle logarithmique de [minHz] à [maxHz]. Sert d'empreinte à un son précis du jeu (le « dink » d'un tir à la tête),
 * comparée par [TemplateMatcher].
 */
class BandSpectrum(val rate: Int, val bands: Int, minHz: Double, maxHz: Double, private val onFrame: (DoubleArray) -> Unit) {
    val size = Integer.highestOneBit((rate * FRAME_SECONDS).toInt().coerceAtLeast(64))
    val hop = size / 4
    private val fft = RealFft(size)
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
    private val ring = FloatArray(size)
    private val frame = DoubleArray(size)
    private val power = DoubleArray(fft.bins)
    private var count = 0L

    /** Bins de chaque bande : [edges][b] inclus à [edges][b + 1] exclu. */
    private val edges = IntArray(bands + 1) { b ->
        val hz = minHz * (maxHz / minHz).pow(b.toDouble() / bands)
        (hz * size / rate).toInt().coerceIn(1, fft.bins - 1)
    }.also { e -> for (b in 1 until e.size) if (e[b] <= e[b - 1]) e[b] = minOf(e[b - 1] + 1, fft.bins) }

    init {
        require(maxHz > minHz && minHz > 0) { "bandes : minHz doit être positif et inférieur à maxHz" }
        require(maxHz <= rate / 2.0) { "bandes : maxHz ($maxHz) au-delà de la moitié de la fréquence d'échantillonnage ($rate)" }
    }

    fun push(samples: FloatArray, n: Int) {
        for (i in 0 until n) {
            ring[(count % size).toInt()] = samples[i]
            count++
            if (count >= size && (count - size) % hop == 0L) emit()
        }
    }

    /** Instant (s) du début de la trame [index]. */
    fun time(index: Int): Double = index.toDouble() * hop / rate

    private fun emit() {
        val start = (count % size).toInt()
        for (k in 0 until size) frame[k] = ring[(start + k) % size] * window[k]
        fft.power(frame, power)
        val out = DoubleArray(bands) { b ->
            var sum = 0.0
            for (k in edges[b] until minOf(fft.bins, maxOf(edges[b] + 1, edges[b + 1]))) sum += power[k]
            10 * log10(sum + 1e-12)
        }
        // Seule la forme du spectre compte : les bandes vides d'un gabarit propre (-90 dB) et le fond sonore d'une partie
        // (-50 dB) ne doivent pas faire la différence. Tout ce qui est à plus de DYNAMIC_DB sous la bande la plus forte
        // est ramené à ce plancher.
        val floor = out.max() - DYNAMIC_DB
        for (b in out.indices) if (out[b] < floor) out[b] = floor
        onFrame(out)
    }

    private companion object {
        const val FRAME_SECONDS = 1024.0 / 48_000
        const val DYNAMIC_DB = 40.0
    }
}

/**
 * Ressemblance d'un passage à un gabarit (suite de spectres en bandes) : corrélation normalisée des énergies en dB,
 * de -1 à 1. Insensible au volume (le gabarit plus fort ou plus faible de quelques dB ressemble autant) ; un son d'une
 * autre couleur ou d'un autre rythme, non. Les trames du passage arrivent une à une ; chacune donne la ressemblance du
 * passage qui finit sur elle.
 */
class TemplateMatcher(template: List<DoubleArray>) {
    val length = template.size
    private val bands = template.first().size
    private val t = DoubleArray(length * bands)
    private val tNorm: Double
    private val history = ArrayDeque<DoubleArray>()

    init {
        require(template.isNotEmpty() && template.all { it.size == bands }) { "gabarit vide ou incohérent" }
        val mean = template.sumOf { it.sum() } / (length * bands)
        for (f in 0 until length) for (b in 0 until bands) t[f * bands + b] = template[f][b] - mean
        tNorm = sqrt(t.sumOf { it * it })
        require(tNorm > 0) { "gabarit sans relief (silence ou son constant)" }
    }

    /** Ressemblance du passage qui finit sur [frame], ou NaN tant que le passage est plus court que le gabarit. */
    fun push(frame: DoubleArray): Double {
        history.addLast(frame)
        if (history.size > length) history.removeFirst()
        if (history.size < length) return Double.NaN
        var mean = 0.0
        for (f in history) for (v in f) mean += v
        mean /= length * bands
        var dot = 0.0
        var norm = 0.0
        var i = 0
        for (f in history) {
            for (v in f) {
                val x = v - mean
                dot += x * t[i++]
                norm += x * x
            }
        }
        return if (norm <= 0) 0.0 else dot / (sqrt(norm) * tNorm)
    }

    companion object {
        /** Empreinte d'un gabarit entier : ses spectres en bandes, silences du début et de la fin retirés. */
        fun fingerprint(samples: FloatArray, rate: Int, bands: Int, minHz: Double, maxHz: Double, silenceDb: Double = 30.0): List<DoubleArray> {
            val frames = mutableListOf<DoubleArray>()
            BandSpectrum(rate, bands, minHz, maxHz) { frames += it }.push(samples, samples.size)
            if (frames.isEmpty()) return frames
            // Une trame compte si son énergie totale est à moins de silenceDb de la plus forte.
            val energy = frames.map { f -> 10 * log10(f.sumOf { 10.0.pow(it / 10) }) }
            val loud = energy.max() - silenceDb
            val first = energy.indexOfFirst { it >= loud }
            val last = energy.indexOfLast { it >= loud }
            return frames.subList(first, last + 1).toList()
        }

        /** Maxima locaux au-dessus de [threshold], un par [minGap] trames : les instants où le son est reconnu. */
        fun peaks(scores: FloatArray, count: Int, threshold: Double, minGap: Int): List<Int> {
            val result = mutableListOf<Int>()
            for (i in 0 until count) {
                val v = scores[i]
                if (v.isNaN() || v < threshold) continue
                val lo = maxOf(0, i - minGap)
                val hi = minOf(count - 1, i + minGap)
                if ((lo..hi).any { j -> !scores[j].isNaN() && (scores[j] > v || (scores[j] == v && j < i)) }) continue
                result += i
            }
            return result
        }
    }
}
