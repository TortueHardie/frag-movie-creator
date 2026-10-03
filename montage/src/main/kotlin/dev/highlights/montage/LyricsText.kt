package dev.highlights.montage

import dev.highlights.core.model.TimeRange
import dev.highlights.editing.story.Caption
import java.nio.file.Path
import java.text.Normalizer
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Ligne des paroles fournies : son début quand le fichier le donne (.lrc), ses mots. */
data class LyricLine(val start: Duration?, val words: List<String>)

/**
 * Paroles fournies par l'utilisateur, en texte (.txt) ou synchronisées par ligne (.lrc). Whisper se trompe souvent de
 * mots sur une musique (« The world's emerging » pour « Rome is burning ») mais les place bien dans le temps : les mots
 * fournis prennent les instants des mots entendus qui leur ressemblent ([align]), ceux qui n'en trouvent pas se placent
 * entre leurs voisins. Sans transcription, un .lrc suffit : les mots se répartissent dans leur ligne ([spread]).
 */
object LyricsText {
    /** Horodatage de ligne LRC : [mm:ss], [mm:ss.xx] ou [mm:ss:xx]. */
    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** Balise d'en-tête LRC ([ar:Artiste], [ti:Titre]…) : pas des paroles. */
    private val TAG = Regex("""^\[[a-zA-Z#]+:.*]$""")

    /** Ressemblance (0..1) au-delà de laquelle un mot entendu peut être le mot fourni. */
    private const val MATCH = 0.5

    /** Un mot entendu ne peut être celui d'une ligne .lrc qu'autour de cette ligne. */
    private val LINE_SLACK = 1500.milliseconds

    /** Durée d'un mot qu'on ne sait pas placer autrement (débit chanté moyen, mesuré sur la transcription de « Rome Is Burning »). */
    private val WORD = 350.milliseconds

    /** Fichier de paroles posé à côté de la musique, du même nom : .lrc d'abord (synchronisé), sinon .txt. */
    fun sidecar(music: Path): Path? =
        listOf("lrc", "txt").map { music.resolveSibling("${music.nameWithoutExtension}.$it") }.firstOrNull { it.isRegularFile() }

    fun read(file: Path): List<LyricLine> = parse(file.readText().removePrefix("﻿"))

    fun parse(text: String): List<LyricLine> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !TAG.matches(it) }.flatMap { line ->
            val times = TIME.findAll(line).map { m ->
                val fraction = m.groupValues[3].let { if (it.isEmpty()) 0 else it.padEnd(3, '0').toInt() }
                (m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + fraction).milliseconds
            }.toList()
            val words = line.replace(TIME, " ").split(Regex("\\s+")).map(Lyrics::trimWord).filter { w -> w.any { it.isLetterOrDigit() } }
            // Un refrain répété : une même ligne précédée de plusieurs horodatages.
            if (times.isEmpty()) listOf(LyricLine(null, words)) else times.map { LyricLine(it, words) }
        }.filter { it.words.isNotEmpty() }
        return if (lines.all { it.start != null }) lines.sortedBy { it.start } else lines
    }

    /** Mot comparable : minuscules, sans accents ni ponctuation. */
    internal fun normalize(word: String): String =
        Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).filter { it.isLetterOrDigit() }

    /** 1 - distance d'édition / longueur du plus long : 1 pour deux mots identiques. */
    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }

    /**
     * Mots fournis placés sur les mots entendus ([heard], instants dans la musique) : alignement des deux suites qui
     * garde l'ordre et maximise la ressemblance des paires (un mot entendu va à un seul mot fourni, et seulement s'ils
     * se ressemblent assez). Les mots restés seuls se répartissent, au prorata de leur longueur, entre leurs voisins
     * placés ; ceux d'une ligne .lrc ne commencent pas avant elle.
     */
    fun align(lines: List<LyricLine>, heard: List<Caption>): List<Caption> {
        val given = lines.flatMapIndexed { li, line -> line.words.map { li to it } }
        if (given.isEmpty()) return emptyList()
        if (heard.isEmpty()) return spread(lines)
        val starts = lines.map { it.start }
        fun window(li: Int): ClosedRange<Duration>? {
            val start = starts[li] ?: return null
            val next = starts.drop(li + 1).firstOrNull { it != null && it > start } ?: (start + WORD * (lines[li].words.size + 4))
            return (start - LINE_SLACK)..(next + LINE_SLACK)
        }
        val windows = lines.indices.map(::window)
        val a = given.map { normalize(it.second) }
        val b = heard.map { normalize(it.text) }
        val n = a.size
        val m = b.size
        // Note de chaque paire possible : ressemblance au-delà du seuil, dans la fenêtre de sa ligne si elle en a une.
        fun gain(i: Int, j: Int): Double {
            val w = windows[given[i].first]
            if (w != null && heard[j].range.start !in w) return Double.NEGATIVE_INFINITY
            val s = similarity(a[i], b[j])
            return if (s >= MATCH) s else Double.NEGATIVE_INFINITY
        }
        val score = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            score[i][j] = maxOf(score[i - 1][j], score[i][j - 1], score[i - 1][j - 1] + gain(i - 1, j - 1))
        }
        val matched = arrayOfNulls<TimeRange>(n)
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val g = gain(i - 1, j - 1)
            when {
                g.isFinite() && score[i][j] == score[i - 1][j - 1] + g -> { matched[i - 1] = heard[j - 1].range; i--; j-- }
                score[i][j] == score[i - 1][j] -> i--
                else -> j--
            }
        }
        return fill(given, starts, matched)
    }

    /** Sans transcription : chaque ligne .lrc répartit ses mots de son début au suivant (au plus un débit chanté normal). */
    fun spread(lines: List<LyricLine>): List<Caption> {
        if (lines.any { it.start == null }) return emptyList()
        val given = lines.flatMapIndexed { li, line -> line.words.map { li to it } }
        return fill(given, lines.map { it.start }, arrayOfNulls(given.size))
    }

    /** Instants des mots non placés, entre leurs voisins placés (ou le début de leur ligne, ou un débit moyen). */
    private fun fill(given: List<Pair<Int, String>>, starts: List<Duration?>, matched: Array<TimeRange?>): List<Caption> {
        val ranges = matched.copyOf()
        var k = 0
        while (k < given.size) {
            if (ranges[k] != null) { k++; continue }
            // Une suite de mots non placés s'arrête au début d'une ligne .lrc : la ligne suivante a son propre départ.
            var end = k
            while (end < given.size && ranges[end] == null && (end == k || given[end].first == given[end - 1].first || starts[given[end].first] == null)) end++
            val run = k until end
            val before = ranges.getOrNull(k - 1)?.end
            val after = ranges.getOrNull(end)?.start ?: given.getOrNull(end)?.let { starts[it.first] }
            val lineStart = starts[given[k].first]
            val from = listOfNotNull(before, lineStart).maxOrNull() ?: ((after ?: Duration.ZERO) - WORD * run.count()).coerceAtLeast(Duration.ZERO)
            // Plus loin que nécessaire (un pont instrumental avant le mot suivant) : un débit chanté normal.
            val natural = from + WORD * run.count()
            val to = after?.let { minOf(it, natural) }?.takeIf { it > from } ?: natural
            val weights = run.map { given[it].second.length.coerceAtLeast(1) }
            val total = weights.sum().toDouble()
            var t = from
            run.forEachIndexed { r, idx ->
                val next = t + (to - from) * (weights[r] / total)
                ranges[idx] = TimeRange(t, next)
                t = next
            }
            k = end
        }
        return given.indices.map { Caption(ranges[it]!!, given[it].second) }.sortedBy { it.range.start }
    }
}
