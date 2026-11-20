package dev.highlights.montage

import dev.highlights.core.i18n.Message
import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.dsp.RealFft
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Niveau d'intensité d'une section : dicte le rythme des coupes. */
enum class Intensity { LOW, MID, HIGH }

/**
 * Rôle d'une section dans le morceau. L'intensité dit à quel point ça joue fort ; le rôle dit ce que la section
 * *fait* : une montée et un couplet peuvent avoir la même intensité moyenne sans appeler le même montage.
 */
enum class SectionKind {
    /** Début calme du morceau. */
    INTRO,

    /** Le volume grimpe d'un bout à l'autre et débouche sur plus intense : les coupes peuvent s'accélérer avec. */
    BUILD_UP,

    /** La section qui commence sur la drop. */
    DROP,

    /** Creux entre deux sections plus fortes : une respiration. */
    BREAKDOWN,

    /** Couplet, refrain : tout ce qui ne joue pas un rôle particulier. */
    BODY,

    /** Fin calme du morceau. */
    OUTRO,
}

/**
 * Section de la musique (intro, couplet, montée, drop, breakdown…), délimitée par des changements de timbre ou de
 * volume et alignée sur les mesures. [endBeat] est exclusif.
 */
@Serializable
data class MusicSection(
    val startBeat: Int,
    val endBeat: Int,
    /** Volume moyen (RMS, dB) des temps de la section. */
    val loudnessDb: Double,
    /** 0 = partie la plus calme du morceau, 1 = la plus intense. */
    val intensity: Double,
    /** Écart de volume (dB) entre le dernier tiers et le premier : positif sur une montée. */
    val rise: Double = 0.0,
    val kind: SectionKind = SectionKind.BODY,
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

/** Frappe de la musique : un temps ([beat] entier) ou un contretemps ([beat] en ,5), de force [strength] (0..1). */
data class MusicHit(val at: Duration, val strength: Double, val beat: Double) {
    val onBeat: Boolean get() = beat % 1.0 == 0.0
}

/**
 * Mesures du contenu d'une musique, indépendantes du tempo sauf [bpm], sur la partie qui passerait au rythme rapide :
 * sections à partir de la drop d'intensité au moins 0,7 (toute la suite de la drop s'il n'y en a pas). Servent à
 * apprendre le ressenti de l'utilisateur ([MusicFeelLearner]). Mesurées le 18/11 sur 19 musiques : aucune ne sépare seule
 * les musiques ressenties rapides des calmes (R2D2, rapide, a le moins d'attaques par seconde des 19).
 * [onsets] : attaques par seconde, toutes bandes ; [lowOnsets] (< 220 Hz), [midOnsets] (< 2,2 kHz), [highOnsets] (au-delà) ;
 * [loudnessDb] : volume moyen des temps (dB) ; [percussive] : part percussive de l'énergie (séparation harmonique /
 * percussive par filtres médians, Fitzgerald 2010), 0..1.
 */
@Serializable
data class MusicFeatures(
    val bpm: Double,
    val onsets: Double,
    val lowOnsets: Double,
    val midOnsets: Double,
    val highOnsets: Double,
    val loudnessDb: Double,
    val percussive: Double,
) {
    /** Valeurs dans l'ordre de [NAMES]. */
    fun values(): DoubleArray = doubleArrayOf(bpm, onsets, lowOnsets, midOnsets, highOnsets, loudnessDb, percussive)

    companion object {
        val NAMES = listOf("bpm", "onsets", "lowOnsets", "midOnsets", "highOnsets", "loudnessDb", "percussive")
    }
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
    /** Force de l'attaque au milieu de chaque temps (contretemps), sur la même échelle que [beatAccent]. Vide : inconnue. */
    val halfAccent: DoubleArray = DoubleArray(0),
    /**
     * Saillance de chaque temps (0..1) : la musique y ressort-elle ? Montée du son à l'attaque (le volume juste après
     * contre juste avant) et niveau face aux passages les plus forts de sa section. Là où un instrument frappe à chaque
     * temps (une grosse caisse), aucun ne ressort ; dans une intro au piano, ce sont les notes de la phrase qu'on
     * retient. Vide : inconnue.
     */
    val salience: DoubleArray = DoubleArray(0),
    /** Mesures du contenu de la partie d'après la drop ([MusicFeatures]), pour apprendre le ressenti. Null : inconnues. */
    val features: MusicFeatures? = null,
    /**
     * Décalage appris des kills par rapport aux temps de ce morceau ([KillTimingLearner] : corrections de l'utilisateur
     * concordantes sur ce morceau), appliqué aux kills sans décalage posé à la main. Jamais enregistré avec l'analyse.
     */
    val killShift: Duration = Duration.ZERO,
    /**
     * Drop posée à la main ([MusicDrops.apply], instant dans le morceau) : elle l'emporte sur toute drop détectée, même
     * « depuis le début » ([MusicAnalyzer.fromStart]). Null : drop détectée. Jamais enregistré avec l'analyse.
     */
    val dropByHand: Duration? = null,
    /** Drop corrigée par ce qui a été appris des drops posées ([LearnedDrop.dropFor]) ; null : pas corrigée. Jamais enregistré. */
    val dropLearned: Duration? = null,
) {
    /** Force musicale d'un temps : son attaque, ou sa saillance si elle le distingue davantage. */
    fun strength(beat: Int): Double = maxOf(beatAccent.getOrElse(beat) { 0.0 }, salience.getOrElse(beat) { 0.0 })

    /** Période moyenne (régression sur les temps détectés) : sert à extrapoler au-delà des temps détectés. */
    val beatPeriod: Duration get() = (60.0 / bpm).seconds

    /**
     * Frappes sur lesquelles un kill peut tomber de [from] à [to] inclus : chaque temps, plus les contretemps aussi
     * marqués que les temps qui les entourent (une caisse claire, pas un charleston qui ne fait que remplir la mesure).
     */
    fun hits(from: Int, to: Int): List<MusicHit> = buildList {
        for (b in from..to) {
            add(MusicHit(beatTime(b), strength(b), b.toDouble()))
            if (b < to && isHalfHit(b)) add(MusicHit((beatTime(b) + beatTime(b + 1)) / 2, halfAccent[b], b + 0.5))
        }
    }

    /** Le contretemps qui suit le temps [beat] est-il une frappe ? */
    fun isHalfHit(beat: Int): Boolean {
        val half = halfAccent.getOrElse(beat) { 0.0 }
        val around = minOf(beatAccent.getOrElse(beat) { 0.0 }, beatAccent.getOrElse(beat + 1) { 0.0 })
        return half >= MIN_HALF_ACCENT && half >= HALF_HIT_RATIO * around
    }

    /**
     * Le contretemps qui suit [beat] frappe-t-il plus fort que les deux temps qui l'entourent ([SYNCOPATION]) : la
     * musique y met l'accent, un kill peut y tomber (voir [CutSettings.offbeatKills]).
     */
    fun isSyncopated(beat: Int): Boolean {
        val half = halfAccent.getOrElse(beat) { 0.0 }
        val around = maxOf(beatAccent.getOrElse(beat) { 0.0 }, beatAccent.getOrElse(beat + 1) { 0.0 })
        return half >= MIN_HALF_ACCENT && half >= SYNCOPATION * around
    }

    /** Instant du contretemps qui suit le temps [beat]. */
    fun halfTime(beat: Int): Duration = (beatTime(beat) + beatTime(beat + 1)) / 2

    fun isDownbeat(beat: Int): Boolean = ((beat - downbeatPhase) % 4 + 4) % 4 == 0

    /** Instant du temps [beat], extrapolé au tempo moyen hors des temps détectés. */
    fun beatTime(beat: Int): Duration = when {
        beat < 0 -> beats.first() + beatPeriod * beat
        beat < beats.size -> beats[beat]
        else -> beats.last() + beatPeriod * (beat - beats.lastIndex)
    }

    fun sectionIndexAt(beat: Int): Int = sections.indexOfLast { beat >= it.startBeat }.coerceAtLeast(0)

    fun sectionAt(beat: Int): MusicSection = sections[sectionIndexAt(beat)]

    companion object {
        /** Attaque minimale d'un contretemps, et part de l'attaque des temps voisins qu'il doit atteindre. */
        const val MIN_HALF_ACCENT = 0.5
        const val HALF_HIT_RATIO = 0.95

        /** Un contretemps syncopé frappe au moins autant de fois plus fort que le plus fort des deux temps voisins. */
        const val SYNCOPATION = 1.2
    }
}

object MusicAnalyzer {
    /** À augmenter quand l'analyse change de calcul : les analyses gardées par [MusicLibrary] sont alors refaites. */
    const val VERSION = 6

    /** Part de la durée du montage avant laquelle une drop doit tomber pour y compter, avec de quoi la suivre. */
    private const val REACH = 0.7

    /** Saut d'intensité minimal d'une section à la suivante pour y voir une drop, quand la musique part du début. */
    private const val MIN_JUMP = 0.3

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
    /** Montée de volume (dB) entre le premier et le dernier tiers d'une section pour y voir une montée. */
    private const val RISE_DB = 2.5
    /** Écart d'intensité en dessous des deux voisins pour qu'une section soit un creux. */
    private const val DIP = 0.15

    suspend fun analyze(ffmpeg: FfmpegService, file: Path): MusicAnalysis {
        if (!file.isRegularFile()) throw InputException(Message("error.music.notFound", "file" to file))
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
        if (samples.size < SAMPLE_RATE * 5) throw HighlightsException(Message("error.music.tooShort", "duration" to duration))

        val spec = melSpectrogram(samples)
        val onset = onsetEnvelope(spec)
        // Tempo sur une enveloppe plus lissée : les pics fins (~12 ms) rendent l'autocorrélation sensible à la quantification du lag.
        val bpmEstimate = estimateTempo(smooth(onset, sigma = 3.0), FPS, bandEnvelopes(spec))
        val beatFrames = trackBeats(onset, FPS * 60.0 / bpmEstimate)
        if (beatFrames.size < 8) throw HighlightsException(Message("error.music.noTempo", "name" to file.fileName.toString()))

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
        // Contretemps : même mesure d'attaque au milieu de chaque temps, rapportée à l'échelle des temps.
        val halfAccent = DoubleArray(n) { i ->
            val mid = (frameOf[i] + frameOf[i + 1]) / 2
            if (mid - 2 > onset.lastIndex) 0.0
            else ((maxOf(0, mid - 2)..minOf(onset.lastIndex, mid + 2)).maxOf { onset[it] } / accentScale).coerceIn(0.0, 1.0)
        }
        val low = DoubleArray(n) { i -> (frameOf[i]..minOf(frameOf[i] + 2, spec.frames - 1)).sumOf { spec.lowEnergy[it] } }
        val novelty = DoubleArray(n) { i -> if (i == 0) 0.0 else 1 - cosine(beatMel[i], beatMel[i - 1]) }

        // Premier temps de mesure : accent des basses (non compressé), attaque et changement de timbre.
        val lowNorm = normalize(low)
        val novNorm = normalize(novelty)
        val phaseScores = DoubleArray(BEATS_PER_BAR)
        for (i in 0 until n) phaseScores[i % BEATS_PER_BAR] += lowNorm[i] + 0.5 * accent[i] + 0.5 * novNorm[i]
        val phase = phaseScores.indices.maxBy { phaseScores[it] }

        val raw = detectSections(beatMel, loudness, accent, phase, n)
        val drop = findDrop(raw)
        val sections = classify(raw, drop)
        log.info {
            "Structure : " + sections.joinToString(" ") { "${it.kind.name.lowercase()}(${it.beats}t, ${"%.2f".format(it.intensity)})" }
        }
        // Ce qui ressort : une note qui attaque fort, ou le son qui change de nature (un instrument qui entre, une coupure).
        val notes = salience(samples, beats)
        val changes = timbreChanges(beatMel)
        val salience = DoubleArray(n) { maxOf(notes[it], changes[it]) }
        log.info {
            "Moments qui ressortent (notes + changements de son) : " + sections.joinToString(" ") { s ->
                val range = s.startBeat until minOf(s.endBeat, n)
                "${s.kind.name.lowercase()} ${range.count { notes[it] >= SALIENT }}+${range.count { changes[it] >= SALIENT && notes[it] < SALIENT }}/${s.beats}"
            }
        }
        val features = features(spec, sections, drop, beats, loudness, bpm)
        log.info { "Contenu après la drop : $features" }
        return MusicAnalysis(file, duration, bpm, beats, phase, energy, accent, sections, drop, halfAccent, salience, features)
    }

    /**
     * Mesures du contenu ([MusicFeatures]) sur les sections à partir de la drop d'intensité au moins
     * [FEATURE_INTENSITY], ou sur toute la suite de la drop s'il n'y en a pas. Attaques : pics du flux spectral positif
     * de la bande (normalisé à son 99e centile), maxima sur ±[PEAK_RADIUS] trames, au-dessus de la moyenne sur une
     * seconde plus 0,1 écart-type, à [MIN_ONSET_GAP] au moins l'un de l'autre.
     */
    private fun features(spec: Spectrogram, sections: List<MusicSection>, drop: Int, beats: List<Duration>, loudness: DoubleArray, bpm: Double): MusicFeatures {
        val dropIndex = sections.indexOfLast { drop >= it.startBeat }.coerceAtLeast(0)
        val after = sections.drop(dropIndex)
        val part = after.filter { it.intensity >= FEATURE_INTENSITY }.ifEmpty { after }
        fun frameOf(beat: Int) = ((beats[beat.coerceIn(0, beats.lastIndex)].inWholeMicroseconds / 1e6) * FPS).toInt().coerceIn(0, spec.frames)
        val inside = BooleanArray(spec.frames)
        part.forEach { s -> for (f in frameOf(s.startBeat) until frameOf(s.endBeat)) inside[f] = true }
        val seconds = inside.count { it } / FPS
        if (seconds <= 0) return MusicFeatures(bpm, 0.0, 0.0, 0.0, 0.0, -60.0, 0.0)
        fun density(bands: IntRange): Double {
            val flux = DoubleArray(spec.frames)
            for (t in 1 until spec.frames) for (k in bands) {
                val d = spec.mel[t][k] - spec.mel[t - 1][k]
                if (d > 0) flux[t] += d
            }
            val scale = percentile(flux, 0.99).takeIf { it > 1e-9 } ?: return 0.0
            for (t in flux.indices) flux[t] /= scale
            val mean = flux.average()
            val std = sqrt(flux.sumOf { (it - mean) * (it - mean) } / flux.size)
            val window = FPS.roundToInt()
            val prefix = DoubleArray(flux.size + 1).also { for (i in flux.indices) it[i + 1] = it[i] + flux[i] }
            val gap = (MIN_ONSET_GAP.inWholeMilliseconds / 1000.0 * FPS).roundToInt()
            var count = 0
            var last = -1_000_000
            for (t in flux.indices) {
                if (!inside[t]) continue
                val lo = maxOf(0, t - PEAK_RADIUS)
                val hi = minOf(flux.lastIndex, t + PEAK_RADIUS)
                if ((lo..hi).any { flux[it] > flux[t] }) continue
                val a = maxOf(0, t - window / 2)
                val b = minOf(flux.size, t + window / 2 + 1)
                if (flux[t] < MIN_ONSET || flux[t] <= (prefix[b] - prefix[a]) / (b - a) + 0.1 * std) continue
                if (t - last < gap) continue
                count++
                last = t
            }
            return count / seconds
        }
        val partBeats = part.flatMap { (it.startBeat until minOf(it.endBeat, loudness.size)).toList() }
        val loud = if (partBeats.isEmpty()) -60.0 else partBeats.sumOf { loudness[it] } / partBeats.size
        return MusicFeatures(
            bpm = bpm,
            onsets = density(0 until MEL_BANDS),
            lowOnsets = density(0 until lowBands),
            midOnsets = density(lowBands until midBands),
            highOnsets = density(midBands until MEL_BANDS),
            loudnessDb = loud,
            percussive = percussiveShare(spec, inside),
        )
    }

    /**
     * Part percussive de l'énergie des trames [inside] : médiane dans le temps ([HPSS_TIME] trames : l'harmonique, ce
     * qui dure) contre médiane dans le spectre ([HPSS_BANDS] bandes : le percussif, ce qui couvre le spectre d'un coup),
     * masque doux P² / (H² + P²) (Fitzgerald 2010), sur l'amplitude mel linéaire.
     */
    private fun percussiveShare(spec: Spectrogram, inside: BooleanArray): Double {
        var total = 0.0
        var perc = 0.0
        val column = DoubleArray(HPSS_TIME)
        val row = DoubleArray(HPSS_BANDS)
        // Amplitude linéaire (le spectre est gardé en ln(1 + 100 × amplitude)) : sur l'échelle compressée, les deux
        // médianes se valaient sur toutes les vraies musiques (part de 0,50 partout).
        fun amp(t: Int, k: Int) = kotlin.math.expm1(spec.mel[t][k])
        for (t in 0 until spec.frames) {
            if (!inside[t]) continue
            for (k in 0 until MEL_BANDS) {
                val v = amp(t, k)
                for (i in 0 until HPSS_TIME) column[i] = amp((t + i - HPSS_TIME / 2).coerceIn(0, spec.frames - 1), k)
                for (i in 0 until HPSS_BANDS) row[i] = amp(t, (k + i - HPSS_BANDS / 2).coerceIn(0, MEL_BANDS - 1))
                column.sort()
                row.sort()
                val h = column[HPSS_TIME / 2]
                val p = row[HPSS_BANDS / 2]
                val e = v * v
                total += e
                perc += e * p * p / (h * h + p * p + 1e-12)
            }
        }
        return if (total <= 0) 0.0 else perc / total
    }

    /** Intensité des sections mesurées pour [MusicFeatures] : celle des parties rapides (`montage.fastParts.minIntensity`). */
    private const val FEATURE_INTENSITY = 0.7
    private const val PEAK_RADIUS = 4
    private val MIN_ONSET_GAP = 100.milliseconds

    /** Attaque minimale, en part de la forte attaque de la bande (99e centile) : les fuites d'une autre bande ne comptent pas. */
    private const val MIN_ONSET = 0.25
    private const val HPSS_TIME = 17
    private const val HPSS_BANDS = 9

    /**
     * Saillance de chaque temps (voir [MusicAnalysis.salience]), jugée dans son voisinage ([SALIENCE_WINDOW] temps de
     * part et d'autre) : montée du volume de [SALIENCE_BEFORE] avant le temps à [SALIENCE_AFTER] après, comparée à la
     * montée médiane des temps voisins (comptée à partir de 3 dB de plus, pleine à 13 dB), multipliée par le niveau du
     * temps face au plus fort de ses voisins (plein à ce niveau, nul 12 dB en dessous : une petite note qui sort du
     * silence ne pèse pas autant qu'un accord). Jugée sur toute la section, une montée finale bien plus forte que le
     * piano étouffait ses notes. Mesuré sur « Rome Is Burning » : les temps 0, 6, 8, 14, 16, 22, 30, 38, 46 (la phrase
     * du piano, mi puis fa jusqu'à +33 dB) puis les frappes de la montée ; 0 % des temps des drops régulières de deux
     * autres morceaux.
     */
    internal fun salience(samples: FloatArray, beats: List<Duration>): DoubleArray {
        fun rms(from: Duration, to: Duration): Double {
            val a = (from.inWholeMicroseconds * SAMPLE_RATE / 1_000_000).toInt().coerceIn(0, samples.size)
            val b = (to.inWholeMicroseconds * SAMPLE_RATE / 1_000_000).toInt().coerceIn(a, samples.size)
            if (b <= a) return 1e-6
            return sqrt((a until b).sumOf { samples[it].toDouble() * samples[it] } / (b - a)) + 1e-6
        }
        val n = beats.size
        val rise = DoubleArray(n) { 20 * log10(rms(beats[it], beats[it] + SALIENCE_AFTER) / rms(beats[it] - SALIENCE_BEFORE, beats[it] - SALIENCE_GAP)) }
        val level = DoubleArray(n) { 20 * log10(rms(beats[it], beats[it] + SALIENCE_AFTER)) }
        return DoubleArray(n) { i ->
            val lo = maxOf(0, i - SALIENCE_WINDOW)
            val hi = minOf(n, i + SALIENCE_WINDOW + 1)
            val around = (lo until hi).filter { it != i }.map { rise[it] }.sorted()
            if (around.isEmpty()) return@DoubleArray 0.0
            val contrast = rise[i] - around[around.size / 2]
            val loud = (1 + (level[i] - (lo until hi).maxOf { level[it] }) / 12).coerceIn(0.0, 1.0)
            ((contrast - 3) / 10).coerceIn(0.0, 1.0) * loud
        }
    }

    /**
     * Changements de son de chaque temps (0..1) : le spectre du temps (bandes mel, en log) s'écarte de celui du temps
     * d'avant au moins [CHANGE_RATIO] fois plus que d'habitude alentour ([SALIENCE_WINDOW] temps de part et d'autre),
     * d'au moins [MIN_CHANGE], et durablement (le temps d'après ressemble au nouveau son plus qu'à l'ancien : pas un
     * bruit isolé ; et le son d'avant-hier en diffère aussi : pas le retour à la normale après ce bruit). Un instrument qui entre, une voix, une coupure avant la drop. Plein à [CHANGE_RATIO] + 3 fois. Mesuré
     * sur six morceaux : 9 à 50 par morceau, presque tous à l'intérieur des sections (sur « Rome Is Burning », l'arrivée
     * du fa de chaque phrase de piano ; sur « Fortunate Son », les entrées de guitare et de voix).
     */
    internal fun timbreChanges(beatMel: Array<DoubleArray>): DoubleArray {
        val n = beatMel.size
        // Forme du spectre, indépendamment du volume : chaque temps centré sur sa moyenne (le spectre est déjà en log).
        val shape = Array(n) { i -> val m = beatMel[i].average(); DoubleArray(beatMel[i].size) { k -> beatMel[i][k] - m } }
        fun distance(a: Int, b: Int) = 1 - cosine(shape[a], shape[b])
        val change = DoubleArray(n) { i -> if (i == 0) 0.0 else distance(i, i - 1) }
        return DoubleArray(n) { i ->
            if (i < 2 || i > n - 2) return@DoubleArray 0.0
            val around = (maxOf(1, i - SALIENCE_WINDOW) until minOf(n, i + SALIENCE_WINDOW + 1)).filter { it != i }.map { change[it] }.sorted()
            val usual = around[around.size / 2].coerceAtLeast(1e-6)
            // Durable (le temps d'après ressemble au nouveau son), et nouveau pour de bon : pas le retour au son d'avant
            // après un bruit d'un seul temps.
            val lasting = distance(i + 1, i) < distance(i + 1, i - 1) && distance(i, i - 2) >= change[i] / 2
            val ratio = change[i] / usual
            if (!lasting || change[i] < MIN_CHANGE || ratio < CHANGE_RATIO) 0.0
            else (SALIENT + (ratio - CHANGE_RATIO) / 5).coerceAtMost(1.0)
        }
    }

    private const val CHANGE_RATIO = 3.0
    private const val MIN_CHANGE = 0.02

    private val SALIENCE_AFTER = 60.milliseconds
    private val SALIENCE_BEFORE = 90.milliseconds
    private val SALIENCE_GAP = 15.milliseconds
    private const val SALIENCE_WINDOW = 8

    /** Saillance à partir de laquelle une note « ressort » (journal, tests). */
    const val SALIENT = 0.4

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
        val fft = RealFft(FRAME)
        val windowed = DoubleArray(FRAME)
        val power = DoubleArray(fft.bins)
        val lowBins = (200.0 * FRAME / SAMPLE_RATE).roundToInt().coerceAtLeast(2)
        val mel = Array(frames) { DoubleArray(MEL_BANDS) }
        val lowEnergy = DoubleArray(frames)
        for (f in 0 until frames) {
            val start = f * HOP
            for (i in 0 until FRAME) windowed[i] = samples[start + i] * window[i]
            fft.power(windowed, power)
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

    /**
     * Attaques par bande (flux positif, normalisé, lissé comme l'enveloppe du tempo) : basses (< 220 Hz), médiums
     * (< [MID_TOP_HZ]) et aigus. Servent à voir si quelque chose bat entre deux temps lus ([halvesTempo]).
     */
    private fun bandEnvelopes(spec: Spectrogram): List<DoubleArray> =
        listOf(0 until lowBands, lowBands until midBands, midBands until MEL_BANDS).map { bands ->
            val flux = DoubleArray(spec.frames)
            for (t in 1 until spec.frames) for (k in bands) {
                val d = spec.mel[t][k] - spec.mel[t - 1][k]
                if (d > 0) flux[t] += d
            }
            smooth(normalize(flux), sigma = 3.0)
        }

    /** Bandes sous [MID_TOP_HZ] : basses et médiums (voix, guitare, caisse claire) ; au-dessus, les aigus (charleston). */
    private val midBands: Int by lazy {
        val lo = hzToMel(30.0)
        val hi = hzToMel(SAMPLE_RATE / 2.0)
        (0 until MEL_BANDS).count { melToHz(lo + (hi - lo) * (it + 1) / (MEL_BANDS + 1)) < MID_TOP_HZ }
    }

    private const val MID_TOP_HZ = 2200.0

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

    /**
     * Autocorrélation de l'enveloppe d'attaques, pondérée autour de 120 BPM (log-normale, une octave), puis choix de
     * l'octave ([halvesTempo], avec les attaques par bande [bands]) : un tempo vif lu sur les croches d'une musique
     * lente est ramené à la noire.
     */
    internal fun estimateTempo(onset: DoubleArray, fps: Double, bands: List<DoubleArray> = emptyList()): Double {
        val minLag = (fps * 60.0 / 200.0).toInt()
        val maxLag = (fps * 60.0 / 60.0).toInt().coerceAtMost(onset.size / 2)
        // Jusqu'au double du plus long décalage : la noire d'un tempo lu doublé.
        val r = autocorrelation(onset, minOf(2 * maxLag + 2, onset.size - 1))
        var bestLag = minLag
        var bestScore = Double.NEGATIVE_INFINITY
        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * fps / lag
            val weight = exp(-0.5 * (log2(bpm / 120.0) / 1.0).let { it * it })
            scores[lag] = r[lag] * weight
            if (scores[lag] > bestScore) {
                bestScore = scores[lag]
                bestLag = lag
            }
        }
        val between = if (bands.isEmpty()) Double.POSITIVE_INFINITY else bands.maxOf { near(autocorrelation(it, bestLag), bestLag / 2.0) }
        if (halvesTempo(r, bestLag, fps, between)) {
            // Noire = deux croches lues : le maximum de l'autocorrélation autour du double du décalage.
            val lag = (2 * bestLag - 2..2 * bestLag + 2).filter { it in 1 until r.lastIndex }.maxBy { r[it] }
            log.info { "Tempo : ${"%.1f".format(60.0 * fps / bestLag)} BPM lus sur les croches, ramenés à la noire, ${"%.1f".format(60.0 * fps / lag)}" }
            return 60.0 * fps / refine(r, lag)
        }
        return 60.0 * fps / if (bestLag in (minLag + 1) until maxLag) refine(scores, bestLag) else bestLag.toDouble()
    }

    /**
     * Autocorrélation de [values] centrées, décalages 0 à [top], rapportée à la valeur au décalage nul. Les produits sont
     * divisés par la longueur entière (pas par le nombre de produits) : comme avant le choix d'octave, une pulsation
     * parfaitement régulière garde le plus court de deux décalages à égalité (boucle à 170 BPM lue 170, pas 85).
     */
    private fun autocorrelation(values: DoubleArray, top: Int): DoubleArray {
        val mean = values.average()
        val centered = DoubleArray(values.size) { values[it] - mean }
        val zero = centered.sumOf { it * it } / centered.size
        return DoubleArray(top + 1) { lag ->
            if (zero < 1e-12 || lag >= centered.size) 0.0
            else {
                var acc = 0.0
                for (t in 0 until centered.size - lag) acc += centered[t] * centered[t + lag]
                acc / centered.size / zero
            }
        }
    }

    /** Plus forte valeur de [r] autour du décalage fractionnaire [at] (une trame de marge de part et d'autre). */
    private fun near(r: DoubleArray, at: Double): Double =
        (floor(at).toInt() - 1..ceil(at).toInt() + 1).filter { it in 1..r.lastIndex }.maxOfOrNull { r[it] } ?: 0.0

    /** Interpolation parabolique autour du maximum [lag] de [values]. */
    private fun refine(values: DoubleArray, lag: Int): Double {
        if (lag <= 0 || lag >= values.lastIndex) return lag.toDouble()
        val a = values[lag - 1]
        val b = values[lag]
        val c = values[lag + 1]
        val denom = a - 2 * b + c
        return if (denom != 0.0) lag + 0.5 * (a - c) / denom else lag.toDouble()
    }

    /**
     * Tempo lu doublé ? Une musique lente aux croches très régulières se lit parfois sur ses croches, au-dessus de
     * [HALVE_ABOVE] BPM (« Lose Yourself » : 171 lus pour 86). Elle se trahit par deux choses à la fois :
     * - le temps lu est la plus fine pulsation dans toutes les bandes : basses, médiums et aigus n'ont aucune attaque
     *   régulière entre deux temps lus ([between] : la plus forte autocorrélation des bandes au demi-temps lu,
     *   au plus [HALVE_BETWEEN]) ;
     * - la noire (deux temps lus) est presque aussi nette que le temps lu (autocorrélation de l'enveloppe, au moins
     *   [HALVE_BEAT] × celle du temps lu).
     * Une vraie musique rapide a quelque chose entre ses temps (charleston, caisse claire, basse) ou une noire bien
     * moins nette. Mesuré sur 19 musiques (D:\Musique et « music sample », tableau dans le README, « Tempo ») :
     * « Lose Yourself » demi-temps −0,28, noire 0,97 ; gardées : « R2D2 » (161) 0,02 et 0,73, « Menace » (155) 0,12
     * et 1,12, « life kinda sucks » (188) 0,24, « Undercover Martyn » (160) 0,32, « GO2 - Spitfire » (157) 0,45 ; une
     * boucle synthétique à 170 BPM (grosse caisse à chaque temps, charleston entre) 0,81. Une seule musique ramenée :
     * le critère tient sur un exemple, d'où des seuils loin des musiques gardées.
     */
    internal fun halvesTempo(r: DoubleArray, lag: Int, fps: Double, between: Double): Boolean {
        if (60.0 * fps / lag < HALVE_ABOVE || 2 * lag + 2 > r.lastIndex) return false
        val beat = r[lag].takeIf { it > 1e-9 } ?: return false
        val half = near(r, 2.0 * lag) / beat
        log.debug { "Octave à ${"%.1f".format(60.0 * fps / lag)} BPM : entre deux temps ${"%.2f".format(between)}, noire ${"%.2f".format(half)}" }
        return between <= HALVE_BETWEEN && half >= HALVE_BEAT
    }

    /** Tempo lu au-dessus duquel il peut être le double du vrai (voir [halvesTempo]). */
    private const val HALVE_ABOVE = 140.0

    /** Pulsation de la noire (deux temps lus) face à celle du temps lu, au moins, pour ramener le tempo à la noire. */
    private const val HALVE_BEAT = 0.85

    /** Autocorrélation des bandes au demi-temps lu, au plus, pour ramener le tempo à la noire (rien entre deux temps). */
    private const val HALVE_BETWEEN = -0.1

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
            MusicSection(a, b, sectionLoud[i], (0.8 * loud + 0.2 * acc).coerceIn(0.0, 1.0), rise = rise(loudness, a, b))
        }
    }

    /** Écart de volume entre le dernier et le premier tiers d'une section : ce qui monte sur une montée. */
    private fun rise(loudness: DoubleArray, from: Int, to: Int): Double {
        val third = (to - from) / 3
        if (third < 1) return 0.0
        val first = (from until from + third).sumOf { loudness[it] } / third
        val last = (to - third until to).sumOf { loudness[it] } / third
        return last - first
    }

    /**
     * Rôle de chaque section. Le drop est déjà repéré ; une montée grimpe nettement d'un bout à l'autre et débouche sur
     * plus intense qu'elle ; un breakdown est un creux entre deux sections plus fortes ; l'intro et l'outro sont les
     * extrémités, quand elles sont plus calmes que le reste du morceau. Le reste est du corps de morceau.
     */
    internal fun classify(sections: List<MusicSection>, dropBeat: Int): List<MusicSection> {
        if (sections.isEmpty()) return sections
        val median = sections.map { it.intensity }.sorted()[sections.size / 2]
        val dropIndex = sections.indexOfLast { dropBeat >= it.startBeat }.coerceAtLeast(0)
        return sections.mapIndexed { i, s ->
            val next = sections.getOrNull(i + 1)
            val previous = sections.getOrNull(i - 1)
            // Une montée débouche toujours sur plus intense qu'elle.
            val leadsUp = next != null && next.intensity > s.intensity + 0.1
            val kind = when {
                i == dropIndex && sections.size > 1 -> SectionKind.DROP
                i == 0 && s.intensity < median -> SectionKind.INTRO
                // La section qui mène à la drop en est la montée, par construction : un riser perd souvent ses basses
                // en gagnant ses aigus, si bien que son volume peut même baisser. Ailleurs, il faut l'entendre monter.
                leadsUp && (i == dropIndex - 1 || s.rise >= RISE_DB) -> SectionKind.BUILD_UP
                i == sections.lastIndex && s.intensity < median -> SectionKind.OUTRO
                previous != null && next != null &&
                    s.intensity + DIP < minOf(previous.intensity, next.intensity) -> SectionKind.BREAKDOWN
                else -> SectionKind.BODY
            }
            s.copy(kind = kind)
        }
    }

    /**
     * Musique prise depuis son début sur au plus [length] : la drop devient la plus forte de celles qui tombent assez
     * tôt pour être dans le montage (avant [REACH] de [length]), et les sections sont reclassées autour d'elle. Une
     * musique peut avoir plusieurs drops (Spitfire : 0:23 et 4:15) ; la mieux notée peut être hors du montage, qui
     * n'aurait alors plus de drop du tout. Inchangée si la drop y est déjà, ou si aucune section n'y saute assez
     * ([MIN_JUMP]) pour en être une.
     */
    fun fromStart(analysis: MusicAnalysis, length: Duration): MusicAnalysis {
        val sections = analysis.sections
        if (sections.size < 2 || analysis.dropByHand != null) return analysis
        val start = analysis.beatTime(sections.first().startBeat)
        val reach = length * REACH
        if (analysis.beatTime(analysis.dropBeat) - start <= reach) return analysis
        val early = sections.takeWhile { analysis.beatTime(it.startBeat) - start <= reach }
        val candidates = (1 until early.size).filter { early[it].intensity - early[it - 1].intensity >= MIN_JUMP }
        if (candidates.isEmpty()) return analysis
        val drop = findDrop(early.take(candidates.max() + 1))
        if (candidates.none { early[it].startBeat == drop }) return analysis
        log.info { "Musique depuis le début : drop au temps $drop (${analysis.beatTime(drop)}) au lieu de ${analysis.dropBeat} (${analysis.beatTime(analysis.dropBeat)})" }
        return analysis.copy(sections = classify(sections, drop), dropBeat = drop)
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
