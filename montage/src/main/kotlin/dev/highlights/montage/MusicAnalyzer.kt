package dev.highlights.montage

import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.dsp.Fft
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Niveau d'intensité d'une section : dicte le rythme des coupes. */
enum class Intensity { LOW, MID, HIGH }

/**
 * Section de la musique (intro, couplet, montée, drop, breakdown…), délimitée par des changements de timbre ou de
 * volume et alignée sur les mesures. [endBeat] est exclusif.
 */
data class MusicSection(
    val startBeat: Int,
    val endBeat: Int,
    /** Volume moyen (RMS, dB) des temps de la section. */
    val loudnessDb: Double,
    /** 0 = partie la plus calme du morceau, 1 = la plus intense. */
    val intensity: Double,
) {
    val beats: Int get() = endBeat - startBeat
    val level: Intensity
        get() = when {
            intensity >= 0.75 -> Intensity.HIGH
            intensity >= 0.4 -> Intensity.MID
            else -> Intensity.LOW
        }

    operator fun contains(beat: Int): Boolean = beat in startBeat until endBeat
}

/**
 * Structure rythmique et musicale d'un morceau.
 * [beats] : instants des temps ; [downbeatPhase] : indice modulo 4 des premiers temps de mesure ;
 * [beatAccent] : force de l'attaque sur chaque temps (0..1) ; [sections] : structure ; [dropBeat] : temps où commence la
 * partie la plus intense (plus gros saut d'énergie entre deux sections).
 */
data class MusicAnalysis(
    val file: Path,
    val duration: Duration,
    val bpm: Double,
    val beats: List<Duration>,
    val downbeatPhase: Int,
    val beatEnergy: DoubleArray,
    val beatAccent: DoubleArray,
    val sections: List<MusicSection>,
    val dropBeat: Int,
) {
    /** Période moyenne (régression sur les temps détectés) : sert à extrapoler au-delà des temps détectés. */
    val beatPeriod: Duration get() = (60.0 / bpm).seconds

    fun isDownbeat(beat: Int): Boolean = ((beat - downbeatPhase) % 4 + 4) % 4 == 0

    /** Instant du temps [beat], extrapolé au tempo moyen hors des temps détectés. */
    fun beatTime(beat: Int): Duration = when {
        beat < 0 -> beats.first() + beatPeriod * beat
        beat < beats.size -> beats[beat]
        else -> beats.last() + beatPeriod * (beat - beats.lastIndex)
    }

    fun sectionIndexAt(beat: Int): Int = sections.indexOfLast { beat >= it.startBeat }.coerceAtLeast(0)

    fun sectionAt(beat: Int): MusicSection = sections[sectionIndexAt(beat)]
}

object MusicAnalyzer {
    const val SAMPLE_RATE = 22050
    private const val FRAME = 1024
    private const val HOP = 256
    private const val BINS = FRAME / 2
    private const val MEL_BANDS = 40
    private const val FPS = SAMPLE_RATE.toDouble() / HOP
    private const val BEATS_PER_BAR = 4
    /** Demi-largeur du noyau de nouveauté (en mesures) : une section fait au moins 4 mesures. */
    private const val KERNEL_BARS = 4
    /** Nouveauté minimale d’une frontière de section (timbre : écart de similarité cosinus ; volume : 6 dB = 1). */
    private const val NOVELTY_FLOOR = 0.25

    suspend fun analyze(ffmpeg: FfmpegService, file: Path): MusicAnalysis {
        if (!file.isRegularFile()) throw InputException("Musique introuvable : $file")
        val chunks = mutableListOf<FloatArray>()
        var total = 0
        ffmpeg.run(
            FfmpegCommand(
                listOf("-i", file.toString(), "-vn", "-ac", "1", "-ar", "$SAMPLE_RATE", "-f", "f32le", "-acodec", "pcm_f32le", "pipe:1"),
                "analyse musicale ${file.fileName}",
            ),
            StdoutHandler.Binary { input ->
                val data = DataInputStream(input.buffered(1 shl 16))
                val bytes = ByteArray(1 shl 16)
                var carry = 0
                while (true) {
                    val read = data.read(bytes, carry, bytes.size - carry)
                    if (read < 0) break
                    val available = carry + read
                    val whole = available / 4
                    val floats = FloatArray(whole)
                    ByteBuffer.wrap(bytes, 0, whole * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                    chunks += floats
                    total += whole
                    carry = available - whole * 4
                    if (carry > 0) System.arraycopy(bytes, whole * 4, bytes, 0, carry)
                }
            },
        )
        val samples = FloatArray(total)
        var offset = 0
        chunks.forEach { System.arraycopy(it, 0, samples, offset, it.size); offset += it.size }
        return analyzeSamples(samples, file).also { a ->
            log.info {
                "Musique ${file.fileName} : ${"%.1f".format(a.bpm)} BPM, ${a.beats.size} temps, ${a.sections.size} sections, " +
                    "drop au temps ${a.dropBeat} (${a.beatTime(a.dropBeat)})"
            }
        }
    }

    fun analyzeSamples(samples: FloatArray, file: Path = Path.of("musique")): MusicAnalysis {
        val duration = (samples.size.toDouble() / SAMPLE_RATE).seconds
        if (samples.size < SAMPLE_RATE * 5) throw HighlightsException("Musique trop courte (${duration.inWholeSeconds} s) pour détecter un tempo")

        val spec = melSpectrogram(samples)
        val onset = onsetEnvelope(spec)
        // Tempo sur une enveloppe plus lissée : les pics fins (~12 ms) rendent l'autocorrélation sensible à la quantification du lag.
        val bpmEstimate = estimateTempo(smooth(onset, sigma = 3.0), FPS)
        val beatFrames = trackBeats(onset, FPS * 60.0 / bpmEstimate)
        if (beatFrames.size < 8) throw HighlightsException("Tempo introuvable dans ${file.fileName} (musique sans rythme marqué ?)")

        // Une trame est datée par son centre : l'attaque domine le flux quand elle y arrive.
        val beats = beatFrames.map { ((it * HOP + FRAME / 2).toDouble() / SAMPLE_RATE).seconds }
        val n = beats.size
        // Régression linéaire temps = a + période × indice : tempo moyen plus précis que l'estimation initiale.
        val xs = DoubleArray(n) { it.toDouble() }
        val ys = DoubleArray(n) { beats[it].inWholeMicroseconds / 1e6 }
        val mx = xs.average()
        val my = ys.average()
        val period = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.indices.sumOf { (xs[it] - mx) * (xs[it] - mx) }
        val bpm = 60.0 / period

        // --- caractéristiques synchrones aux temps
        val frameOf = IntArray(n + 1) { i -> if (i < n) beatFrames[i] else minOf(spec.frames, beatFrames.last() + (period * FPS).roundToInt()) }
        val beatMel = Array(n) { i ->
            val from = frameOf[i]
            val to = maxOf(from + 1, frameOf[i + 1]).coerceAtMost(spec.frames)
            DoubleArray(MEL_BANDS) { k -> (from until to).sumOf { spec.mel[it][k] } / (to - from) }
        }
        val energy = DoubleArray(n) { i ->
            val from = (beats[i].inWholeMicroseconds * SAMPLE_RATE / 1_000_000).toInt().coerceIn(0, samples.size)
            val to = (if (i + 1 < n) beats[i + 1].inWholeMicroseconds * SAMPLE_RATE / 1_000_000 else from + (period * SAMPLE_RATE).toLong()).toInt().coerceIn(from, samples.size)
            if (to <= from) 0.0 else sqrt((from until to).sumOf { samples[it].toDouble() * samples[it] } / (to - from))
        }
        val loudness = DoubleArray(n) { 20 * log10(energy[it] + 1e-5) }
        val rawAccent = DoubleArray(n) { i -> (maxOf(0, frameOf[i] - 2)..minOf(onset.lastIndex, frameOf[i] + 2)).maxOf { onset[it] } }
        val accentScale = percentile(rawAccent, 0.95).takeIf { it > 1e-9 } ?: 1.0
        val accent = DoubleArray(n) { (rawAccent[it] / accentScale).coerceIn(0.0, 1.0) }
        val low = DoubleArray(n) { i -> (frameOf[i]..minOf(frameOf[i] + 2, spec.frames - 1)).sumOf { spec.lowEnergy[it] } }
        val novelty = DoubleArray(n) { i -> if (i == 0) 0.0 else 1 - cosine(beatMel[i], beatMel[i - 1]) }

        // Premier temps de mesure : accent des basses (non compressé), attaque et changement de timbre.
        val lowNorm = normalize(low)
        val novNorm = normalize(novelty)
        val phaseScores = DoubleArray(BEATS_PER_BAR)
        for (i in 0 until n) phaseScores[i % BEATS_PER_BAR] += lowNorm[i] + 0.5 * accent[i] + 0.5 * novNorm[i]
        val phase = phaseScores.indices.maxBy { phaseScores[it] }

        val sections = detectSections(beatMel, loudness, accent, phase, n)
        val drop = findDrop(sections)
        return MusicAnalysis(file, duration, bpm, beats, phase, energy, accent, sections, drop)
    }

    // ------------------------------------------------------------------ spectre

    private class Spectrogram(val mel: Array<DoubleArray>, val lowEnergy: DoubleArray) {
        val frames: Int get() = mel.size
    }

    private class MelFilter(val first: Int, val weights: DoubleArray)

    private fun hzToMel(f: Double) = 2595 * log10(1 + f / 700)
    private fun melToHz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)

    /** Banc de filtres triangulaires, [MEL_BANDS] bandes entre 30 Hz et Nyquist. */
    private val filters: List<MelFilter> by lazy {
        val lo = hzToMel(30.0)
        val hi = hzToMel(SAMPLE_RATE / 2.0)
        val points = DoubleArray(MEL_BANDS + 2) { melToHz(lo + (hi - lo) * it / (MEL_BANDS + 1)) * FRAME / SAMPLE_RATE }
        (0 until MEL_BANDS).map { b ->
            val left = points[b]
            val center = points[b + 1]
            val right = points[b + 2]
            val first = left.toInt().coerceAtLeast(1)
            val last = right.toInt().coerceAtMost(BINS - 1)
            val weights = DoubleArray(maxOf(1, last - first + 1)) { k ->
                val bin = (first + k).toDouble()
                when {
                    bin < center -> ((bin - left) / (center - left)).coerceIn(0.0, 1.0)
                    else -> ((right - bin) / (right - center)).coerceIn(0.0, 1.0)
                }
            }
            MelFilter(first, weights)
        }
    }

    /** Fréquence centrale des bandes en Hz ; les bandes sous ~220 Hz portent grosse caisse et basse. */
    private val lowBands: Int by lazy {
        val lo = hzToMel(30.0)
        val hi = hzToMel(SAMPLE_RATE / 2.0)
        (0 until MEL_BANDS).count { melToHz(lo + (hi - lo) * (it + 1) / (MEL_BANDS + 1)) < 220.0 }.coerceAtLeast(2)
    }

    private fun melSpectrogram(samples: FloatArray): Spectrogram {
        val frames = (samples.size - FRAME) / HOP + 1
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / FRAME) }
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        val power = DoubleArray(BINS)
        val lowBins = (200.0 * FRAME / SAMPLE_RATE).roundToInt().coerceAtLeast(2)
        val mel = Array(frames) { DoubleArray(MEL_BANDS) }
        val lowEnergy = DoubleArray(frames)
        for (f in 0 until frames) {
            val start = f * HOP
            for (i in 0 until FRAME) {
                re[i] = samples[start + i] * window[i]
                im[i] = 0.0
            }
            Fft.transform(re, im)
            for (k in 0 until BINS) power[k] = re[k] * re[k] + im[k] * im[k]
            for (k in 1 until lowBins) lowEnergy[f] += power[k]
            val row = mel[f]
            filters.forEachIndexed { b, filter ->
                var acc = 0.0
                for (k in filter.weights.indices) acc += filter.weights[k] * power[filter.first + k]
                // Compression logarithmique de l'amplitude : les attaques douces comptent aussi.
                row[b] = ln(1.0 + 100.0 * sqrt(acc))
            }
        }
        return Spectrogram(mel, lowEnergy)
    }

    /**
     * Flux spectral « SuperFlux » (Böck 2013) : différence positive avec le maximum des bandes voisines de la trame
     * précédente (insensible au vibrato), toutes bandes + basses renforcées (la grosse caisse porte le temps).
     */
    private fun onsetEnvelope(spec: Spectrogram): DoubleArray {
        val frames = spec.frames
        val all = DoubleArray(frames)
        val low = DoubleArray(frames)
        for (f in 1 until frames) {
            val cur = spec.mel[f]
            val prev = spec.mel[f - 1]
            var a = 0.0
            var l = 0.0
            for (k in 0 until MEL_BANDS) {
                var ref = prev[k]
                if (k > 0) ref = max(ref, prev[k - 1])
                if (k + 1 < MEL_BANDS) ref = max(ref, prev[k + 1])
                val d = cur[k] - ref
                if (d > 0) {
                    a += d
                    if (k < lowBands) l += d
                }
            }
            all[f] = a
            low[f] = l
        }
        val allN = normalize(all)
        val lowN = normalize(low)
        // Lissage gaussien : les pics d'attaque se recouvrent même quand la période ne tombe pas sur un nombre entier de trames.
        return smooth(DoubleArray(frames) { allN[it] + 1.5 * lowN[it] }, sigma = 2.0)
    }

    private fun smooth(values: DoubleArray, sigma: Double): DoubleArray {
        val radius = (3 * sigma).toInt()
        val kernel = DoubleArray(2 * radius + 1) { exp(-0.5 * ((it - radius) / sigma).let { x -> x * x }) }
        val norm = kernel.sum()
        return DoubleArray(values.size) { i ->
            var acc = 0.0
            for (k in kernel.indices) {
                val j = i + k - radius
                if (j in values.indices) acc += values[j] * kernel[k]
            }
            acc / norm
        }
    }

    private fun normalize(values: DoubleArray): DoubleArray {
        if (values.isEmpty()) return values
        val mean = values.average()
        val std = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size).takeIf { it > 1e-9 } ?: 1.0
        return DoubleArray(values.size) { ((values[it] - mean) / std).coerceAtLeast(0.0) }
    }

    private fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        return sorted[(p * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (k in a.indices) {
            dot += a[k] * b[k]
            na += a[k] * a[k]
            nb += b[k] * b[k]
        }
        return if (na < 1e-12 || nb < 1e-12) 0.0 else dot / sqrt(na * nb)
    }

    // ------------------------------------------------------------------ tempo et temps

    /** Autocorrélation de l'enveloppe d'attaques, pondérée autour de 120 BPM (log-normale, une octave). */
    internal fun estimateTempo(onset: DoubleArray, fps: Double): Double {
        val minLag = (fps * 60.0 / 200.0).toInt()
        val maxLag = (fps * 60.0 / 60.0).toInt().coerceAtMost(onset.size / 2)
        val mean = onset.average()
        val centered = DoubleArray(onset.size) { onset[it] - mean }
        var bestLag = minLag
        var bestScore = Double.NEGATIVE_INFINITY
        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            var r = 0.0
            for (t in 0 until centered.size - lag) r += centered[t] * centered[t + lag]
            val bpm = 60.0 * fps / lag
            val weight = exp(-0.5 * (log2(bpm / 120.0) / 1.0).let { it * it })
            scores[lag] = r * weight
            if (scores[lag] > bestScore) {
                bestScore = scores[lag]
                bestLag = lag
            }
        }
        // Interpolation parabolique autour du maximum.
        val refined = if (bestLag in (minLag + 1) until maxLag) {
            val a = scores[bestLag - 1]
            val b = scores[bestLag]
            val c = scores[bestLag + 1]
            val denom = a - 2 * b + c
            if (denom != 0.0) bestLag + 0.5 * (a - c) / denom else bestLag.toDouble()
        } else {
            bestLag.toDouble()
        }
        return 60.0 * fps / refined
    }

    /** Suivi de temps par programmation dynamique (Ellis 2007) : attaques fortes et intervalles réguliers. */
    internal fun trackBeats(onset: DoubleArray, period: Double, tightness: Double = 100.0): List<Int> {
        val n = onset.size
        val cumulative = DoubleArray(n)
        val backlink = IntArray(n) { -1 }
        val minBack = (period / 2).roundToInt().coerceAtLeast(1)
        val maxBack = (period * 2).roundToInt()
        for (t in 0 until n) {
            var best = Double.NEGATIVE_INFINITY
            var link = -1
            for (prev in maxOf(0, t - maxBack)..(t - minBack)) {
                val gap = (t - prev) / period
                val score = cumulative[prev] - tightness * ln(gap).let { it * it }
                if (score > best) {
                    best = score
                    link = prev
                }
            }
            cumulative[t] = onset[t] + if (link >= 0) best.coerceAtLeast(0.0) else 0.0
            backlink[t] = if (link >= 0 && best > 0) link else -1
        }
        // Dernier temps : meilleur score cumulé dans la dernière période.
        var t = (maxOf(0, n - period.roundToInt()) until n).maxByOrNull { cumulative[it] } ?: return emptyList()
        val beats = mutableListOf<Int>()
        while (t >= 0) {
            beats += t
            t = backlink[t]
        }
        return beats.reversed()
    }

    // ------------------------------------------------------------------ structure

    /**
     * Sections par nouveauté (Foote 2000) sur une matrice d'auto-similarité de timbre à l'échelle de la mesure, combinée
     * aux sauts de volume ; frontières sur des premiers temps de mesure, sections d'au moins [KERNEL_BARS] mesures.
     */
    private fun detectSections(beatMel: Array<DoubleArray>, loudness: DoubleArray, accent: DoubleArray, phase: Int, n: Int): List<MusicSection> {
        val bars = (n - phase) / BEATS_PER_BAR
        fun barStart(k: Int) = phase + k * BEATS_PER_BAR
        val boundaries = mutableListOf(0)
        if (bars >= 2 * KERNEL_BARS + 1) {
            // Timbre par mesure, centré par bande puis comparé en cosinus : ressemblance de -1 à 1.
            val feat = Array(bars) { k ->
                DoubleArray(MEL_BANDS) { b -> (barStart(k) until barStart(k) + BEATS_PER_BAR).sumOf { beatMel[it][b] } / BEATS_PER_BAR }
            }
            for (b in 0 until MEL_BANDS) {
                val mean = feat.sumOf { it[b] } / bars
                feat.forEach { it[b] -= mean }
            }
            val ssm = Array(bars) { i -> DoubleArray(bars) { j -> cosine(feat[i], feat[j]) } }
            val barLoud = DoubleArray(bars) { k -> (barStart(k) until barStart(k) + BEATS_PER_BAR).sumOf { loudness[it] } / BEATS_PER_BAR }

            val novelty = DoubleArray(bars)
            for (k in KERNEL_BARS..bars - KERNEL_BARS) {
                var within = 0.0
                var across = 0.0
                for (i in k - KERNEL_BARS until k) for (j in k - KERNEL_BARS until k) within += ssm[i][j]
                for (i in k until k + KERNEL_BARS) for (j in k until k + KERNEL_BARS) within += ssm[i][j]
                for (i in k - KERNEL_BARS until k) for (j in k until k + KERNEL_BARS) across += ssm[i][j]
                val blocks = (KERNEL_BARS * KERNEL_BARS).toDouble()
                val timbre = (within / (2 * blocks) - across / blocks).coerceAtLeast(0.0)
                val before = (k - KERNEL_BARS until k).sumOf { barLoud[it] } / KERNEL_BARS
                val after = (k until k + KERNEL_BARS).sumOf { barLoud[it] } / KERNEL_BARS
                val volume = (abs(after - before) / 6.0).coerceAtMost(1.0)
                novelty[k] = timbre + volume
            }
            // Seuil relatif (pics nets) et plancher absolu : un vrai changement de timbre ou d’au moins ~1,5 dB.
            val valid = (KERNEL_BARS..bars - KERNEL_BARS).map { novelty[it] }
            val mean = valid.average()
            val std = sqrt(valid.sumOf { (it - mean) * (it - mean) } / valid.size)
            val threshold = maxOf(NOVELTY_FLOOR, mean + 0.5 * std)
            val peaks = (KERNEL_BARS..bars - KERNEL_BARS).filter { k ->
                novelty[k] >= threshold && (maxOf(0, k - 3)..minOf(bars - 1, k + 3)).all { novelty[it] <= novelty[k] }
            }.sortedByDescending { novelty[it] }
            val kept = mutableListOf<Int>()
            for (p in peaks) if (kept.all { abs(it - p) >= KERNEL_BARS } && p <= bars - KERNEL_BARS) kept += p
            kept.sorted().forEach { boundaries += barStart(it) }
        }
        boundaries += n
        val raw = boundaries.zipWithNext().filter { (a, b) -> b > a }

        // Intensité relative : volume entre le 10e et le 95e centile des temps (au moins 6 dB d'écart), plus l'attaque.
        val p95 = percentile(loudness, 0.95)
        val p10 = maxOf(percentile(loudness, 0.10), p95 - 20)
        val range = maxOf(p95 - p10, 6.0)
        val sectionLoud = raw.map { (a, b) -> (a until b).sumOf { loudness[it] } / (b - a) }
        val sectionAccent = raw.map { (a, b) -> (a until b).sumOf { accent[it] } / (b - a) }
        val accMin = sectionAccent.min()
        val accRange = (sectionAccent.max() - accMin).takeIf { it > 0.05 }
        return raw.mapIndexed { i, (a, b) ->
            val loud = ((sectionLoud[i] - p10) / range).coerceIn(0.0, 1.0)
            val acc = accRange?.let { ((sectionAccent[i] - accMin) / it).coerceIn(0.0, 1.0) } ?: 1.0
            MusicSection(a, b, sectionLoud[i], (0.8 * loud + 0.2 * acc).coerceIn(0.0, 1.0))
        }
    }

    /** Drop : début de la section intense qui suit le plus gros saut d'intensité (à égalité, la première). */
    internal fun findDrop(sections: List<MusicSection>): Int {
        if (sections.size < 2) return 0
        val floor = maxOf(0.6, 0.85 * sections.maxOf { it.intensity })
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (s in 1 until sections.size) {
            if (sections[s].intensity < floor) continue
            val score = (sections[s].intensity - sections[s - 1].intensity) + 0.25 * sections[s].intensity
            if (score > bestScore + 0.05) {
                bestScore = score
                best = s
            }
        }
        if (best < 0) best = sections.indices.maxBy { sections[it].intensity }
        return sections[best].startBeat
    }
}
